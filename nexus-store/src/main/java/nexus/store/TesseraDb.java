package nexus.store;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tessera - the crash-safe B+tree key-value store written in C (a sibling project) - called from
 * Java through the Foreign Function &amp; Memory API: no JNI glue, no wrapper library; the
 * {@code ts_*} functions of {@code tessera.dll} / {@code libtessera.so} are bound directly.
 *
 * <p>Every write is a transaction of its own (durable on return) unless grouped with
 * {@link #begin()} / {@link #commit()}. A handle must be used by one thread at a time, which the
 * synchronized methods guarantee.
 */
@SuppressWarnings("restricted")      // native access is the point of this class
public final class TesseraDb implements AutoCloseable {
    public enum Sync { FULL, NORMAL, OFF }

    public record Entry(byte[] key, byte[] value) {
        public String keyText() {
            return new String(key, StandardCharsets.UTF_8);
        }

        public String valueText() {
            return new String(value, StandardCharsets.UTF_8);
        }
    }

    public record Stats(long keys, long txnId, int pageSize, int pages, int freePages, int treeHeight, int walFrames, long cacheHits,
                        long cacheMisses, long checkpoints, long commits) {
    }

    private static final int TS_OK = 0, TS_NOTFOUND = 1, TS_DONE = 2;

    /** The bound functions of the native library (loaded once per process). */
    private static final class Native {
        final MethodHandle errstr, optionsDefault, open, close, begin, commit, rollback, put, get, delete, free, iterOpen, iterNext, iterKey,
                iterValue, iterClose, checkpoint, stats, check;
        final Path library;

        Native(Path lib) {
            library = lib;
            var linker = Linker.nativeLinker();
            var lookup = SymbolLookup.libraryLookup(lib, Arena.global());
            errstr = h(linker, lookup, "ts_errstr", FunctionDescriptor.of(ADDRESS, JAVA_INT));
            optionsDefault = h(linker, lookup, "ts_options_default", FunctionDescriptor.ofVoid(ADDRESS));
            open = h(linker, lookup, "ts_open", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            close = h(linker, lookup, "ts_close", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            begin = h(linker, lookup, "ts_begin", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            commit = h(linker, lookup, "ts_commit", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            rollback = h(linker, lookup, "ts_rollback", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            put = h(linker, lookup, "ts_put", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG));
            get = h(linker, lookup, "ts_get", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
            delete = h(linker, lookup, "ts_delete", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));
            free = h(linker, lookup, "ts_free", FunctionDescriptor.ofVoid(ADDRESS));
            iterOpen = h(linker, lookup, "ts_iter_open", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS));
            iterNext = h(linker, lookup, "ts_iter_next", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            iterKey = h(linker, lookup, "ts_iter_key", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            iterValue = h(linker, lookup, "ts_iter_value", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            iterClose = h(linker, lookup, "ts_iter_close", FunctionDescriptor.ofVoid(ADDRESS));
            checkpoint = h(linker, lookup, "ts_checkpoint", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            stats = h(linker, lookup, "ts_get_stats", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            check = h(linker, lookup, "ts_check", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        }

        static MethodHandle h(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor d) {
            return linker.downcallHandle(lookup.find(name).orElseThrow(() -> new IllegalStateException("tessera has no " + name)), d);
        }
    }

    private static volatile Native nativeLib;

    /**
     * Where the native library is: the {@code nexus.tessera.lib} system property, the
     * {@code NEXUS_TESSERA_LIB} environment variable, the build output of {@code native/tessera},
     * or next to the application.
     */
    public static Path findLibrary() {
        String prop = System.getProperty("nexus.tessera.lib", System.getenv("NEXUS_TESSERA_LIB"));
        if (prop != null && !prop.isBlank()) return Path.of(prop);
        String file = System.mapLibraryName("tessera");
        var candidates = new ArrayList<Path>();
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            candidates.add(dir.resolve("native/tessera/build/Release").resolve(file));
            candidates.add(dir.resolve("native/tessera/build").resolve(file));
            candidates.add(dir.resolve("lib").resolve(file));
        }
        for (var c : candidates) if (Files.isRegularFile(c)) return c;
        return null;
    }

    /** Whether the native library can be loaded (else NEXUS falls back to plain files). */
    public static boolean available() {
        try {
            lib();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static Native lib() {
        var n = nativeLib;
        if (n != null) return n;
        synchronized (TesseraDb.class) {
            if (nativeLib == null) {
                Path p = findLibrary();
                if (p == null) throw new IllegalStateException("tessera native library not found (build native/tessera with CMake)");
                nativeLib = new Native(p.toAbsolutePath());
            }
            return nativeLib;
        }
    }

    public static Path libraryPath() {
        return lib().library;
    }

    private final Native n;
    private MemorySegment db;
    private final Path path;

    private TesseraDb(Native n, MemorySegment db, Path path) {
        this.n = n;
        this.db = db;
        this.path = path;
    }

    public Path path() {
        return path;
    }

    private static IOException error(Native n, String what, int rc) {
        String msg;
        try {
            msg = ((MemorySegment) n.errstr.invokeExact(rc)).reinterpret(256).getString(0);
        } catch (Throwable t) {
            msg = "error " + rc;
        }
        return new IOException("tessera " + what + ": " + msg + " (" + rc + ")");
    }

    public static TesseraDb open(Path file, Sync sync) throws IOException {
        var n = lib();
        try (var a = Arena.ofConfined()) {
            // ts_options: size_t cache_pages; uint32 checkpoint_pages; int sync; int create; ts_vfs *vfs  (32 bytes)
            var opt = a.allocate(32, 8);
            n.optionsDefault.invokeExact(opt);
            opt.set(JAVA_INT, 12, sync.ordinal());
            var out = a.allocate(ADDRESS);
            Files.createDirectories(file.toAbsolutePath().getParent());
            int rc = (int) n.open.invokeExact(a.allocateFrom(file.toAbsolutePath().toString()), opt, out);
            if (rc != TS_OK) throw error(n, "open " + file, rc);
            return new TesseraDb(n, out.get(ADDRESS, 0), file);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    private void ensureOpen() {
        if (db == null) throw new IllegalStateException("the database is closed");
    }

    private void check(String what, int rc) throws IOException {
        if (rc < 0) throw error(n, what, rc);
    }

    public synchronized void put(byte[] key, byte[] value) throws IOException {
        ensureOpen();
        try (var a = Arena.ofConfined()) {
            check("put", (int) n.put.invokeExact(db, a.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, key), (long) key.length,
                                                 a.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, value), (long) value.length));
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    public void put(String key, String value) throws IOException {
        put(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
    }

    /** The value, or null if the key does not exist. */
    public synchronized byte[] get(byte[] key) throws IOException {
        ensureOpen();
        try (var a = Arena.ofConfined()) {
            var val = a.allocate(ADDRESS);
            var len = a.allocate(JAVA_LONG);
            int rc = (int) n.get.invokeExact(db, a.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, key), (long) key.length, val, len);
            if (rc == TS_NOTFOUND) return null;
            check("get", rc);
            var p = val.get(ADDRESS, 0);
            byte[] out = p.reinterpret(len.get(JAVA_LONG, 0)).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            n.free.invokeExact(p);
            return out;
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    public String get(String key) throws IOException {
        byte[] v = get(key.getBytes(StandardCharsets.UTF_8));
        return v == null ? null : new String(v, StandardCharsets.UTF_8);
    }

    /** Returns false if the key did not exist. */
    public synchronized boolean delete(byte[] key) throws IOException {
        ensureOpen();
        try (var a = Arena.ofConfined()) {
            int rc = (int) n.delete.invokeExact(db, a.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, key), (long) key.length);
            check("delete", rc);
            return rc != TS_NOTFOUND;
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    public boolean delete(String key) throws IOException {
        return delete(key.getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void begin() throws IOException {
        ensureOpen();
        try {
            check("begin", (int) n.begin.invokeExact(db));
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    public synchronized void commit() throws IOException {
        ensureOpen();
        try {
            check("commit", (int) n.commit.invokeExact(db));
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    public synchronized void rollback() throws IOException {
        ensureOpen();
        try {
            check("rollback", (int) n.rollback.invokeExact(db));
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    /** Entries with lo &lt;= key &lt; hi in key order (null bounds are open), at most {@code limit}. */
    public synchronized List<Entry> scan(byte[] lo, byte[] hi, int limit) throws IOException {
        ensureOpen();
        var out = new ArrayList<Entry>();
        try (var a = Arena.ofConfined()) {
            var itOut = a.allocate(ADDRESS);
            var loSeg = lo == null ? MemorySegment.NULL : a.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, lo);
            var hiSeg = hi == null ? MemorySegment.NULL : a.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, hi);
            check("scan", (int) n.iterOpen.invokeExact(db, loSeg, (long) (lo == null ? 0 : lo.length), hiSeg, (long) (hi == null ? 0 : hi.length),
                                                       itOut));
            var it = itOut.get(ADDRESS, 0);
            try {
                var len = a.allocate(JAVA_LONG);
                var rcSeg = a.allocate(JAVA_INT);
                while (out.size() < limit) {
                    int rc = (int) n.iterNext.invokeExact(it);
                    if (rc == TS_DONE) break;
                    check("scan", rc);
                    var k = ((MemorySegment) n.iterKey.invokeExact(it, len));
                    byte[] key = k.reinterpret(len.get(JAVA_LONG, 0)).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
                    var v = ((MemorySegment) n.iterValue.invokeExact(it, len, rcSeg));
                    if (v.equals(MemorySegment.NULL) && rcSeg.get(JAVA_INT, 0) < 0) check("scan value", rcSeg.get(JAVA_INT, 0));
                    byte[] value = v.equals(MemorySegment.NULL) ? new byte[0] : v.reinterpret(len.get(JAVA_LONG, 0)).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
                    out.add(new Entry(key, value));
                }
            } finally {
                n.iterClose.invokeExact(it);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
        return out;
    }

    /** Every entry whose key starts with {@code prefix}. */
    public List<Entry> scanPrefix(String prefix, int limit) throws IOException {
        byte[] lo = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] hi = lo.clone();
        // the smallest key greater than every key with this prefix
        int i = hi.length - 1;
        while (i >= 0 && hi[i] == (byte) 0xFF) i--;
        if (i < 0) return scan(lo, null, limit);
        hi = java.util.Arrays.copyOf(hi, i + 1);
        hi[i]++;
        return scan(lo, hi, limit);
    }

    public synchronized Stats stats() throws IOException {
        ensureOpen();
        try (var a = Arena.ofConfined()) {
            var s = a.allocate(72, 8);
            check("stats", (int) n.stats.invokeExact(db, s));
            return new Stats(s.get(JAVA_LONG, 0), s.get(JAVA_LONG, 8), s.get(JAVA_INT, 16), s.get(JAVA_INT, 20), s.get(JAVA_INT, 24),
                             s.get(JAVA_INT, 28), s.get(JAVA_INT, 32), s.get(JAVA_LONG, 40), s.get(JAVA_LONG, 48), s.get(JAVA_LONG, 56),
                             s.get(JAVA_LONG, 64));
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    /** Runs Tessera's integrity checker over the whole file; returns the number of problems found. */
    public synchronized long verify() throws IOException {
        ensureOpen();
        try (var a = Arena.ofConfined()) {
            var problems = a.allocate(JAVA_LONG);
            check("check", (int) n.check.invokeExact(db, MemorySegment.NULL, MemorySegment.NULL, problems));
            return problems.get(JAVA_LONG, 0);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    public synchronized void checkpoint() throws IOException {
        ensureOpen();
        try {
            check("checkpoint", (int) n.checkpoint.invokeExact(db));
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (db == null) return;
        var d = db;
        db = null;
        try {
            check("close", (int) n.close.invokeExact(d));
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }
}
