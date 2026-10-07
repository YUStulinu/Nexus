package nexus.engines;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import nexus.engines.system.VramBroker;

/**
 * One supervised engine process.
 *
 * <ul>
 *   <li><b>Start.</b> If something healthy already answers on the port, the engine is ATTACHED to it
 *       (NEXUS does not start a second copy). Otherwise the process is spawned and its health URL
 *       polled until it answers (READY) or the startup timeout passes (FAILED).</li>
 *   <li><b>Watch.</b> While up, the health URL is polled every few seconds and the process is
 *       watched; an unexpected exit or a dead server is a crash.</li>
 *   <li><b>Recover.</b> A crashed engine is restarted with exponential backoff (2 s, 4 s, 8 s), at most
 *       {@value #MAX_RESTARTS} times in {@value #RESTART_WINDOW_MIN} minutes, after which it stays FAILED.</li>
 *   <li><b>Log.</b> The process's output is kept in a ring buffer for the UI.</li>
 *   <li><b>GPU memory.</b> Before spawning, the engine takes a lease of its memory budget from the
 *       {@link VramBroker} (waiting, or making room, if the card is full) and gives it back when the
 *       process ends. While idle it may be evicted - stopped - to make room for another process;
 *       the next request starts it again.</li>
 * </ul>
 */
public final class Engine {
    public static final int MAX_RESTARTS = 3;
    public static final int RESTART_WINDOW_MIN = 5;
    private static final int LOG_LINES = 2000;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private final EngineSpec spec;
    private volatile EngineState state = EngineState.STOPPED;
    private volatile Process process;
    private volatile String lastError;
    private volatile Instant since = Instant.now();
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private final List<Consumer<Engine>> listeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> logListeners = new CopyOnWriteArrayList<>();
    private final List<Instant> restarts = new ArrayList<>();
    private volatile boolean wanted;          // the user (or a node) wants it running
    private volatile CompletableFuture<Engine> ready = new CompletableFuture<>();
    private final Object lock = new Object();
    private volatile VramBroker broker;
    private volatile VramBroker.Lease lease;
    private volatile Thread launcher;
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile Instant lastUsed = Instant.now();
    private volatile String evictedFor;

    public Engine(EngineSpec spec) {
        this.spec = spec;
    }

    /** Makes the engine take GPU memory leases from {@code b} (null: no coordination). */
    public void setBroker(VramBroker b) {
        broker = b;
    }

    /** The engine as a holder of GPU memory: an idle LLM server can be evicted. */
    private final VramBroker.Holder holder = new VramBroker.Holder() {
        @Override
        public String name() {
            return spec.name();
        }

        @Override
        public VramBroker.Preemption preemption() {
            return spec.kind() == EngineSpec.Kind.LLM ? VramBroker.Preemption.EVICT : VramBroker.Preemption.NEVER;
        }

        @Override
        public boolean preemptibleNow() {
            return inFlight.get() == 0 && state == EngineState.READY;
        }

        @Override
        public Instant lastUsed() {
            return lastUsed;
        }

        @Override
        public void preempt(String requester) {
            if (!preemptibleNow()) return;
            appendLog("[nexus] stopping to free GPU memory for " + requester + "; the next request starts it again");
            evictedFor = requester;
            stop();
        }
    };

