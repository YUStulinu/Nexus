package nexus.ml.bert;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Remembers embeddings on disk, keyed by the SHA-256 of the exact text that was embedded, one
 * append-only file per model. Re-indexing a folder where one file changed embeds only that file's
 * passages; re-running a workflow embeds nothing.
 *
 * File format: records of {32-byte hash, int dimension, dimension floats}. A torn last record (a
 * crash mid-append) is ignored on load.
 */
public final class EmbeddingCache {
    private final Path file;
    private final Map<String, float[]> map = new HashMap<>();
    private int hits, misses;

    public EmbeddingCache(Path file) throws IOException {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                byte[] h = new byte[32];
                while (true) {
                    try {
                        in.readFully(h);
                        int d = in.readInt();
                        if (d <= 0 || d > 65536) break;
                        float[] v = new float[d];
                        for (int i = 0; i < d; i++) v[i] = in.readFloat();
                        map.put(HexFormat.of().formatHex(h), v);
                    } catch (EOFException e) {
                        break;
                    }
                }
            }
        }
    }

    /** The cache of a model in the NEXUS home folder. */
    public static EmbeddingCache forModel(String model) throws IOException {
        return new EmbeddingCache(EmbeddingModels.home().resolve("cache").resolve(model + ".emb"));
    }

    static byte[] hash(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized int size() {
        return map.size();
    }

    public synchronized int hits() {
        return hits;
    }

    public synchronized int misses() {
        return misses;
    }

    /**
     * Embeddings of {@code texts}: cached ones are reused, the rest computed with {@code encoder}
     * (in parallel) and appended to the file.
     *
     * @param progress number of texts done so far (cached ones count immediately)
     */
    public float[][] embedAll(BertEncoder encoder, List<String> texts, IntConsumer progress) throws IOException, InterruptedException {
        float[][] out = new float[texts.size()][];
        var missingIdx = new ArrayList<Integer>();
        var missingText = new ArrayList<String>();
        var keys = new String[texts.size()];
        synchronized (this) {
            for (int i = 0; i < texts.size(); i++) {
                keys[i] = HexFormat.of().formatHex(hash(texts.get(i)));
                float[] v = map.get(keys[i]);
                if (v != null && v.length == encoder.dimension()) {
                    out[i] = v;
                    hits++;
                } else {
                    missingIdx.add(i);
                    missingText.add(texts.get(i));
                }
            }
            misses += missingIdx.size();
        }
        int cached = texts.size() - missingIdx.size();
        if (progress != null) progress.accept(cached);
        if (missingIdx.isEmpty()) return out;
        float[][] fresh = encoder.embedAll(missingText, n -> {
            if (progress != null) progress.accept(cached + n);
        });
        synchronized (this) {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (var o = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)))) {
                for (int j = 0; j < fresh.length; j++) {
                    int i = missingIdx.get(j);
                    out[i] = fresh[j];
                    if (map.putIfAbsent(keys[i], fresh[j]) != null) continue;
                    o.write(HexFormat.of().parseHex(keys[i]));
                    o.writeInt(fresh[j].length);
                    for (float f : fresh[j]) o.writeFloat(f);
                }
            }
        }
        return out;
    }
}
