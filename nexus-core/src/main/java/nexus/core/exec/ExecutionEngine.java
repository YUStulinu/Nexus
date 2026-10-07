package nexus.core.exec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import nexus.core.graph.Edge;
import nexus.core.graph.Graph;
import nexus.core.graph.PortSpec;
import nexus.core.types.DataTypes;

/**
 * Runs graphs.
 *
 * <p><b>Dataflow on futures.</b> Every output port of every node in the run is a
 * {@link CompletableFuture}; a node is scheduled on its own virtual thread as soon as the futures of
 * all its connected inputs are complete. Independent branches therefore run in parallel with no
 * explicit scheduling, and a node that publishes an output early (a {@code TextStream} it is still
 * writing) lets its consumers start before it finishes.
 *
 * <p><b>Incremental.</b> Every node gets a fingerprint: a hash of its type, its parameters and the
 * fingerprints of whatever feeds its inputs. A cacheable node whose fingerprint matches its last
 * successful run is not executed again - its previous outputs are reused. Nodes marked not
 * cacheable (they read files, clocks, servers) get a new fingerprint on every run, which also
 * forces everything downstream of them to run.
 *
 * <p><b>Failure.</b> A node that throws is marked ERROR; everything that depends on it is SKIPPED,
 * while independent branches carry on. {@link Run#cancel()} stops nodes that have not started and
 * interrupts the ones that have.
 */
