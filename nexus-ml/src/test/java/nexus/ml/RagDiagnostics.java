package nexus.ml;

import java.util.List;
import nexus.ml.index.SearchIndex;
import org.junit.jupiter.api.Test;

/** Prints how each retrieval mode ranks the example passages (run with -Ddiag=true). */
class RagDiagnostics {
    @Test
    void print() throws Exception {
        if (!Boolean.getBoolean("diag")) return;
        var idx = RagEndToEndTest.index();
        var enc = RagEndToEndTest.encoder;
        for (var c : idx.chunks()) System.out.printf("#%d %s [%s] %s%n", c.id(), c.citation(), c.heading(), c.text().substring(0, Math.min(70, c.text().length())));
        for (var q : List.of("Ce constantă de explorare folosește căutarea în Gambit?", "Cât de mare este bufferul de reluare la antrenare?",
                             "Cum activez modul de gândire la Ember?")) {
            System.out.println("\nQ: " + q);
            float[] v = enc.embed("query: " + q);
            for (var m : SearchIndex.Mode.values()) {
                System.out.print("  " + m + ":");
                for (var r : idx.search(v, q, 4, m)) System.out.printf(" #%d(%.3f)", r.chunk().id(), m == SearchIndex.Mode.KEYWORD ? r.score() : r.similarity());
                System.out.println();
            }
        }
    }
}