    /**
     * Marks the engine busy for the duration of a request (an engine answering is never evicted).
     * Use in try-with-resources around each call to the server.
     */
    public Use use() {
        inFlight.incrementAndGet();
        lastUsed = Instant.now();
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) return;     // a second close must not count the request twice
            lastUsed = Instant.now();
            inFlight.decrementAndGet();
        };
    }

    /** A request in progress (closing it does not throw). */
    public interface Use extends AutoCloseable {
        @Override
        void close();
    }

    public int inFlight() {
        return inFlight.get();
    }

    public Instant lastUsed() {
        return lastUsed;
    }

    /** Who the engine was last stopped for, to free GPU memory (null if it was not evicted). */
    public String evictedFor() {
        return evictedFor;
    }

    private void releaseLease() {
        var l = lease;
        lease = null;
        if (l != null) l.close();
    }

    public EngineSpec spec() {
        return spec;
    }

    public EngineState state() {
        return state;
    }

    public String lastError() {
        return lastError;
    }

    public Instant since() {
        return since;
    }

    public long pid() {
        var p = process;
        return p == null ? -1 : p.pid();
    }

    public int restartCount() {
        synchronized (restarts) {
            return restarts.size();
        }
    }

    public void addListener(Consumer<Engine> l) {
        listeners.add(l);
    }

    public void addLogListener(Consumer<String> l) {
        logListeners.add(l);
    }

    public List<String> logTail(int n) {
        synchronized (log) {
            var all = new ArrayList<>(log);
            return all.subList(Math.max(0, all.size() - n), all.size());
        }
    }

    private void setState(EngineState s, String error) {
        state = s;
        since = Instant.now();
        if (error != null) lastError = error;
        if (s.isUp()) ready.complete(this);
        for (var l : listeners) l.accept(this);
    }

    private void appendLog(String line) {
        synchronized (log) {
            log.addLast(line);
            while (log.size() > LOG_LINES) log.removeFirst();
        }
        for (var l : logListeners) l.accept(line);
    }

    // ---- control ---------------------------------------------------------------------------------------------

    /**
     * Starts the engine if it is not up, and returns a future completing when it is ready.
     * Safe to call from many nodes at once: they all wait for the same start.
     */
    public CompletableFuture<Engine> ensureStarted() {
        synchronized (lock) {
            wanted = true;
            if (state.isUp()) return CompletableFuture.completedFuture(this);
            if (state == EngineState.STARTING) return ready;
            ready = new CompletableFuture<>();
            setState(EngineState.STARTING, null);
            launcher = Thread.ofVirtual().name("engine-start-" + spec.id()).start(this::launch);
            return ready;
        }
    }

    /** Waits for the engine to be ready, starting it if needed. */
    public Engine awaitReady(Duration timeout) throws Exception {
        try {
            return ensureStarted().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException(spec.name() + " did not become ready in " + timeout.toSeconds() + " s");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException(spec.name() + ": " + cause.getMessage(), cause);
        }
    }

    public void stop() {
        synchronized (lock) {
            wanted = false;
            var p = process;
            if (p == null) {
                if (state == EngineState.STARTING && launcher != null) launcher.interrupt();   // still waiting for GPU memory
                if (state == EngineState.ATTACHED || state == EngineState.FAILED) setState(EngineState.STOPPED, null);
                return;
            }
            setState(EngineState.STOPPING, null);
        }
        var p = process;
        if (p != null) {
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
            try {
                if (!p.waitFor(4, TimeUnit.SECONDS)) {
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly().waitFor(3, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        process = null;
        releaseLease();
        setState(EngineState.STOPPED, null);
    }

    public void restart() {
        stop();
        ensureStarted();
    }

    // ---- lifecycle ---------------------------------------------------------------------------------------------

    private void launch() {
        try {
            if (spec.port() > 0 && healthy()) {
                appendLog("[nexus] something healthy already answers on port " + spec.port() + ": attaching to it");
                setState(EngineState.ATTACHED, null);
                Thread.ofVirtual().name("engine-watch-" + spec.id()).start(this::watch);
                return;
            }
            if (!spec.available()) throw new IOException("executable not found: " + spec.command().getFirst());
            var b = broker;
            if (b != null && spec.vramMiB() > 0 && lease == null) {
                appendLog("[nexus] asking for " + spec.vramMiB() + " MiB of GPU memory");
                lease = b.acquire(holder, spec.vramMiB(), VramBroker.Priority.INTERACTIVE, Duration.ofSeconds(120));
                if (!wanted) {
                    releaseLease();
                    setState(EngineState.STOPPED, null);
                    return;
                }
            }
            appendLog("[nexus] starting: " + String.join(" ", spec.command()));
            var pb = new ProcessBuilder(spec.command()).redirectErrorStream(true);
            if (spec.workDir() != null) pb.directory(spec.workDir().toFile());
            pb.environment().putAll(spec.env());
            Process p;
            try {
                p = pb.start();
            } catch (IOException e) {
                throw new IOException("executable not found or not runnable: " + spec.command().getFirst() + " (" + e.getMessage() + ")", e);
            }
            process = p;
            Thread.ofVirtual().name("engine-log-" + spec.id()).start(() -> pump(p));
            long deadline = System.nanoTime() + spec.startupTimeout().toNanos();
            while (true) {
                if (!p.isAlive()) throw new IOException("the process exited with code " + p.exitValue() + " while starting");
                if (spec.healthPath() == null || healthy()) break;
                if (System.nanoTime() > deadline) throw new IOException("not healthy after " + spec.startupTimeout().toSeconds() + " s");
                Thread.sleep(300);
            }
            appendLog("[nexus] ready (pid " + p.pid() + ")");
            var l = lease;
            if (l != null) l.markActive();
            lastUsed = Instant.now();
            evictedFor = null;
            setState(EngineState.READY, null);
            if (!wanted) {            // stopped while it was starting
                stop();
                return;
            }
            watch();
        } catch (InterruptedException e) {
            releaseLease();
            appendLog("[nexus] start cancelled");
            ready.completeExceptionally(new IllegalStateException(spec.name() + " was stopped while starting"));
            setState(EngineState.STOPPED, null);
        } catch (Exception e) {
            var p = process;
            if (p != null) p.destroyForcibly();
            process = null;
            releaseLease();
            appendLog("[nexus] failed to start: " + e.getMessage());
            ready.completeExceptionally(e);
            setState(EngineState.FAILED, e.getMessage());
        }
    }

    /** Copies the process output into the log. */
    private void pump(Process p) {
        try (var r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) appendLog(line);
        } catch (IOException ignored) {
            // the process ended
        }
    }

    /** Watches a running engine; on a crash, restarts it with backoff if it is still wanted. */
    private void watch() {
        int failures = 0;
        while (wanted && state.isUp()) {
            try {
                Thread.sleep(2500);
            } catch (InterruptedException e) {
                return;
            }
            if (!wanted || !state.isUp()) return;
            var p = process;
            boolean dead = (p != null && !p.isAlive());
            boolean unhealthy = spec.healthPath() != null && !healthy();
            failures = unhealthy ? failures + 1 : 0;
            if (dead || failures >= 3) {
                String why = dead ? "the process exited (code " + p.exitValue() + ")" : "the server stopped answering";
                appendLog("[nexus] crash: " + why);
                process = null;
                releaseLease();
                setState(EngineState.FAILED, why);
                maybeRestart();
                return;
            }
        }
    }

    private void maybeRestart() {
        if (!wanted || state == EngineState.ATTACHED) return;
        int recent;
        synchronized (restarts) {
            var cutoff = Instant.now().minus(Duration.ofMinutes(RESTART_WINDOW_MIN));
            restarts.removeIf(t -> t.isBefore(cutoff));
            recent = restarts.size();
            if (recent >= MAX_RESTARTS) {
                appendLog("[nexus] giving up after " + recent + " restarts in " + RESTART_WINDOW_MIN + " minutes");
                return;
            }
            restarts.add(Instant.now());
        }
        long backoff = 2000L << recent;
        appendLog("[nexus] restarting in " + backoff / 1000 + " s");
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            return;
        }
        if (wanted) ensureStarted();
    }

    /** One health probe (false on any error). */
    public boolean healthy() {
        if (spec.port() <= 0 || spec.healthPath() == null) return false;
        try {
            var req = HttpRequest.newBuilder(URI.create(spec.baseUrl() + spec.healthPath())).timeout(Duration.ofSeconds(2)).GET().build();
            return HTTP.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String toString() {
        return spec.name() + " [" + state + "]";
    }
}
