package nexus.ml.docs;

import java.util.List;

/**
 * A loaded document: its text page by page (page numbers are kept for citations; non-paginated
 * formats have a single page 1).
 */
public record Document(String id, String title, String source, List<Page> pages) {
    public record Page(int number, String text) {
    }

    public Document {
        pages = List.copyOf(pages);
    }

    public String text() {
        var sb = new StringBuilder();
        for (var p : pages) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(p.text());
        }
        return sb.toString();
    }

    public int characters() {
        return pages.stream().mapToInt(p -> p.text().length()).sum();
    }
}
