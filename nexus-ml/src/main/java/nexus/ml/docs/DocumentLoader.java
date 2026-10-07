package nexus.ml.docs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import nexus.core.util.TextUtil;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Reads documents from files or folders: PDF (page by page, with PDFBox), plain text, Markdown,
 * HTML. Text is cleaned of the artefacts of PDF extraction: words hyphenated across lines are
 * joined, single line breaks inside paragraphs become spaces, paragraph breaks are kept.
 */
public final class DocumentLoader {
    public static final Set<String> EXTENSIONS = Set.of("pdf", "txt", "md", "markdown", "html", "htm", "csv", "json");

    private DocumentLoader() {
    }

    /** Every supported file under {@code path} (or the file itself). */
    public static List<Path> files(Path path) throws IOException {
        if (Files.isRegularFile(path)) return List.of(path);
        if (!Files.isDirectory(path)) throw new IOException("not found: " + path);
        try (Stream<Path> s = Files.walk(path)) {
            return s.filter(Files::isRegularFile).filter(p -> EXTENSIONS.contains(extension(p))).sorted().toList();
        }
    }

    static String extension(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static Document load(Path file) throws IOException {
        String ext = extension(file);
        String name = file.getFileName().toString();
        String title = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        List<Document.Page> pages = new ArrayList<>();
        switch (ext) {
            case "pdf" -> {
                try (var pdf = Loader.loadPDF(file.toFile())) {
                    var stripper = new PDFTextStripper();
                    stripper.setSortByPosition(true);
                    int n = pdf.getNumberOfPages();
                    for (int i = 1; i <= n; i++) {
                        stripper.setStartPage(i);
                        stripper.setEndPage(i);
                        String t = clean(stripper.getText(pdf));
                        if (!t.isBlank()) pages.add(new Document.Page(i, t));
                    }
                    var info = pdf.getDocumentInformation();
                    if (info != null && info.getTitle() != null && !info.getTitle().isBlank()) title = info.getTitle().strip();
                }
            }
            case "html", "htm" -> pages.add(new Document.Page(1, TextUtil.htmlToText(Files.readString(file, StandardCharsets.UTF_8))));
            default -> pages.add(new Document.Page(1, Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n")));
        }
        return new Document(Integer.toHexString(file.toAbsolutePath().toString().hashCode()), title, file.toString(), pages);
    }

    /** Repairs PDF line structure: hyphenation, hard-wrapped lines, stray spaces. */
    static String clean(String raw) {
        String s = raw.replace("\r\n", "\n").replace((char) 0xA0, ' ');
        s = s.replaceAll("(\\p{L})-\\n(\\p{Ll})", "$1$2");          // hyphen at line end inside a word
        s = s.replaceAll("[ \\t]+\\n", "\n");
        s = s.replaceAll("(?<![.!?:;\\n])\\n(?!\\n)(?=\\p{Ll})", " "); // wrapped line continuing a sentence
        s = s.replaceAll("\\n{3,}", "\n\n");
        s = s.replaceAll("[ \\t]{2,}", " ");
        return s.strip();
    }
}
