package nexus.games.net;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import nexus.games.rules.Game;

/**
 * Gambit's AlphaZero network, evaluated in Java with the Vector API: the {@code .gnet} files
 * exported by Gambit's training (batch norms already folded into the convolutions) run here with
 * the same results as Gambit's C# engine.
 *
 * <pre>
 *   planes -> conv3x3 + ReLU -> [conv3x3 + ReLU -> conv3x3 -> + input -> ReLU] x blocks
 *          -> policy: conv1x1 + ReLU -> fully connected -> logits (one per action)
 *          -> value:  conv1x1 + ReLU -> fully connected + ReLU -> fully connected -> tanh
 * </pre>
 *
 * Activations are pixel-major (HWC). A 3x3 convolution walks the cells and, for each neighbour
 * inside the board, adds {@code input[neighbour, ci] * W[tap, ci, 0..Cout]} to the cell's output
 * channels: the weights of one tap and input channel are contiguous over the outputs, so each step
 * is one broadcast and one fused multiply-add per vector of output channels.
 *
 * <p>File format (little endian): "GNET", int version (1), the game name as a .NET string
 * (7-bit-encoded length, UTF-8), nine ints (planes, height, width, actions, blocks, channels,
 * policy channels, value channels, value hidden), the number of weight arrays, then each array as
 * (int length, floats).
 */
public final class GnetNetwork {
    private static final VectorSpecies<Float> S = FloatVector.SPECIES_PREFERRED;
    private static final int L = S.length();

    public record Spec(String game, int planes, int height, int width, int actions, int blocks, int channels, int policyChannels,
                       int valueChannels, int valueHidden) {
        public int area() {
            return height * width;
        }

        @Override
        public String toString() {
            return blocks + "x" + channels + " ResNet for " + game;
        }
    }

    private final Spec spec;
    private final float[] stemW, stemB;
    private final float[][] blockW1, blockB1, blockW2, blockB2;
    private final float[] polConvW, polConvB, polFcW, polFcB, valConvW, valConvB, valFc1W, valFc1B, valFc2W, valFc2B;
    private final int[] neighbours;
    private final ThreadLocal<Scratch> scratch;
    private final long parameters;

    private final class Scratch {
        final float[] input, a, b, t, head, hidden;

        Scratch() {
            int area = spec.area();
            input = new float[spec.planes * area];
            a = new float[spec.channels * area];
            b = new float[spec.channels * area];
            t = new float[spec.channels * area];
            head = new float[Math.max(spec.policyChannels, spec.valueChannels) * area];
            hidden = new float[spec.valueHidden];
        }
    }

    public GnetNetwork(Spec spec, List<float[]> w) {
        this.spec = spec;
        int i = 0, c = spec.channels;
        long params = 0;
        for (var a : w) params += a.length;
        parameters = params;
        stemW = transpose3x3(w.get(i++), c, spec.planes);
        stemB = w.get(i++);
        blockW1 = new float[spec.blocks][];
        blockB1 = new float[spec.blocks][];
        blockW2 = new float[spec.blocks][];
        blockB2 = new float[spec.blocks][];
        for (int b = 0; b < spec.blocks; b++) {
            blockW1[b] = transpose3x3(w.get(i++), c, c);
            blockB1[b] = w.get(i++);
            blockW2[b] = transpose3x3(w.get(i++), c, c);
            blockB2[b] = w.get(i++);
        }
        polConvW = w.get(i++);
        polConvB = w.get(i++);
        polFcW = w.get(i++);
        polFcB = w.get(i++);
        valConvW = w.get(i++);
        valConvB = w.get(i++);
        valFc1W = w.get(i++);
        valFc1B = w.get(i++);
        valFc2W = w.get(i++);
        valFc2B = w.get(i++);
        if (i != w.size()) throw new IllegalArgumentException("expected " + i + " weight arrays, got " + w.size());
        int area = spec.area();
        check(polFcW, spec.actions * spec.policyChannels * area);
        check(valFc1W, spec.valueHidden * spec.valueChannels * area);
        neighbours = new int[area * 9];
        for (int y = 0; y < spec.height; y++)
            for (int x = 0; x < spec.width; x++)
                for (int t = 0; t < 9; t++) {
                    int yy = y + t / 3 - 1, xx = x + t % 3 - 1;
                    neighbours[(y * spec.width + x) * 9 + t] = yy >= 0 && yy < spec.height && xx >= 0 && xx < spec.width ? yy * spec.width + xx : -1;
                }
        scratch = ThreadLocal.withInitial(Scratch::new);
    }

    public Spec spec() {
        return spec;
    }

    public long parameters() {
        return parameters;
    }

