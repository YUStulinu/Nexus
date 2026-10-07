package nexus.ml.bert;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntConsumer;
import nexus.ml.tensor.Kernels;
import nexus.ml.tensor.Safetensors;
import nexus.ml.text.UnigramTokenizer;

/**
 * A BERT sentence encoder (multilingual-e5-small, or any BertModel with a Unigram tokenizer) in plain
 * Java.
 *
 * <pre>
 * tokens -> word + position + token-type embeddings -> LayerNorm
 *        -> 12 x [ self-attention (12 heads) -> + residual -> LayerNorm
 *                  -> dense 1536 -> GELU -> dense 384 -> + residual -> LayerNorm ]
 *        -> mean over tokens -> L2 normalisation  = the sentence embedding
 * </pre>
 * Weights come from model.safetensors, memory-mapped: the 250 000 x 384 word-embedding table stays
 * in the mapped file (only the rows of the tokens in use are read); the transformer weights (about
 * 85 MB) are copied to heap arrays for the Vector API kernels. Texts of a batch are encoded in
 * parallel, one per core.
 */
public final class BertEncoder implements AutoCloseable {
    private final Safetensors st;
    private final UnigramTokenizer tokenizer;
    private final int hidden, heads, layers, inter, maxPositions;
    private final float eps;
    private final MemorySegment wordEmbeddings;
    private final float[] posEmb, typeEmb, embLnG, embLnB;
    private final Layer[] blocks;
    private final ExecutorService pool;
    private final String name;

    private record Layer(float[] wq, float[] bq, float[] wk, float[] bk, float[] wv, float[] bv, float[] wo, float[] bo, float[] ln1g,
                         float[] ln1b, float[] wi, float[] bi, float[] wo2, float[] bo2, float[] ln2g, float[] ln2b) {
    }

