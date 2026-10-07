package nexus.ml.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The XLM-RoBERTa / multilingual-e5 tokenizer, read from a Hugging Face tokenizer.json:
 * <ol>
 *   <li>normalise: the precompiled SentencePiece character map, then runs of spaces to one space;</li>
 *   <li>pre-tokenise (Metaspace): spaces become "▁", a "▁" is prepended, and the text is split
 *       before every "▁";</li>
 *   <li>segment each piece with the Unigram model: the Viterbi path through the lattice of
 *       vocabulary pieces maximising the sum of their log-probabilities; characters no piece covers
 *       become &lt;unk&gt; (consecutive unknowns fused into one);</li>
 *   <li>add &lt;s&gt; ... &lt;/s&gt; and truncate to the model's maximum length.</li>
 * </ol>
 */
public final class UnigramTokenizer {
    private static final double UNK_PENALTY = 10.0;
    private static final char META = '▁';

    private final Map<String, Integer> ids = new HashMap<>();
    private final String[] pieces;
    private final double[] scores;
    private final int unkId, bosId, eosId, padId;
    private final double unkScore;
    private final int maxPieceLength;
    private final PrecompiledCharsmap charsmap;

    public UnigramTokenizer(Path tokenizerJson) throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(tokenizerJson));
        JsonNode model = root.get("model");
        if (!"Unigram".equals(model.path("type").asText())) throw new IOException("not a Unigram tokenizer");
        JsonNode vocab = model.get("vocab");
        pieces = new String[vocab.size()];
        scores = new double[vocab.size()];
        double min = Double.MAX_VALUE;
        int maxLen = 1;
        for (int i = 0; i < vocab.size(); i++) {
            pieces[i] = vocab.get(i).get(0).asText();
            scores[i] = vocab.get(i).get(1).asDouble();
            ids.putIfAbsent(pieces[i], i);
            min = Math.min(min, scores[i]);
            maxLen = Math.max(maxLen, pieces[i].length());
        }
        unkId = model.path("unk_id").asInt(3);
        unkScore = min - UNK_PENALTY;
        maxPieceLength = maxLen;
        bosId = ids.getOrDefault("<s>", 0);
        eosId = ids.getOrDefault("</s>", 2);
        padId = ids.getOrDefault("<pad>", 1);
        PrecompiledCharsmap cm = null;
        JsonNode norm = root.get("normalizer");
        if (norm != null && !norm.isNull()) {
            var list = new ArrayList<JsonNode>();
            if ("Sequence".equals(norm.path("type").asText())) norm.get("normalizers").forEach(list::add);
            else list.add(norm);
            for (JsonNode n : list)
                if ("Precompiled".equals(n.path("type").asText())) cm = new PrecompiledCharsmap(n.get("precompiled_charsmap").asText());
        }
        charsmap = cm;
    }

    public int vocabSize() {
        return pieces.length;
    }

    public int padId() {
        return padId;
    }

    public String piece(int id) {
        return id >= 0 && id < pieces.length ? pieces[id] : "<?>";
    }

    /** Normalisation and pre-tokenisation: the pieces the Unigram model segments. */
    List<String> preTokenize(String text) {
        String s = charsmap != null ? charsmap.normalize(text) : text;
        s = s.replaceAll(" {2,}", " ");
        s = s.replace(' ', META);
        if (s.isEmpty()) return List.of();
        if (s.charAt(0) != META) s = META + s;
        var out = new ArrayList<String>();
        int start = 0;
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) == META) {
                out.add(s.substring(start, i));
                start = i;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    /** Viterbi segmentation of one pre-token into vocabulary ids. */
    void segment(String s, List<Integer> out) {
        int n = s.length();
        double[] best = new double[n + 1];
        int[] backLen = new int[n + 1];
        int[] backId = new int[n + 1];
        java.util.Arrays.fill(best, Double.NEGATIVE_INFINITY);
        best[0] = 0;
        for (int i = 0; i < n; i++) {
            if (best[i] == Double.NEGATIVE_INFINITY) continue;
            boolean singleCharFound = false;
            int charLen = Character.charCount(s.codePointAt(i));
            for (int len = 1; len <= maxPieceLength && i + len <= n; len++) {
                Integer id = ids.get(s.substring(i, i + len));
                if (id == null) continue;
                if (len == charLen) singleCharFound = true;
                double sc = best[i] + scores[id];
                if (sc > best[i + len]) {
                    best[i + len] = sc;
                    backLen[i + len] = len;
                    backId[i + len] = id;
                }
            }
            if (!singleCharFound) {
                double sc = best[i] + unkScore;
                if (sc > best[i + charLen]) {
                    best[i + charLen] = sc;
                    backLen[i + charLen] = charLen;
                    backId[i + charLen] = unkId;
                }
            }
        }
        var rev = new ArrayList<Integer>();
        for (int pos = n; pos > 0; pos -= backLen[pos]) rev.add(backId[pos]);
        int prev = -1;
        for (int k = rev.size() - 1; k >= 0; k--) {
            int id = rev.get(k);
            if (id == unkId && prev == unkId) continue;   // fuse consecutive unknowns
            out.add(id);
            prev = id;
        }
    }

    /** Token ids with &lt;s&gt; and &lt;/s&gt;, at most {@code maxLength} in total. */
    public int[] encode(String text, int maxLength) {
        var ids = new ArrayList<Integer>();
        ids.add(bosId);
        for (var p : preTokenize(text)) segment(p, ids);
        if (ids.size() > maxLength - 1) ids.subList(maxLength - 1, ids.size()).clear();
        ids.add(eosId);
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The number of tokens a text would take (without the special tokens). */
    public int count(String text) {
        var ids = new ArrayList<Integer>();
        for (var p : preTokenize(text)) segment(p, ids);
        return ids.size();
    }
}
