package nexus.ml.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import nexus.ml.docs.Chunker.Chunk;

/**
 * Passages searchable by meaning (HNSW over embeddings) and by words (BM25), fused with
 * reciprocal rank fusion: each list contributes 1 / (60 + rank) to a passage's score, so a passage
 * ranked well by either method rises, and one ranked well by both rises most - without having to
 * calibrate a cosine against a BM25 score. The keyword vote is scaled by the strength of the match
 * (see {@link Bm25Index.Hit#strength}): with few documents, a passage that merely shares a common
 * word with the question would otherwise get as large a vote as one that shares a rare one.
 */
public final class SearchIndex {
    public enum Mode { HYBRID, SEMANTIC, KEYWORD }

    public static final int RRF_K = 60;

    private final List<Chunk> chunks;
    private final HnswIndex vectors;
    private final Bm25Index keywords;
    private final String model;

    public SearchIndex(List<Chunk> chunks, HnswIndex vectors, Bm25Index keywords, String model) {
        if (chunks.size() != vectors.size() || chunks.size() != keywords.size())
            throw new IllegalArgumentException("chunks, vectors and keyword index differ in size");
        this.chunks = List.copyOf(chunks);
        this.vectors = vectors;
        this.keywords = keywords;
        this.model = model;
    }

    public List<Chunk> chunks() {
        return chunks;
    }

    public String model() {
        return model;
    }

    public int size() {
        return chunks.size();
    }

    /**
     * @param semanticRank rank in the vector search (1-based, 0 if absent)
     * @param keywordRank  rank in BM25 (1-based, 0 if absent)
     */
    public record Result(Chunk chunk, double score, int semanticRank, int keywordRank, float similarity) {
    }

    public List<Result> search(float[] queryVector, String queryText, int k, Mode mode) {
        int pool = Math.max(k * 4, 20);
        var fused = new HashMap<Integer, double[]>();   // id -> {score, semRank, kwRank, similarity}
        if (mode != Mode.KEYWORD) {
            var hits = vectors.search(queryVector, pool, Math.max(64, pool * 2));
            for (int r = 0; r < hits.size(); r++) {
                var a = fused.computeIfAbsent(hits.get(r).id(), x -> new double[4]);
                a[0] += 1.0 / (RRF_K + r + 1);
                a[1] = r + 1;
                a[3] = hits.get(r).similarity();
            }
        }
        if (mode != Mode.SEMANTIC) {
            var hits = keywords.search(queryText, pool);
            for (int r = 0; r < hits.size(); r++) {
                var a = fused.computeIfAbsent(hits.get(r).id(), x -> new double[4]);
                a[0] += hits.get(r).strength() / (RRF_K + r + 1);
                a[2] = r + 1;
            }
        }
        var out = new ArrayList<Result>();
        fused.entrySet().stream().sorted((x, y) -> Double.compare(y.getValue()[0], x.getValue()[0])).limit(k)
             .forEach(e -> out.add(new Result(chunks.get(e.getKey()), e.getValue()[0], (int) e.getValue()[1], (int) e.getValue()[2],
                                              (float) e.getValue()[3])));
        return out;
    }

    /** Numbered passages for a language model to answer from (and cite as [n]). */
    public static String context(List<Result> results) {
        var sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            var c = results.get(i).chunk();
            sb.append('[').append(i + 1).append("] (").append(c.citation()).append(") ").append(c.text()).append("\n\n");
        }
        return sb.toString().strip();
    }
}
