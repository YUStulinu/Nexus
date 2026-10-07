package nexus.ml;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import nexus.ml.bert.BertEncoder;
import nexus.ml.docs.Chunker;
import nexus.ml.docs.Document;
import nexus.ml.docs.DocumentLoader;
import nexus.ml.index.Bm25Index;
import nexus.ml.index.HnswIndex;
import nexus.ml.index.SearchIndex;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The whole retrieval pipeline on the example documents (a Romanian regulation in Markdown, an
 * English guide in Markdown and an English three-page PDF), with questions asked in Romanian - for
 * the English documents, a cross-lingual search. The right passage, with the right page, must be
 * among the first three: that is what the language model reads (the search node passes 4). Skipped
 * when the model is not downloaded.
 */
class RagEndToEndTest {
    static SearchIndex index;
    static BertEncoder encoder;

    static synchronized SearchIndex index() throws Exception {
        if (index != null) return index;
        encoder = new BertEncoder(E5ReferenceTest.MODEL);
        var docs = new ArrayList<Document>();
        for (var f : DocumentLoader.files(Path.of("..", "examples", "docs"))) docs.add(DocumentLoader.load(f));
        var chunks = new Chunker(220, 1, encoder.tokenizer()::count).chunk(docs);
        var vectors = encoder.embedAll(chunks.stream().map(c -> "passage: " + c.embedText()).toList(), null);
        var hnsw = new HnswIndex(encoder.dimension());
        var bm = new Bm25Index();
        for (int i = 0; i < chunks.size(); i++) {
            hnsw.add(vectors[i]);
            bm.add(chunks.get(i).heading() + " " + chunks.get(i).text());
        }
        return index = new SearchIndex(chunks, hnsw, bm, encoder.name());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Câte zile de concediu de odihnă am pe an?                  | Regulament intern, p. 1  | 24 de zile",
            "Ce fac dacă mi-am pierdut laptopul de serviciu?           | Regulament intern, p. 1  | incident de securitate",
            "Ce constantă de explorare folosește căutarea în Gambit?   | Gambit design notes, p. 2 | c_puct",
            "Cât de mare este bufferul de reluare la antrenare?        | Gambit design notes, p. 3 | 500,000",
            "Cum activez modul de gândire la Ember?                    | Ember user guide, p. 1   | enable_thinking",
            "De ce se oprește Ember cu codul 1 la pornire?             | Ember user guide, p. 1   | not enough GPU memory"})
    void romanianQuestionsFindTheRightPassage(String question, String citation, String mustContain) throws Exception {
        assumeTrue(Files.exists(E5ReferenceTest.MODEL.resolve("model.safetensors")), "model not downloaded");
        var idx = index();
        var results = idx.search(encoder.embed("query: " + question), question, 3, SearchIndex.Mode.HYBRID);
        int rank = 0;
        for (int i = 0; i < results.size() && rank == 0; i++) {
            var c = results.get(i).chunk();
            if (c.citation().equals(citation) && c.text().contains(mustContain)) rank = i + 1;
        }
        System.out.printf("rank %d  %s%n", rank, question.strip());
        assertTrue(rank >= 1, "not in the top 3: " + results.stream().map(r -> r.chunk().text()).toList());
    }
}
