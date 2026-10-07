package nexus.ml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import nexus.ml.bert.BertEncoder;
import nexus.ml.text.UnigramTokenizer;
import org.junit.jupiter.api.Test;

/**
 * NEXUS's tokenizer and BERT encoder against the reference implementation (Hugging Face tokenizers +
 * transformers, see tools/reference/e5_reference.py) on texts chosen to be awkward: diacritics with
 * cedilla and comma, quotes, full-width letters, ligatures, emoji, CJK, URLs, runs of whitespace.
 * Skipped when the model is not downloaded (~/.nexus/models/multilingual-e5-small).
 */
class E5ReferenceTest {
    static final Path MODEL = Path.of(System.getProperty("user.home"), ".nexus", "models", "multilingual-e5-small");

    static JsonNode reference() throws Exception {
        try (var in = E5ReferenceTest.class.getResourceAsStream("/e5-reference.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    void tokenizer_matches_the_reference_exactly() throws Exception {
        assumeTrue(Files.exists(MODEL.resolve("tokenizer.json")), "model not downloaded");
        var tok = new UnigramTokenizer(MODEL.resolve("tokenizer.json"));
        for (JsonNode r : reference().get("records")) {
            int[] expected = new int[r.get("ids").size()];
            for (int i = 0; i < expected.length; i++) expected[i] = r.get("ids").get(i).asInt();
            assertArrayEquals(expected, tok.encode(r.get("text").asText(), 512), "tokens of: " + r.get("text").asText());
        }
    }

    @Test
    void embeddings_match_the_reference() throws Exception {
        assumeTrue(Files.exists(MODEL.resolve("model.safetensors")), "model not downloaded");
        try (var enc = new BertEncoder(MODEL)) {
            for (JsonNode r : reference().get("records")) {
                float[] e = enc.embed(r.get("text").asText());
                double dot = 0, maxDiff = 0;
                for (int i = 0; i < e.length; i++) {
                    double ref = r.get("embedding").get(i).asDouble();
                    dot += e[i] * ref;
                    maxDiff = Math.max(maxDiff, Math.abs(e[i] - ref));
                }
                assertTrue(dot > 0.99999, "cosine " + dot + " for: " + r.get("text").asText());
                assertTrue(maxDiff < 1e-4, "max |diff| " + maxDiff + " for: " + r.get("text").asText());
            }
        }
    }
}