    public BertEncoder(Path dir) throws IOException {
        name = dir.getFileName().toString();
        var cfg = new ObjectMapper().readTree(Files.readString(dir.resolve("config.json")));
        hidden = cfg.get("hidden_size").asInt();
        heads = cfg.get("num_attention_heads").asInt();
        layers = cfg.get("num_hidden_layers").asInt();
        inter = cfg.get("intermediate_size").asInt();
        maxPositions = cfg.get("max_position_embeddings").asInt();
        eps = (float) cfg.path("layer_norm_eps").asDouble(1e-12);
        tokenizer = new UnigramTokenizer(dir.resolve("tokenizer.json"));
        st = new Safetensors(dir.resolve("model.safetensors"));
        String p = st.has("bert.embeddings.word_embeddings.weight") ? "bert." : "";
        wordEmbeddings = st.segment(p + "embeddings.word_embeddings.weight");
        posEmb = st.floats(p + "embeddings.position_embeddings.weight");
        typeEmb = st.floats(p + "embeddings.token_type_embeddings.weight");
        embLnG = st.floats(p + "embeddings.LayerNorm.weight");
        embLnB = st.floats(p + "embeddings.LayerNorm.bias");
        blocks = new Layer[layers];
        for (int l = 0; l < layers; l++) {
            String b = p + "encoder.layer." + l + ".";
            blocks[l] = new Layer(st.floats(b + "attention.self.query.weight"), st.floats(b + "attention.self.query.bias"),
                                  st.floats(b + "attention.self.key.weight"), st.floats(b + "attention.self.key.bias"),
                                  st.floats(b + "attention.self.value.weight"), st.floats(b + "attention.self.value.bias"),
                                  st.floats(b + "attention.output.dense.weight"), st.floats(b + "attention.output.dense.bias"),
                                  st.floats(b + "attention.output.LayerNorm.weight"), st.floats(b + "attention.output.LayerNorm.bias"),
                                  st.floats(b + "intermediate.dense.weight"), st.floats(b + "intermediate.dense.bias"),
                                  st.floats(b + "output.dense.weight"), st.floats(b + "output.dense.bias"),
                                  st.floats(b + "output.LayerNorm.weight"), st.floats(b + "output.LayerNorm.bias"));
        }
        pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), r -> {
            var t = new Thread(r, "bert-encoder");
            t.setDaemon(true);
            return t;
        });
    }

    public String name() {
        return name;
    }

    public int dimension() {
        return hidden;
    }

    public UnigramTokenizer tokenizer() {
        return tokenizer;
    }

    public int maxTokens() {
        return maxPositions;
    }

    /** The last hidden state, tokens x hidden, for one sequence of token ids. */
    public float[] hiddenStates(int[] ids) {
        int t = ids.length, d = hidden;
        float[] x = new float[t * d];
        float[] row = new float[d];
        for (int i = 0; i < t; i++) {
            Safetensors.row(wordEmbeddings, d, ids[i], row, 0);
            for (int j = 0; j < d; j++) x[i * d + j] = row[j] + posEmb[i * d + j] + typeEmb[j];
        }
        Kernels.layerNorm(x, t, d, embLnG, embLnB, eps);
        float[] q = new float[t * d], k = new float[t * d], v = new float[t * d], ctx = new float[t * d], tmp = new float[t * d];
        float[] ff = new float[t * inter];
        int hd = d / heads;
        float scale = (float) (1 / Math.sqrt(hd));
        // Per-head buffers: Q_h and K_h as [t x hd], V_h transposed to [hd x t], scores [t x t].
        float[] qh = new float[t * hd], kh = new float[t * hd], vhT = new float[hd * t], att = new float[t * t], ch = new float[t * hd];
        for (var L : blocks) {
            Kernels.linear(x, t, d, L.wq(), d, L.bq(), q);
            Kernels.linear(x, t, d, L.wk(), d, L.bk(), k);
            Kernels.linear(x, t, d, L.wv(), d, L.bv(), v);
            // Attention as two matrix products per head (no mask: one unpadded sequence):
            //   S = Q_h K_h^T * scale,  P = softmax(S),  C_h = P V_h
            for (int h = 0; h < heads; h++) {
                int ho = h * hd;
                for (int i = 0; i < t; i++) {
                    for (int c = 0; c < hd; c++) {
                        qh[i * hd + c] = q[i * d + ho + c] * scale;
                        kh[i * hd + c] = k[i * d + ho + c];
                        vhT[c * t + i] = v[i * d + ho + c];
                    }
                }
                Kernels.linear(qh, t, hd, kh, t, null, att);
                for (int i = 0; i < t; i++) Kernels.softmax(att, i * t, t);
                Kernels.linear(att, t, t, vhT, hd, null, ch);
                for (int i = 0; i < t; i++) System.arraycopy(ch, i * hd, ctx, i * d + ho, hd);
            }
            Kernels.linear(ctx, t, d, L.wo(), d, L.bo(), tmp);
            Kernels.addInPlace(x, tmp, t * d);
            Kernels.layerNorm(x, t, d, L.ln1g(), L.ln1b(), eps);
            Kernels.linear(x, t, d, L.wi(), inter, L.bi(), ff);
            Kernels.gelu(ff, t * inter);
            Kernels.linear(ff, t, inter, L.wo2(), d, L.bo2(), tmp);
            Kernels.addInPlace(x, tmp, t * d);
            Kernels.layerNorm(x, t, d, L.ln2g(), L.ln2b(), eps);
        }
        return x;
    }

    /** Mean-pooled, L2-normalised embedding of one text. e5 expects "query: " / "passage: " prefixes. */
    public float[] embed(String text) {
        int[] ids = tokenizer.encode(text, maxPositions);
        float[] h = hiddenStates(ids);
        int t = ids.length, d = hidden;
        float[] e = new float[d];
        for (int i = 0; i < t; i++) for (int j = 0; j < d; j++) e[j] += h[i * d + j];
        double norm = 0;
        for (int j = 0; j < d; j++) {
            e[j] /= t;
            norm += e[j] * e[j];
        }
        float inv = (float) (1 / Math.max(Math.sqrt(norm), 1e-12));
        for (int j = 0; j < d; j++) e[j] *= inv;
        return e;
    }

    /** Embeds many texts on all cores; {@code progress} receives the number done so far. */
    public float[][] embedAll(List<String> texts, IntConsumer progress) throws InterruptedException {
        float[][] out = new float[texts.size()][];
        var futures = new Future<?>[texts.size()];
        var done = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < texts.size(); i++) {
            final int idx = i;
            futures[i] = pool.submit(() -> {
                out[idx] = embed(texts.get(idx));
                int n = done.incrementAndGet();
                if (progress != null) progress.accept(n);
            });
        }
        try {
            for (var f : futures) f.get();
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            for (var f : futures) f.cancel(true);
            throw e;
        }
        return out;
    }

    @Override
    public void close() {
        pool.shutdownNow();
        st.close();
    }
}