public final class ExecutionEngine implements AutoCloseable {
    private final Services services;
    private final ExecutorService executor =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("nexus-node-", 0).factory());
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> lastOutputs = new ConcurrentHashMap<>();
    private final AtomicLong runIds = new AtomicLong();

    private record CacheEntry(String fingerprint, Map<String, Object> outputs) {
    }

    public ExecutionEngine(Services services) {
        this.services = services;
    }

    public Services services() {
        return services;
    }

    /** The outputs of a node's most recent successful execution (empty if none). */
    public Map<String, Object> lastOutputs(String nodeId) {
        return lastOutputs.getOrDefault(nodeId, Map.of());
    }

    /** Forgets a node's cached outputs, so it runs again next time. */
    public void invalidate(String nodeId) {
        cache.remove(nodeId);
    }

    public void clearCache() {
        cache.clear();
    }

    /** Runs every node of the graph. */
    public Run runAll(Graph.Snapshot snap, ExecutionListener listener) {
        return run(snap, snap.nodes().keySet(), listener);
    }

    /** Runs {@code targets} and whatever they depend on. */
    public Run run(Graph.Snapshot snap, Collection<String> targets, ExecutionListener listener) {
        Set<String> needed = upstreamClosure(snap, targets);
        List<String> order = topologicalOrder(snap, needed);
        String salt = Long.toString(System.nanoTime());
        Map<String, String> fingerprints = new HashMap<>();
        for (var id : order) fingerprints.put(id, fingerprint(snap, id, fingerprints, salt));

        // One future per output port of every node in the run.
        Map<String, Map<String, CompletableFuture<Object>>> ports = new HashMap<>();
        for (var id : order) {
            var outs = new LinkedHashMap<String, CompletableFuture<Object>>();
            for (var p : snap.nodes().get(id).definition().outputs()) outs.put(p.key(), new CompletableFuture<>());
            ports.put(id, outs);
        }

        var run = new Run(runIds.incrementAndGet(), new LinkedHashSet<>(order));
        var timings = Collections.synchronizedList(new ArrayList<RunResult.NodeTiming>());
        listener.runStarted(run, run.nodes());
        for (var id : order) listener.nodeStatus(id, NodeStatus.QUEUED, null);

        var tasks = new ArrayList<CompletableFuture<?>>();
        for (var id : order) {
            var deps = new ArrayList<CompletableFuture<Object>>();
            for (var e : snap.edges())
                if (e.toNode().equals(id) && needed.contains(e.fromNode())) deps.add(ports.get(e.fromNode()).get(e.fromPort()));
            var task = CompletableFuture.allOf(deps.toArray(CompletableFuture[]::new))
                    .handleAsync((ok, err) -> {
                        runNode(snap, id, fingerprints.get(id), ports, err, run, listener, timings);
                        return null;
                    }, executor);
            tasks.add(task);
        }
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).whenComplete((v, err) -> {
            long end = System.nanoTime();
            boolean success = run.statuses.values().stream().allMatch(NodeStatus::isSuccess);
            var sorted = new ArrayList<>(timings);
            sorted.sort((a, b) -> Long.compare(a.startNanos(), b.startNanos()));
            var result = new RunResult(run.id(), success, run.isCancelled(), run.startNanos(), end, List.copyOf(sorted));
            try {
                listener.runFinished(run, result);
            } finally {
                run.done.complete(result);
            }
        });
        return run;
    }

    private void runNode(Graph.Snapshot snap, String id, String fp, Map<String, Map<String, CompletableFuture<Object>>> ports,
                         Throwable upstreamError, Run run, ExecutionListener listener, List<RunResult.NodeTiming> timings) {
        var state = snap.nodes().get(id);
        var def = state.definition();
        var outs = ports.get(id);
        long start = System.nanoTime();
        String threadName = Thread.currentThread().getName();

        if (run.isCancelled()) {
            finish(run, listener, timings, id, state.title(), NodeStatus.CANCELLED, "cancelled", start, threadName);
            outs.values().forEach(f -> f.completeExceptionally(new CancellationException("cancelled")));
            return;
        }
        if (upstreamError != null) {
            boolean cancelled = unwrap(upstreamError) instanceof CancellationException;
            finish(run, listener, timings, id, state.title(), cancelled ? NodeStatus.CANCELLED : NodeStatus.SKIPPED,
                   cancelled ? "cancelled" : "an input failed", start, threadName);
            outs.values().forEach(f -> f.completeExceptionally(upstreamError));
            return;
        }

        var cached = cache.get(id);
        if (def.cacheable() && cached != null && cached.fingerprint().equals(fp)) {
            for (var e : outs.entrySet()) e.getValue().complete(cached.outputs().get(e.getKey()));
            lastOutputs.put(id, cached.outputs());
            listener.nodeOutputs(id, cached.outputs());
            finish(run, listener, timings, id, state.title(), NodeStatus.CACHED, "unchanged", start, threadName);
            return;
        }

        run.statuses.put(id, NodeStatus.RUNNING);
        run.running.put(id, Thread.currentThread());
        listener.nodeStatus(id, NodeStatus.RUNNING, null);
        var ctx = new Context(snap, id, ports, run, listener);
        try {
            def.behavior().get().execute(ctx);
            Map<String, Object> produced = new LinkedHashMap<>();
            for (var e : outs.entrySet()) {
                Object v = ctx.written.get(e.getKey());
                if (!e.getValue().isDone()) e.getValue().complete(v);
                produced.put(e.getKey(), v);
            }
            if (def.cacheable()) cache.put(id, new CacheEntry(fp, produced));
            else cache.remove(id);
            lastOutputs.put(id, produced);
            listener.nodeOutputs(id, produced);
            finish(run, listener, timings, id, state.title(), NodeStatus.DONE, null, start, threadName);
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            boolean cancelled = run.isCancelled() || cause instanceof CancellationException || cause instanceof InterruptedException;
            cache.remove(id);
            outs.values().forEach(f -> f.completeExceptionally(cancelled ? new CancellationException("cancelled") : cause));
            String message = cancelled ? "cancelled" : describe(cause);
            finish(run, listener, timings, id, state.title(), cancelled ? NodeStatus.CANCELLED : NodeStatus.ERROR, message, start, threadName);
        } finally {
            run.running.remove(id);
            Thread.interrupted();   // do not leak an interrupt into the next task on this thread
        }
    }

    private static void finish(Run run, ExecutionListener listener, List<RunResult.NodeTiming> timings, String id, String title,
                               NodeStatus status, String message, long start, String thread) {
        run.statuses.put(id, status);
        timings.add(new RunResult.NodeTiming(id, title, status, start, System.nanoTime(), message, thread));
        listener.nodeStatus(id, status, message);
    }

    static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null)
            t = t.getCause();
        return t;
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    // ---- the context handed to a node -------------------------------------------------------------------

    private final class Context implements NodeContext {
        private final Graph.Snapshot snap;
        private final String id;
        private final Map<String, Map<String, CompletableFuture<Object>>> ports;
        private final Run run;
        private final ExecutionListener listener;
        private final Map<String, Object> written = Collections.synchronizedMap(new HashMap<>());
        private final Map<String, Object> inputCache = new HashMap<>();

        Context(Graph.Snapshot snap, String id, Map<String, Map<String, CompletableFuture<Object>>> ports, Run run,
                ExecutionListener listener) {
            this.snap = snap;
            this.id = id;
            this.ports = ports;
            this.run = run;
            this.listener = listener;
        }

        @Override
        public String nodeId() {
            return id;
        }

        @Override
        public String nodeTitle() {
            return snap.nodes().get(id).title();
        }

        @Override
        public Object input(String key) {
            if (inputCache.containsKey(key)) return inputCache.get(key);
            PortSpec spec = snap.nodes().get(id).definition().input(key);
            Optional<Edge> edge = snap.edgeInto(id, key);
            if (edge.isEmpty()) {
                if (spec.optional()) return null;
                throw new IllegalStateException("input '" + spec.label() + "' is not connected");
            }
            var e = edge.get();
            var from = snap.nodes().get(e.fromNode()).definition().output(e.fromPort());
            Object raw = ports.get(e.fromNode()).get(e.fromPort()).join();
            Object value = DataTypes.convert(raw, from.type(), spec.type());
            inputCache.put(key, value);
            return value;
        }

        @Override
        public boolean isConnected(String key) {
            return snap.edgeInto(id, key).isPresent();
        }

        @Override
        public Object param(String key) {
            var params = snap.nodes().get(id).params();
            if (!params.containsKey(key)) throw new IllegalArgumentException(nodeTitle() + " has no parameter '" + key + "'");
            return params.get(key);
        }

        @Override
        public void output(String key, Object value) {
            var f = ports.get(id).get(key);
            if (f == null) throw new IllegalArgumentException(nodeTitle() + " has no output '" + key + "'");
            written.put(key, value);
            f.complete(value);
        }

        @Override
        public void progress(double fraction, String message) {
            listener.nodeProgress(id, fraction, message);
        }

        @Override
        public void log(String message) {
            listener.nodeLog(id, message);
        }

        @Override
        public void emit(String channel, Object payload) {
            listener.nodeEmit(id, channel, payload);
        }

        @Override
        public boolean isCancelled() {
            return run.isCancelled();
        }

        @Override
        public Services services() {
            return services;
        }
    }

    // ---- graph helpers --------------------------------------------------------------------------------------

    static Set<String> upstreamClosure(Graph.Snapshot snap, Collection<String> targets) {
        var result = new LinkedHashSet<String>();
        var stack = new ArrayDeque<String>();
        for (var t : targets) if (snap.nodes().containsKey(t)) stack.push(t);
        while (!stack.isEmpty()) {
            var id = stack.pop();
            if (!result.add(id)) continue;
            for (var e : snap.edges()) if (e.toNode().equals(id)) stack.push(e.fromNode());
        }
        return result;
    }

    static List<String> topologicalOrder(Graph.Snapshot snap, Set<String> subset) {
        var indegree = new LinkedHashMap<String, Integer>();
        for (var id : snap.nodes().keySet()) if (subset.contains(id)) indegree.put(id, 0);
        for (var e : snap.edges())
            if (subset.contains(e.fromNode()) && subset.contains(e.toNode())) indegree.merge(e.toNode(), 1, Integer::sum);
        var ready = new ArrayDeque<String>();
        indegree.forEach((id, d) -> {
            if (d == 0) ready.add(id);
        });
        var order = new ArrayList<String>();
        while (!ready.isEmpty()) {
            var id = ready.poll();
            order.add(id);
            for (var e : snap.edges())
                if (e.fromNode().equals(id) && indegree.containsKey(e.toNode()) && indegree.merge(e.toNode(), -1, Integer::sum) == 0)
                    ready.add(e.toNode());
        }
        if (order.size() != indegree.size()) throw new IllegalStateException("the graph has a cycle");
        return order;
    }

    /** Hash of the node's type, parameters and the fingerprints of its inputs' sources. */
    private static String fingerprint(Graph.Snapshot snap, String id, Map<String, String> known, String salt) {
        var state = snap.nodes().get(id);
        var sb = new StringBuilder(state.definition().id()).append('|');
        new TreeMap<>(state.params()).forEach((k, v) -> sb.append(k).append('=').append(canonical(v)).append(';'));
        for (var in : state.definition().inputs()) {
            sb.append('|').append(in.key()).append('<');
            snap.edgeInto(id, in.key()).ifPresent(e -> sb.append(known.get(e.fromNode())).append('.').append(e.fromPort()));
        }
        if (!state.definition().cacheable()) sb.append("|volatile:").append(salt);
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(sb.toString().getBytes(StandardCharsets.UTF_8)), 0, 12);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String canonical(Object v) {
        if (v instanceof Double d) return DataTypes.formatNumber(d);
        return String.valueOf(v);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
