package nexus.ml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import nexus.ml.docs.Chunker;
import nexus.ml.docs.Document;
import nexus.ml.index.Bm25Index;
import nexus.ml.index.HnswIndex;
import nexus.ml.index.SearchIndex;
import org.junit.jupiter.api.Test;

class RetrievalTest {
    static float[] randomUnit(Random r, int d) {
        float[] v = new float[d];
        double n = 0;
        for (int i = 0; i < d; i++) {
            v[i] = (float) r.nextGaussian();
            n += v[i] * v[i];
        }
        for (int i = 0; i < d; i++) v[i] /= (float) Math.sqrt(n);
        return v;
    }

    /** Clustered data, like real embeddings (random uniform vectors are an unrealistically easy/hard case). */
    static float[][] clustered(Random r, int n, int d, int clusters) {
        float[][] centres = new float[clusters][];
        for (int c = 0; c < clusters; c++) centres[c] = randomUnit(r, d);
        float[][] out = new float[n][];
        for (int i = 0; i < n; i++) {
            float[] c = centres[r.nextInt(clusters)], noise = randomUnit(r, d), v = new float[d];
            double norm = 0;
            for (int j = 0; j < d; j++) {
                v[j] = c[j] + 0.6f * noise[j];
                norm += v[j] * v[j];
            }
            for (int j = 0; j < d; j++) v[j] /= (float) Math.sqrt(norm);
            out[i] = v;
        }
        return out;
    }

    @Test
    void hnswRecallMatchesBruteForce() throws Exception {
        var r = new Random(7);
        int n = 5000, d = 64, k = 10;
        var data = clustered(r, n, d, 50);
        var index = new HnswIndex(d);
        for (var v : data) index.add(v);
        int found = 0, total = 0;
        for (int q = 0; q < 200; q++) {
            float[] query = clustered(r, 1, d, 50)[0];
            var exact = new HashSet<Integer>();
            for (var h : index.bruteForce(query, k)) exact.add(h.id());
            for (var h : index.search(query, k, 100)) if (exact.contains(h.id())) found++;
            total += k;
        }
        double recall = (double) found / total;
        System.out.printf("HNSW recall@%d over %d vectors: %.4f%n", k, n, recall);
        assertTrue(recall >= 0.95, "recall " + recall);

        // A written and re-read index answers identically.
        var bytes = new ByteArrayOutputStream();
        index.write(new DataOutputStream(bytes));
        var copy = HnswIndex.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        float[] query = data[123];
        assertEquals(index.search(query, k, 64), copy.search(query, k, 64));
        assertEquals(123, copy.search(query, 1, 64).getFirst().id());
    }

    @Test
    void bm25PrefersRareMatchingTermsAndFoldsDiacritics() {
        var bm = new Bm25Index();
        bm.add("Ștefan cel Mare a domnit în Moldova între 1457 și 1504.");
        bm.add("Moldova este o regiune istorică; capitala ei a fost Suceava.");
        bm.add("Eroarea E1404 apare când cheia de licență a expirat.");
        bm.add("Moldova Moldova Moldova, un text care repetă un singur cuvânt.");
        assertEquals(0, bm.search("stefan", 3).getFirst().id());
        assertEquals(2, bm.search("ce înseamnă eroarea e1404?", 3).getFirst().id());
        assertEquals(1, bm.search("capitala Moldovei Suceava", 3).getFirst().id());
        assertTrue(bm.search("cuvânt inexistent xyz", 3).size() <= 1);
        // Romanian definite articles are suffixes: "bufferul" must find "buffer".
        var en = new Bm25Index();
        en.add("Training samples are kept in a replay buffer.");
        en.add("The exploration constant is 1.5.");
        assertEquals(0, en.search("cât de mare este bufferul?", 2).getFirst().id());
        assertEquals(1, en.search("constanta de explorare", 2).getFirst().id());
        // A word found in every passage is weak evidence, a rare one strong.
        var common = new Bm25Index();
        for (int i = 0; i < 5; i++) common.add("Gambit note " + i + (i == 3 ? " about puct" : ""));
        assertTrue(common.search("gambit", 5).getFirst().strength() < 0.2);
        assertEquals(1.0, common.search("puct", 5).getFirst().strength(), 1e-9);
    }