    private static void check(float[] a, int n) {
        if (a.length != n) throw new IllegalArgumentException("weight array of " + a.length + " values, expected " + n);
    }

    /** [Cout][Cin][3][3] (PyTorch) -> [tap][Cin][Cout]. */
    private static float[] transpose3x3(float[] w, int cout, int cin) {
        check(w, cout * cin * 9);
        var t = new float[w.length];
        for (int co = 0; co < cout; co++)
            for (int ci = 0; ci < cin; ci++)
                for (int tap = 0; tap < 9; tap++) t[(tap * cin + ci) * cout + co] = w[(co * cin + ci) * 9 + tap];
        return t;
    }

    // ---- loading -------------------------------------------------------------------------------------------

    public static GnetNetwork load(Path file) throws IOException {
        try (var in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            return load(in);
        }
    }

    public static GnetNetwork load(InputStream raw) throws IOException {
        var in = new DataInputStream(raw);
        byte[] magic = in.readNBytes(4);
        if (!new String(magic, StandardCharsets.US_ASCII).equals("GNET")) throw new IOException("not a .gnet file");
        int version = readInt(in);
        if (version != 1) throw new IOException("unsupported .gnet version " + version);
        String game = readDotNetString(in);
        int[] v = new int[9];
        for (int k = 0; k < 9; k++) v[k] = readInt(in);
        var spec = new Spec(game, v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8]);
        int count = readInt(in);
        var weights = new ArrayList<float[]>(count);
        for (int k = 0; k < count; k++) {
            int n = readInt(in);
            byte[] bytes = in.readNBytes(n * 4);
            if (bytes.length != n * 4) throw new EOFException("truncated .gnet file");
            var a = new float[n];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(a);
            weights.add(a);
        }
        return new GnetNetwork(spec, weights);
    }

    private static int readInt(DataInputStream in) throws IOException {
        return Integer.reverseBytes(in.readInt());
    }

    /** BinaryWriter.Write(string): a 7-bit-encoded length, then UTF-8 bytes. */
    private static String readDotNetString(DataInputStream in) throws IOException {
        int len = 0, shift = 0, b;
        do {
            b = in.readUnsignedByte();
            len |= (b & 0x7f) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return new String(in.readNBytes(len), StandardCharsets.UTF_8);
    }

    // ---- inference ------------------------------------------------------------------------------------------

    /** 3x3 convolution with zero padding, HWC in and out, plus bias, optionally ReLU. */
    private void conv3x3(float[] in, int cin, float[] wt, float[] bias, float[] out, boolean relu) {
        int area = spec.area(), cout = bias.length;
        int vecEnd = cout - cout % L, blockEnd = cout - cout % (4 * L);
        for (int p = 0; p < area; p++) {
            int o = p * cout;
            // Four vectors of output channels at a time: four independent FMA chains hide the
            // instruction's latency (one accumulator would wait for each previous FMA).
            for (int cb = 0; cb < blockEnd; cb += 4 * L) {
                var a0 = FloatVector.fromArray(S, bias, cb);
                var a1 = FloatVector.fromArray(S, bias, cb + L);
                var a2 = FloatVector.fromArray(S, bias, cb + 2 * L);
                var a3 = FloatVector.fromArray(S, bias, cb + 3 * L);
                for (int t = 0; t < 9; t++) {
                    int nb = neighbours[p * 9 + t];
                    if (nb < 0) continue;
                    int x = nb * cin, wr = t * cin * cout + cb;
                    for (int ci = 0; ci < cin; ci++, wr += cout) {
                        var v = FloatVector.broadcast(S, in[x + ci]);
                        a0 = FloatVector.fromArray(S, wt, wr).fma(v, a0);
                        a1 = FloatVector.fromArray(S, wt, wr + L).fma(v, a1);
                        a2 = FloatVector.fromArray(S, wt, wr + 2 * L).fma(v, a2);
                        a3 = FloatVector.fromArray(S, wt, wr + 3 * L).fma(v, a3);
                    }
                }
                if (relu) {
                    a0 = a0.max(0f);
                    a1 = a1.max(0f);
                    a2 = a2.max(0f);
                    a3 = a3.max(0f);
                }
                a0.intoArray(out, o + cb);
                a1.intoArray(out, o + cb + L);
                a2.intoArray(out, o + cb + 2 * L);
                a3.intoArray(out, o + cb + 3 * L);
            }
            for (int cb = blockEnd; cb < vecEnd; cb += L) {
                var acc = FloatVector.fromArray(S, bias, cb);
                for (int t = 0; t < 9; t++) {
                    int nb = neighbours[p * 9 + t];
                    if (nb < 0) continue;
                    int x = nb * cin, wr = t * cin * cout + cb;
                    for (int ci = 0; ci < cin; ci++, wr += cout) acc = FloatVector.fromArray(S, wt, wr).fma(FloatVector.broadcast(S, in[x + ci]), acc);
                }
                if (relu) acc = acc.max(0f);
                acc.intoArray(out, o + cb);
            }
            for (int cb = vecEnd; cb < cout; cb++) {
                float acc = bias[cb];
                for (int t = 0; t < 9; t++) {
                    int nb = neighbours[p * 9 + t];
                    if (nb < 0) continue;
                    for (int ci = 0; ci < cin; ci++) acc = Math.fma(in[nb * cin + ci], wt[(t * cin + ci) * cout + cb], acc);
                }
                out[o + cb] = relu ? Math.max(acc, 0f) : acc;
            }
        }
    }

    private static float dot(float[] a, int ao, float[] b, int bo, int n) {
        var acc = FloatVector.zero(S);
        int i = 0;
        for (; i <= n - L; i += L) acc = FloatVector.fromArray(S, a, ao + i).fma(FloatVector.fromArray(S, b, bo + i), acc);
        float s = acc.reduceLanes(VectorOperators.ADD);
        for (; i < n; i++) s += a[ao + i] * b[bo + i];
        return s;
    }

    /** 1x1 convolution + bias + ReLU, from HWC activations to CHW (PyTorch's flatten order). */
    private void conv1x1(float[] in, float[] w, float[] b, float[] out) {
        int area = spec.area(), cin = spec.channels;
        for (int co = 0; co < b.length; co++)
            for (int p = 0; p < area; p++) out[co * area + p] = Math.max(0f, dot(in, p * cin, w, co * cin, cin) + b[co]);
    }

    /**
     * Evaluates one position given as CHW planes: writes the policy logits (one per action) and
     * returns the value in [-1, 1] for the side to move.
     */
    public float evaluate(float[] planes, int planesOffset, float[] logits, int logitsOffset) {
        var s = scratch.get();
        int area = spec.area(), c = spec.channels, pl = spec.planes;
        for (int k = 0; k < pl; k++)
            for (int p = 0; p < area; p++) s.input[p * pl + k] = planes[planesOffset + k * area + p];
        conv3x3(s.input, pl, stemW, stemB, s.a, true);
        for (int b = 0; b < spec.blocks; b++) {
            conv3x3(s.a, c, blockW1[b], blockB1[b], s.t, true);
            conv3x3(s.t, c, blockW2[b], blockB2[b], s.b, false);
            int n = c * area, i = 0;
            for (; i <= n - L; i += L)
                FloatVector.fromArray(S, s.a, i).add(FloatVector.fromArray(S, s.b, i)).max(0f).intoArray(s.a, i);
            for (; i < n; i++) s.a[i] = Math.max(0f, s.a[i] + s.b[i]);
        }
        int pin = spec.policyChannels * area;
        conv1x1(s.a, polConvW, polConvB, s.head);
        for (int o = 0; o < spec.actions; o++) logits[logitsOffset + o] = dot(polFcW, o * pin, s.head, 0, pin) + polFcB[o];
        int vin = spec.valueChannels * area;
        conv1x1(s.a, valConvW, valConvB, s.head);
        for (int o = 0; o < spec.valueHidden; o++) s.hidden[o] = Math.max(0f, dot(valFc1W, o * vin, s.head, 0, vin) + valFc1B[o]);
        return (float) Math.tanh(dot(valFc2W, 0, s.hidden, 0, spec.valueHidden) + valFc2B[0]);
    }

    /** Evaluates one game position. */
    public float evaluate(Game g, float[] logits) {
        if (!g.name().equals(spec.game)) throw new IllegalArgumentException("this network plays " + spec.game + ", not " + g.name());
        var planes = new float[spec.planes * spec.area()];
        g.encode(planes, 0);
        return evaluate(planes, 0, logits, 0);
    }

    /** Evaluates many positions in parallel; {@code logits} holds actions() floats per position. */
    public void evaluateBatch(List<? extends Game> games, float[] logits, float[] values) {
        int n = games.size(), a = spec.actions, size = spec.planes * spec.area();
        if (n == 1) {
            var planes = new float[size];
            games.getFirst().encode(planes, 0);
            values[0] = evaluate(planes, 0, logits, 0);
            return;
        }
        var planes = new float[n * size];
        for (int i = 0; i < n; i++) games.get(i).encode(planes, i * size);
        POOL.submit(() -> IntStream.range(0, n).parallel().forEach(i -> values[i] = evaluate(planes, i * size, logits, i * a))).join();
    }

    private static final ForkJoinPool POOL = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
}
