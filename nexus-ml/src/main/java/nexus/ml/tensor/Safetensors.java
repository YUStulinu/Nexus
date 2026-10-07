package nexus.ml.tensor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A safetensors file, memory-mapped with the Foreign Function &amp; Memory API.
 *
 * Format: an 8-byte little-endian header length, a JSON header mapping tensor names to
 * {dtype, shape, data_offsets}, then the raw tensor data. Nothing is read eagerly: the file is
 * mapped once (the OS pages it in on demand), {@link #segment} returns a zero-copy view of one
 * tensor, and {@link #floats} copies a tensor to a heap array (converting F16 / BF16 to float).
 * Large tables such as a 250 000-word embedding matrix can stay mapped and be read row by row.
 */
public final class Safetensors implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort I16 = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    public record TensorInfo(String name, String dtype, long[] shape, long begin, long end) {
        public long elements() {
            long n = 1;
            for (long s : shape) n *= s;
            return n;
        }
    }

    private final Arena arena = Arena.ofShared();
    private final MemorySegment file;
    private final long dataStart;
    private final Map<String, TensorInfo> tensors = new LinkedHashMap<>();

    public Safetensors(Path path) throws IOException {
        try (var ch = FileChannel.open(path, StandardOpenOption.READ)) {
            file = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), arena);
        }
        long headerLen = file.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0);
        if (headerLen <= 0 || headerLen > 100_000_000) throw new IOException(path + " is not a safetensors file");
        byte[] header = file.asSlice(8, headerLen).toArray(ValueLayout.JAVA_BYTE);
        dataStart = 8 + headerLen;
        JsonNode root = JSON.readTree(new String(header, StandardCharsets.UTF_8));
        for (var e : root.properties()) {
            if (e.getKey().equals("__metadata__")) continue;
            var t = e.getValue();
            var shapeNode = t.get("shape");
            long[] shape = new long[shapeNode.size()];
            for (int i = 0; i < shape.length; i++) shape[i] = shapeNode.get(i).asLong();
            var off = t.get("data_offsets");
            tensors.put(e.getKey(), new TensorInfo(e.getKey(), t.get("dtype").asText(), shape, off.get(0).asLong(), off.get(1).asLong()));
        }
    }

    public Map<String, TensorInfo> tensors() {
        return Map.copyOf(tensors);
    }

    public TensorInfo info(String name) {
        var t = tensors.get(name);
        if (t == null) throw new IllegalArgumentException("no tensor '" + name + "'");
        return t;
    }

    public boolean has(String name) {
        return tensors.containsKey(name);
    }

    /** A zero-copy view of the tensor's bytes. */
    public MemorySegment segment(String name) {
        var t = info(name);
        return file.asSlice(dataStart + t.begin(), t.end() - t.begin());
    }

    /** The tensor as floats on the heap (F32, F16 and BF16 supported). */
    public float[] floats(String name) {
        var t = info(name);
        var seg = segment(name);
        int n = Math.toIntExact(t.elements());
        float[] out = new float[n];
        switch (t.dtype()) {
            case "F32" -> MemorySegment.copy(seg, F32, 0, out, 0, n);
            case "F16" -> {
                for (int i = 0; i < n; i++) out[i] = Float.float16ToFloat(seg.get(I16, 2L * i));
            }
            case "BF16" -> {
                for (int i = 0; i < n; i++) out[i] = Float.intBitsToFloat((seg.get(I16, 2L * i) & 0xffff) << 16);
            }
            default -> throw new IllegalArgumentException(name + ": unsupported dtype " + t.dtype());
        }
        return out;
    }

    /** One row of a 2-D F32 tensor, copied into {@code dst} (for tables that stay mapped). */
    public static void row(MemorySegment table, int cols, long row, float[] dst, int dstOffset) {
        MemorySegment.copy(table, F32, row * cols * 4L, dst, dstOffset, cols);
    }

    public List<String> names() {
        return List.copyOf(tensors.keySet());
    }

    @Override
    public void close() {
        arena.close();
    }
}
