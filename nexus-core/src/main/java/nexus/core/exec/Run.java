package nexus.core.exec;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** One execution of (part of) a graph. Can be cancelled; completes with a {@link RunResult}. */
public final class Run {
    private final long id;
    private final Set<String> nodes;
    private final long startNanos = System.nanoTime();
    private volatile boolean cancelled;
    final Map<String, Thread> running = new ConcurrentHashMap<>();
    final Map<String, NodeStatus> statuses = new ConcurrentHashMap<>();
    final CompletableFuture<RunResult> done = new CompletableFuture<>();

    Run(long id, Set<String> nodes) {
        this.id = id;
        this.nodes = Set.copyOf(nodes);
        for (var n : nodes) statuses.put(n, NodeStatus.QUEUED);
    }

    public long id() {
        return id;
    }

    /** The nodes this run executes. */
    public Set<String> nodes() {
        return nodes;
    }

    public long startNanos() {
        return startNanos;
    }

    public NodeStatus status(String nodeId) {
        return statuses.getOrDefault(nodeId, NodeStatus.IDLE);
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Stops the run: nodes not yet started never start, running nodes are interrupted. */
    public void cancel() {
        cancelled = true;
        for (var t : running.values()) t.interrupt();
    }

    public CompletableFuture<RunResult> result() {
        return done;
    }

    public boolean isDone() {
        return done.isDone();
    }
}
