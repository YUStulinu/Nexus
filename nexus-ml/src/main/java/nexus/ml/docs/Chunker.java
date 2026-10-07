package nexus.ml.docs;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import nexus.core.util.TextUtil;

/**
 * Cuts documents into passages for retrieval, along their structure rather than at a fixed number
 * of characters:
 * <ul>
 *   <li>a passage is built from whole sentences until it reaches the token budget;</li>
 *   <li>it never crosses a section heading (a Markdown "#" line or a short title-like line), so a
 *       passage is about one topic and carries that heading;</li>
 *   <li>consecutive passages of a section overlap by a sentence or two, so an answer that straddles a
 *       boundary still lands whole in one passage;</li>
 *   <li>the text that gets embedded is prefixed with the document title and heading - a paragraph
 *       saying "it defaults to three retries" means little alone, and a lot under "Retry policy".</li>
 * </ul>
 */
public final class Chunker {
    /** A passage of a document. {@code text} is shown and cited; {@code embedText} is what gets embedded. */
    public record Chunk(int id, String documentTitle, String source, int page, String heading, String text) {
        public String embedText() {
            return documentTitle + (heading.isEmpty() ? "" : " - " + heading) + ": " + text;
        }

        public String citation() {
            return documentTitle + (page > 0 ? ", p. " + page : "");
        }
    }

    private static final Pattern MD_HEADING = Pattern.compile("^#{1,6}\\s+(.+)$");

    private final int maxTokens, overlapSentences;
    private final ToIntFunction<String> tokens;

    /**
     * @param maxTokens        budget per passage
     * @param overlapSentences sentences repeated at the start of the next passage of the same section
     * @param tokens           token counter (the embedding model's tokenizer)
     */
    public Chunker(int maxTokens, int overlapSentences, ToIntFunction<String> tokens) {
        this.maxTokens = maxTokens;
        this.overlapSentences = overlapSentences;
        this.tokens = tokens;
    }

    static boolean looksLikeHeading(String line) {
        String t = line.strip();
        if (t.isEmpty() || t.length() > 80) return false;
        if (MD_HEADING.matcher(t).matches()) return true;
        if (t.endsWith(".") || t.endsWith(",") || t.endsWith(";")) return false;
        int words = t.split("\\s+").length;
        boolean capitalised = Character.isUpperCase(t.codePointAt(0)) || Character.isDigit(t.codePointAt(0));
        return words <= 8 && capitalised && !t.contains(". ");
    }

    public List<Chunk> chunk(List<Document> docs) {
        var out = new ArrayList<Chunk>();
        for (var d : docs) {
            String heading = "";
            for (var page : d.pages()) {
                var section = new Section(d, page.number());
                section.heading = heading;                       // a section may continue on the next page
                for (var para : TextUtil.paragraphs(page.text())) {
                    var lines = para.lines().toList();
                    var block = new StringBuilder();
                    for (int i = 0; i < lines.size(); i++) {
                        if (isHeading(lines, i, para.length())) {
                            for (var s : TextUtil.sentences(block.toString())) section.add(out, s);
                            block.setLength(0);
                            section.flush(out, true);
                            var m = MD_HEADING.matcher(lines.get(i).strip());
                            section.heading = m.matches() ? m.group(1).strip() : lines.get(i).strip();
                        } else {
                            block.append(lines.get(i)).append('\n');
                        }
                    }
                    for (var s : TextUtil.sentences(block.toString())) section.add(out, s);
                }
                section.flush(out, true);
                heading = section.heading;
            }
        }
        return out;
    }

    /**
     * Whether line {@code i} of a paragraph is a section heading: a title-like line that opens the
     * paragraph, or - in PDFs, where paragraphs are rarely separated by blank lines - one that
     * follows a finished sentence and has text after it.
     */
    static boolean isHeading(List<String> lines, int i, int paragraphLength) {
        if (!looksLikeHeading(lines.get(i))) return false;
        if (i == 0) return lines.size() > 1 || paragraphLength < 80;
        String prev = lines.get(i - 1).strip();
        return i + 1 < lines.size() && !prev.isEmpty() && ".!?:".indexOf(prev.charAt(prev.length() - 1)) >= 0;
    }

    /** The passage being filled: its sentences, the first {@code carried} of which overlap the previous passage. */
    private final class Section {
        final Document doc;
        final int page;
        String heading = "";
        final List<String> sentences = new ArrayList<>();
        int carried;

        Section(Document doc, int page) {
            this.doc = doc;
            this.page = page;
        }

        boolean over() {
            return tokens.applyAsInt(String.join(" ", sentences)) > maxTokens;
        }

        void add(List<Chunk> out, String s) {
            sentences.add(s);
            while (over()) {
                if (sentences.size() - 1 > carried) {        // new material besides s: close the passage before s
                    sentences.removeLast();
                    flush(out, false);
                    sentences.add(s);
                } else if (carried > 0) {                    // only overlap besides s: give up the overlap
                    sentences.removeFirst();
                    carried--;
                } else {                                     // s alone is too long: cut it by words
                    sentences.removeLast();
                    out.addAll(splitLong(out.size(), doc, page, heading, s));
                    return;
                }
            }
        }

        /** Emits the passage; keeps the overlap unless the section ends. */
        void flush(List<Chunk> out, boolean sectionEnd) {
            if (sentences.size() > carried)
                out.add(new Chunk(out.size(), doc.title(), doc.source(), page, heading, String.join(" ", sentences)));
            var keep = sectionEnd ? List.<String>of() : List.copyOf(sentences.subList(Math.max(0, sentences.size() - overlapSentences), sentences.size()));
            sentences.clear();
            sentences.addAll(keep);
            carried = keep.size();
        }
    }

    private List<Chunk> splitLong(int firstId, Document d, int page, String heading, String sentence) {
        var out = new ArrayList<Chunk>();
        var words = sentence.split("\\s+");
        var cur = new StringBuilder();
        for (var w : words) {
            if (!cur.isEmpty() && tokens.applyAsInt(cur + " " + w) > maxTokens) {
                out.add(new Chunk(firstId + out.size(), d.title(), d.source(), page, heading, cur.toString()));
                cur.setLength(0);
            }
            if (!cur.isEmpty()) cur.append(' ');
            cur.append(w);
        }
        if (!cur.isEmpty()) out.add(new Chunk(firstId + out.size(), d.title(), d.source(), page, heading, cur.toString()));
        return out;
    }
}
