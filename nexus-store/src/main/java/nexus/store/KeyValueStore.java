package nexus.store;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ordered string key-value storage. NEXUS keeps its history in Tessera when the native library is
 * available, and in an append-only text file otherwise (CI machines, a fresh checkout without the
 * C build) - same behaviour, fewer guarantees.
 */
public interface KeyValueStore extends AutoCloseable {
    void put(String key, String value) throws IOException;

    String get(String key) throws IOException;

    boolean delete(String key) throws IOException;

    /** Entries whose key starts with {@code prefix}, in key order. */
    List<Map.Entry<String, String>> scan(String prefix, int limit) throws IOException;

    /** "Tessera 1.0 (B+tree + WAL, C via FFM)" or "file". */
    String engine();

    @Override
    void close() throws IOException;

    /** Tessera if its native library loads, else a plain file next to where Tessera's file would be. */
    static KeyValueStore open(Path tesseraFile) throws IOException {
        if (TesseraDb.available()) return new TesseraStore(TesseraDb.open(tesseraFile, TesseraDb.Sync.NORMAL));
        return new FileStore(tesseraFile.resolveSibling(tesseraFile.getFileName() + ".log"));
    }

    /** Tessera behind the interface. */
    final class TesseraStore implements KeyValueStore {
        private final TesseraDb db;

        public TesseraStore(TesseraDb db) {
            this.db = db;
        }

        public TesseraDb db() {
            return db;
        }

        @Override
        public void put(String key, String value) throws IOException {
            db.put(key, value);
        }

        @Override
        public String get(String key) throws IOException {
            return db.get(key);
        }

        @Override
        public boolean delete(String key) throws IOException {
            return db.delete(key);
        }

        @Override
        public List<Map.Entry<String, String>> scan(String prefix, int limit) throws IOException {
            var out = new ArrayList<Map.Entry<String, String>>();
            for (var e : db.scanPrefix(prefix, limit)) out.add(Map.entry(e.keyText(), e.valueText()));
            return out;
        }

        @Override
        public String engine() {
            return "Tessera (C B+tree + write-ahead log, through FFM)";
        }

        @Override
        public void close() throws IOException {
            db.close();
        }
    }

    /** The fallback: a sorted map in memory, every change appended to a log file replayed on open. */
    final class FileStore implements KeyValueStore {
        private final TreeMap<String, String> map = new TreeMap<>();
        private final BufferedWriter log;

        public FileStore(Path file) throws IOException {
            Files.createDirectories(file.toAbsolutePath().getParent());
            if (Files.exists(file)) {
                var dec = Base64.getDecoder();
                for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    var f = line.split(" ", 3);
                    if (f.length < 2) continue;                      // a torn last line
                    String k = new String(dec.decode(f[1]), StandardCharsets.UTF_8);
                    if (f[0].equals("P") && f.length == 3) map.put(k, new String(dec.decode(f[2]), StandardCharsets.UTF_8));
                    else if (f[0].equals("D")) map.remove(k);
                }
            }
            log = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        private static String b64(String s) {
            return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public synchronized void put(String key, String value) throws IOException {
            map.put(key, value);
            log.write("P " + b64(key) + " " + b64(value) + "\n");
            log.flush();
        }

        @Override
        public synchronized String get(String key) {
            return map.get(key);
        }

        @Override
        public synchronized boolean delete(String key) throws IOException {
            boolean had = map.remove(key) != null;
            log.write("D " + b64(key) + "\n");
            log.flush();
            return had;
        }

        @Override
        public synchronized List<Map.Entry<String, String>> scan(String prefix, int limit) {
            var out = new ArrayList<Map.Entry<String, String>>();
            for (var e : map.tailMap(prefix, true).entrySet()) {
                if (!e.getKey().startsWith(prefix) || out.size() >= limit) break;
                out.add(Map.entry(e.getKey(), e.getValue()));
            }
            return out;
        }

        @Override
        public String engine() {
            return "append-only file (Tessera's native library was not found)";
        }

        @Override
        public synchronized void close() throws IOException {
            log.close();
        }
    }
}