    @Test
    void chunkerKeepsSectionsSentencesAndOverlap() {
        String text = """
                # Installation

                Download the archive. Unpack it anywhere. Run the installer as administrator. Restart when asked.

                # Retry policy

                Requests are retried. The delay doubles. Errors are then shown. Logs keep details.
                """;
        var doc = new Document("d", "Manual", "manual.md", List.of(new Document.Page(1, text)));
        // Budget by words: small enough to force several passages per section.
        var chunks = new Chunker(9, 1, s -> s.split("\\s+").length).chunk(List.of(doc));
        for (var c : chunks) {
            assertTrue(c.heading().equals("Installation") || c.heading().equals("Retry policy"), c.heading());
            assertTrue(c.text().endsWith("."), "passages end on a sentence: " + c.text());
            assertTrue(!c.text().contains("#"));
        }
        // No passage mixes the two sections.
        assertTrue(chunks.stream().noneMatch(c -> c.text().contains("installer") && c.text().contains("retried")));
        // Consecutive passages of a section share a sentence.
        var retry = chunks.stream().filter(c -> c.heading().equals("Retry policy")).toList();
        assertTrue(retry.size() >= 2);
        for (int i = 0; i + 1 < retry.size(); i++) {
            String a = retry.get(i).text(), b = retry.get(i + 1).text();
            String lastOfA = a.substring(a.lastIndexOf(". ") + 1).strip();
            assertTrue(b.startsWith(lastOfA) && !b.equals(lastOfA), a + " | " + b);
        }
        // Every passage respects the budget, and no passage is just a repeat of the overlap.
        assertTrue(chunks.stream().allMatch(c -> c.text().split("\\s+").length <= 9));
        assertEquals(chunks.size(), chunks.stream().map(c -> c.text()).distinct().count());
        assertEquals("Manual - Retry policy: " + retry.get(0).text(), retry.get(0).embedText());
        assertEquals("Manual, p. 1", retry.get(0).citation());
    }

    @Test
    void chunkerFindsHeadingsInsidePdfStyleBlocks() {
        // PDF text: no blank lines between paragraphs; a heading line follows a finished sentence.
        var p1 = new Document.Page(1, "The search\nTrees are walked by PUCT. The constant is 1.5.\nBatching\nPositions are evaluated in batches of 256.");
        var p2 = new Document.Page(2, "Batches are gathered from 64 games.\nTraining\nThe buffer keeps 500,000 positions.");
        var chunks = new Chunker(200, 1, s -> s.split("\\s+").length).chunk(List.of(new Document("g", "Notes", "n.pdf", List.of(p1, p2))));
        assertEquals(List.of("The search", "Batching", "Batching", "Training"), chunks.stream().map(Chunker.Chunk::heading).toList());
        assertEquals(List.of(1, 1, 2, 2), chunks.stream().map(Chunker.Chunk::page).toList());
        assertEquals("Batches are gathered from 64 games.", chunks.get(2).text());
    }

    @Test
    void hybridSearchFusesBothRankings() {
        var docs = List.of(new Document("d", "Notes", "n.txt", List.of(new Document.Page(1, "Alpha one. \n\nBeta two. \n\nGamma three."))));
        var chunks = new Chunker(3, 0, s -> s.split("\\s+").length).chunk(docs);
        assertEquals(3, chunks.size());
        // Vectors chosen so that semantic search ranks chunk 2 first, while the words point at chunk 1.
        var hnsw = new HnswIndex(2);
        hnsw.add(new float[]{1, 0});
        hnsw.add(new float[]{0.6f, 0.8f});
        hnsw.add(new float[]{0, 1});
        var bm = new Bm25Index();
        for (var c : chunks) bm.add(c.text());
        var index = new SearchIndex(chunks, hnsw, bm, "test");
        float[] q = {0.1f, 0.995f};
        assertEquals("Gamma three.", index.search(q, "beta", 3, SearchIndex.Mode.SEMANTIC).getFirst().chunk().text());
        assertEquals("Beta two.", index.search(q, "beta", 3, SearchIndex.Mode.KEYWORD).getFirst().chunk().text());
        // Beta is 2nd semantically and 1st by keyword: it wins the fusion.
        var hybrid = index.search(q, "beta", 3, SearchIndex.Mode.HYBRID);
        assertEquals("Beta two.", hybrid.getFirst().chunk().text());
        assertEquals(2, hybrid.getFirst().semanticRank());
        assertEquals(1, hybrid.getFirst().keywordRank());
        assertTrue(SearchIndex.context(hybrid).startsWith("[1] (Notes, p. 1) Beta two."));
    }
}
