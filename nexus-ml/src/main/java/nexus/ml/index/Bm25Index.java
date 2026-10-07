package nexus.ml.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import nexus.core.util.TextUtil;

/**
 * Okapi BM25 keyword search (k1 = 1.2, b = 0.75). Embeddings are good at meaning and bad at rare
 * literal tokens - an error code, a name, "art. 1350" - which is exactly what BM25 is good at; the
 * hybrid search fuses both.
 *
 * Terms are words folded to lower case without diacritics ("Ștefan" = "stefan"), then stemmed
 * lightly: Romanian attaches the definite article to the word ("buffer" / "bufferul", "concediu" /
 * "concediului"), so without it a Romanian question would miss most exact matches. The same
 * suffix stripping also makes many Latin-root words meet across languages ("explorare" and
 * "exploration" both become "explor").
 */
public final class Bm25Index {
    private static final double K1 = 1.2, B = 0.75;

    /** Longest first. Only stripped from words of 6+ letters, leaving at least 4. */
    private static final String[] SUFFIXES = {"urilor", "ations", "ation", "ilor", "elor", "ului", "area", "irea", "uri", "ele", "ile", "lor",
                                              "ing", "are", "ire", "ies", "ul", "ii", "ea", "ed", "es", "a", "e", "i", "s"};

    private final Map<String, List<int[]>> postings = new HashMap<>();   // term -> {doc, tf}
    private final List<Integer> lengths = new ArrayList<>();
    private long totalLength;

    public Bm25Index() {
    }

    static String stem(String folded) {
        if (folded.length() < 6) return folded;
        for (var s : SUFFIXES)
            if (folded.endsWith(s) && folded.length() - s.length() >= 4) return folded.substring(0, folded.length() - s.length());
        return folded;
    }

    static List<String> terms(String text, boolean dropStopWords) {
        var out = new ArrayList<String>();
        for (var w : TextUtil.words(text)) {
            String t = TextUtil.fold(w);
            if (t.isEmpty() || dropStopWords && TextUtil.STOP_WORDS.contains(t)) continue;
            out.add(stem(t));
        }
        return out;
    }

    public synchronized int add(String text) {
        int id = lengths.size();
        var ts = terms(text, false);
        var tf = new HashMap<String, Integer>();
        for (var t : ts) tf.merge(t, 1, Integer::sum);
        for (var e : tf.entrySet()) postings.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(new int[]{id, e.getValue()});
        lengths.add(ts.size());
        totalLength += ts.size();
        return id;
    }

    public synchronized int size() {
        return lengths.size();
    }

    /**
     * @param strength how much the match is worth as evidence, in (0, 1]: the summed rarity (idf) of
     *                 the query terms the passage contains, relative to the rarity of a term found in
     *                 a single passage. Matching only a word that is everywhere (the product's name
     *                 in its own manual) is weak evidence; matching a rare one is strong.
     */
    public record Hit(int id, double score, double strength) {
    }

    private double idf(int n, int df) {
        return Math.log(1 + (n - df + 0.5) / (df + 0.5));
    }

    public synchronized List<Hit> search(String query, int k) {
        int n = lengths.size();
        if (n == 0) return List.of();
        double avg = (double) totalLength / n, maxIdf = idf(n, 1);
        var scores = new HashMap<Integer, double[]>();   // doc -> {score, matched idf}
        for (var t : new LinkedHashSet<>(terms(query, true))) {
            var list = postings.get(t);
            if (list == null) continue;
            double idf = idf(n, list.size());
            for (var p : list) {
                double tf = p[1], len = lengths.get(p[0]);
                var a = scores.computeIfAbsent(p[0], x -> new double[2]);
                a[0] += idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * len / avg));
                a[1] += idf;
            }
        }
        return scores.entrySet().stream().sorted((x, y) -> Double.compare(y.getValue()[0], x.getValue()[0])).limit(k)
                     .map(e -> new Hit(e.getKey(), e.getValue()[0], Math.min(1, e.getValue()[1] / maxIdf))).toList();
    }
}
