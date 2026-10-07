package nexus.core.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Text helpers shared by the nodes: splitting, words, stop words (Romanian and English). */
public final class TextUtil {
    private TextUtil() {
    }

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:['’-][\\p{L}\\p{N}]+)*");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?…])[\"”»)]*\\s+(?=[\\p{Lu}\"„«(\\d])");

    /** Common Romanian and English function words, ignored by word statistics. */
    public static final Set<String> STOP_WORDS = Set.copyOf(java.util.Arrays.asList(
            // Romanian
            "și", "si", "în", "in", "la", "de", "pe", "cu", "a", "al", "ai", "ale", "un", "o", "unei", "unui", "este", "e",
            "sunt", "că", "ca", "care", "ce", "din", "mai", "nu", "se", "să", "sa", "lui", "lor", "fi", "fost", "pentru",
            "prin", "sau", "dar", "iar", "am", "au", "ai", "acest", "această", "aceasta", "acesta", "cel", "cea", "cei",
            "cele", "după", "dupa", "până", "pana", "fără", "fara", "despre", "între", "intre", "le", "li", "îi", "ii",
            "ne", "vă", "va", "mă", "ma", "te", "il", "îl", "o", "ea", "el", "ei", "ele", "noi", "voi", "eu", "tu",
            "dacă", "daca", "când", "cand", "cum", "unde", "atunci", "doar", "foarte", "fiind", "avea", "are", "avut",
            // English
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "is", "are", "was", "were", "be", "been",
            "it", "this", "that", "these", "those", "as", "at", "by", "from", "not", "but", "if", "then", "so", "than",
            "its", "it's", "has", "have", "had", "do", "does", "did", "will", "would", "can", "could", "i", "you", "he",
            "she", "we", "they", "them", "his", "her", "their", "our", "your", "my", "me", "us", "there", "which", "who",
            "what", "when", "where", "how", "all", "any", "some", "no", "more", "most", "such", "into", "about", "also"));

    public static List<String> words(String text) {
        var out = new ArrayList<String>();
        var m = WORD.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    public static List<String> sentences(String text) {
        var out = new ArrayList<String>();
        for (var para : paragraphs(text))
            for (var s : SENTENCE_END.split(para.replaceAll("\\s+", " ").trim())) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    public static List<String> paragraphs(String text) {
        var out = new ArrayList<String>();
        for (var p : text.split("\\R\\s*\\R")) if (!p.isBlank()) out.add(p.strip());
        return out;
    }

    public static List<String> lines(String text) {
        return text.lines().toList();
    }

    public static String titleCase(String s) {
        var sb = new StringBuilder(s.length());
        boolean start = true;
        for (char c : s.toCharArray()) {
            sb.append(start ? Character.toUpperCase(c) : Character.toLowerCase(c));
            start = !Character.isLetterOrDigit(c);
        }
        return sb.toString();
    }

    /** Lower case without diacritics (ș -> s, ă -> a, ...), for matching. */
    public static String fold(String s) {
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /** A short single-line preview. */
    public static String preview(String s, int max) {
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= max ? one : one.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** Rough HTML to text: drops scripts, styles and tags, decodes common entities, keeps paragraph breaks. */
    public static String htmlToText(String html) {
        String s = html.replaceAll("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?i)<\\s*(br|/p|/div|/li|/h[1-6]|/tr|/section|/article)[^>]*>", "\n");
        s = s.replaceAll("(?s)<[^>]+>", " ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        s = s.replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n");
        return s.strip();
    }
}
