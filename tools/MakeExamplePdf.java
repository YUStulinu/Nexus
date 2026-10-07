import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Writes examples/docs/Gambit design notes.pdf, a three-page PDF used by the "Ask your documents"
 * example (so that page numbers in citations are exercised). Run with the PDFBox jars on the class
 * path: java -cp "pdfbox.jar;pdfbox-io.jar;fontbox.jar;commons-logging.jar" tools/MakeExamplePdf.java
 */
public class MakeExamplePdf {
    static final String[][] PAGES = {
            {"Gambit design notes",
             "Gambit is a reimplementation of AlphaZero in C#. A single neural network looks at a board position and returns two things: a probability for every legal move, called the policy, and an estimate of who is going to win, called the value. The network never sees a human game; everything it knows comes from games it plays against itself.",
             "Self-play",
             "Each move of a self-play game is chosen after 400 simulations of Monte Carlo tree search. During the first 30 moves the move is sampled in proportion to the visit counts, which keeps the openings varied; after that the most visited move is always played. Dirichlet noise with alpha 0.3 is mixed into the root policy so that the search keeps trying moves the network considers unlikely."},
            {"The search",
             "Monte Carlo tree search walks down the tree choosing, at every node, the child with the highest PUCT score: the average value of the child plus an exploration bonus proportional to its prior probability and inversely proportional to its visit count. The exploration constant c_puct is 1.5. When the walk reaches a position that has not been expanded, the network evaluates it once and the value is propagated back up the path, with the sign flipped at every level because the players alternate.",
             "Batching",
             "Evaluating positions one by one leaves the GPU idle most of the time. Gambit runs 64 games in parallel and gathers the positions they need into batches of up to 256, so a single network call serves many searches. A virtual loss of 3 is applied to the nodes on a path while its evaluation is pending, so parallel walks spread out instead of all descending the same branch."},
            {"Training",
             "Training samples are kept in a replay buffer of the last 500,000 positions. The loss is the squared error of the value plus the cross-entropy between the policy and the search visit distribution, with an L2 weight penalty of 0.0001. The learning rate starts at 0.02 and is divided by ten after 100,000 steps.",
             "Evaluation",
             "Every 1,000 training steps the new network plays 100 games against the best network so far. It replaces the best network if it wins at least 55 percent of the points. Strength is reported as an Elo rating relative to the first network; a gain of 100 Elo means the new network is expected to score about 64 percent against the old one.",
             "File format",
             "Networks are saved as .gnet files: a small JSON header describing the layers, followed by the weights as little-endian 32-bit floats in the order the header lists them."}};

    public static void main(String[] args) throws Exception {
        var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        var bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        try (var doc = new PDDocument()) {
            doc.getDocumentInformation().setTitle("Gambit design notes");
            for (var blocks : PAGES) {
                var page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (var cs = new PDPageContentStream(doc, page)) {
                    float y = 780, left = 60, width = 475;
                    for (int i = 0; i < blocks.length; i++) {
                        boolean heading = blocks[i].length() < 40;
                        var f = heading ? bold : font;
                        float size = heading ? 14 : 11;
                        if (heading && y < 780) y -= 10;
                        for (var line : wrap(blocks[i], f, size, width)) {
                            cs.beginText();
                            cs.setFont(f, size);
                            cs.newLineAtOffset(left, y);
                            cs.showText(line);
                            cs.endText();
                            y -= size * 1.45f;
                        }
                        y -= 8;
                    }
                }
            }
            doc.save(new File("examples/docs/Gambit design notes.pdf"));
        }
    }

    static List<String> wrap(String text, PDType1Font f, float size, float width) throws Exception {
        var lines = new ArrayList<String>();
        var cur = new StringBuilder();
        for (var w : text.split(" ")) {
            String trial = cur.isEmpty() ? w : cur + " " + w;
            if (f.getStringWidth(trial) / 1000 * size > width && !cur.isEmpty()) {
                lines.add(cur.toString());
                cur = new StringBuilder(w);
            } else {
                cur = new StringBuilder(trial);
            }
        }
        if (!cur.isEmpty()) lines.add(cur.toString());
        return lines;
    }
}
