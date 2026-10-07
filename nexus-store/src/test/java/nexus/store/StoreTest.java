package nexus.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StoreTest {
    @TempDir
    Path dir;

    KeyValueStore open(String kind) throws Exception {
        if (kind.equals("tessera")) {
            assumeTrue(TesseraDb.available(), "tessera native library not built");
            return new KeyValueStore.TesseraStore(TesseraDb.open(dir.resolve("t.tdb"), TesseraDb.Sync.NORMAL));
        }
        return new KeyValueStore.FileStore(dir.resolve("t.log"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"tessera", "file"})
    void putGetDeleteScanAndReopen(String kind) throws Exception {
        try (var s = open(kind)) {
            s.put("b", "2");
            s.put("a", "1");
            s.put("ab", "12");
            s.put("c", "3");
            s.put("ă", "unicode");
            assertEquals("1", s.get("a"));
            assertNull(s.get("zz"));
            assertEquals(List.of("a", "ab"), s.scan("a", 10).stream().map(e -> e.getKey()).toList());
            assertTrue(s.delete("b"));
            assertFalse(s.delete("b"));
            assertEquals("unicode", s.get("ă"));
        }
        try (var s = open(kind)) {
            assertNull(s.get("b"));
            assertEquals("12", s.get("ab"));
            assertEquals(4, s.scan("", 100).size());
        }
    }

    @Test
    void tesseraTransactionsStatsAndIntegrity() throws Exception {
        assumeTrue(TesseraDb.available());
        try (var db = TesseraDb.open(dir.resolve("x.tdb"), TesseraDb.Sync.NORMAL)) {
            db.begin();
            for (int i = 0; i < 2000; i++) db.put(String.format("key:%05d", i), "value " + i + " ".repeat(i % 50));
            db.commit();
            db.begin();
            db.put("key:00001", "changed");
            db.rollback();
            assertEquals("value 1 ", db.get("key:00001"));
            var big = new byte[200_000];
            for (int i = 0; i < big.length; i++) big[i] = (byte) i;
            db.put("blob".getBytes(), big);                          // overflow pages
            assertEquals(big.length, db.get("blob".getBytes()).length);
            var st = db.stats();
            assertEquals(2001, st.keys());
            assertTrue(st.treeHeight() >= 2, "a 2000-key tree has inner pages");
            assertEquals(0, db.verify(), "Tessera's checker finds no problem");
            assertEquals(10, db.scanPrefix("key:01", 10).size());
            long t0 = System.nanoTime();
            for (int i = 0; i < 20000; i++) db.get(String.format("key:%05d", i % 2000));
            System.out.printf("tessera via FFM: %.1f us per get%n", (System.nanoTime() - t0) / 1e3 / 20000);
        }
        assertTrue(Files.size(dir.resolve("x.tdb")) > 0);
    }

    @Test
    void versionsAreStoredOnlyWhenTheGraphChanges() throws Exception {
        try (var h = new WorkflowHistory(open(TesseraDb.available() ? "tessera" : "file"))) {
            String v1 = """
                    {"format":"nexus-workflow","version":1,"name":"demo","nodes":[
                      {"id":"n1","type":"text.input","x":0,"y":0,"params":{"text":"hello"}},
                      {"id":"n2","type":"text.stats","x":300,"y":0,"params":{}}],
                     "edges":[{"from":"n1.text","to":"n2.text"}],"view":{"x":0,"y":0,"zoom":1}}""";
            var a = h.save("Demo", v1, "first");
            assertEquals(1, a.number());
            String moved = v1.replace("\"x\":300", "\"x\":500").replace("\"zoom\":1", "\"zoom\":2");
            assertSame(a.number(), h.save("Demo", moved, "moved only").number(), "moving nodes is not a new version");
            String v2 = v1.replace("\"hello\"", "\"salut\"").replace(",\n  {\"id\":\"n2\"", ",\n  {\"id\":\"n2\"")
                          .replace("\"edges\":[{\"from\":\"n1.text\",\"to\":\"n2.text\"}]", "\"edges\":[]")
                          .replace("{\"id\":\"n2\",\"type\":\"text.stats\",\"x\":300,\"y\":0,\"params\":{}}",
                                   "{\"id\":\"n3\",\"type\":\"text.words\",\"x\":300,\"y\":0,\"params\":{\"top\":5}}");
            var b = h.save("Demo", v2, "second");
            assertEquals(2, b.number());
            assertEquals(2, h.versions("demo").size());
            var d = WorkflowHistory.diff(h.load("Demo", 1), h.load("Demo", 2));
            assertTrue(d.contains("+ node text.words (n3)"), d.toString());
            assertTrue(d.contains("- node text.stats (n2)"), d.toString());
            assertTrue(d.contains("~ text.input (n1): text \"hello\" -> \"salut\""), d.toString());
            assertTrue(d.contains("- wire n1.text -> n2.text"), d.toString());
            h.recordRun(new WorkflowHistory.RunRecord("Demo", java.time.Instant.now(), 12.5, 2, 0, 0, 0, java.util.Map.of("n1", 1.0)));
            assertEquals(1, h.runs("Demo").size());
            assertEquals(12.5, h.runs("Demo").getFirst().millis());
        }
    }
}
