package nexus.app;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import javafx.animation.AnimationTimer;
import nexus.core.exec.ExecutionListener;
import nexus.core.exec.NodeStatus;
import nexus.core.exec.Run;
import nexus.core.exec.RunResult;

/**
 * Carries execution events from the worker threads to the JavaFX thread.
 *
 * Events are queued as they happen and drained once per frame by an {@link AnimationTimer}. Within
 * a frame, text pieces emitted by the same node on the same channel are concatenated and progress
 * updates collapse to the latest one, so a model streaming hundreds of tokens per second costs the
 * UI one update per node per frame, and the UI thread never blocks the engine.
 */
public final class RunBridge implements ExecutionListener {
    /** Receives the coalesced events on the JavaFX thread. */
    public interface Sink {
        default void runStarted(Run run, Set<String> nodes) {
        }

        default void status(String nodeId, NodeStatus status, String message) {
        }

        default void progress(String nodeId, double fraction, String message) {
        }

        default void log(String nodeId, String line) {
        }

        /** {@code payload} is the concatenation of text pieces, or the latest non-text value. */
        default void emit(String nodeId, String channel, Object payload) {
        }

        default void outputs(String nodeId, Map<String, Object> outputs) {
        }

        default void runFinished(Run run, RunResult result) {
        }
    }

    private sealed interface Event {
    }

    private record Started(Run run, Set<String> nodes) implements Event {
    }

    private record Status(String node, NodeStatus status, String message) implements Event {
    }

    private record Progress(String node, double fraction, String message) implements Event {
    }

    private record Log(String node, String line) implements Event {
    }

    private record Emit(String node, String channel, Object payload) implements Event {
    }

    private record Outputs(String node, Map<String, Object> outputs) implements Event {
    }

    private record Finished(Run run, RunResult result) implements Event {
    }

    private final ConcurrentLinkedQueue<Event> queue = new ConcurrentLinkedQueue<>();
    private final Sink sink;

    public RunBridge(Sink sink) {
        this.sink = sink;
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                drain();
            }
        }.start();
    }

    @Override
    public void runStarted(Run run, Set<String> nodes) {
        queue.add(new Started(run, nodes));
    }

    @Override
    public void nodeStatus(String nodeId, NodeStatus status, String message) {
        queue.add(new Status(nodeId, status, message));
    }

    @Override
    public void nodeProgress(String nodeId, double fraction, String message) {
        queue.add(new Progress(nodeId, fraction, message));
    }

    @Override
    public void nodeLog(String nodeId, String line) {
        queue.add(new Log(nodeId, line));
    }

    @Override
    public void nodeEmit(String nodeId, String channel, Object payload) {
        queue.add(new Emit(nodeId, channel, payload));
    }

    @Override
    public void nodeOutputs(String nodeId, Map<String, Object> outputs) {
        queue.add(new Outputs(nodeId, outputs));
    }

    @Override
    public void runFinished(Run run, RunResult result) {
        queue.add(new Finished(run, result));
    }

    /** Delivers everything queued so far, coalescing streams and progress. Runs on the FX thread. */
    void drain() {
        if (queue.isEmpty()) return;
        // Order matters between kinds (a status after its emits), so coalesce only runs of
        // consecutive mergeable events.
        var pendingText = new LinkedHashMap<String, StringBuilder>();
        var pendingProgress = new LinkedHashMap<String, Progress>();
        Event e;
        int budget = 20_000;
        while (budget-- > 0 && (e = queue.poll()) != null) {
            switch (e) {
                case Emit em when em.payload() instanceof String s ->
                        pendingText.computeIfAbsent(em.node() + "\u0000" + em.channel(), k -> new StringBuilder()).append(s);
                case Progress p -> pendingProgress.put(p.node(), p);
                default -> {
                    flush(pendingText, pendingProgress);
                    deliver(e);
                }
            }
        }
        flush(pendingText, pendingProgress);
    }

    private void flush(Map<String, StringBuilder> text, Map<String, Progress> progress) {
        for (var t : text.entrySet()) {
            int sep = t.getKey().indexOf('\u0000');
            sink.emit(t.getKey().substring(0, sep), t.getKey().substring(sep + 1), t.getValue().toString());
        }
        text.clear();
        for (var p : progress.values()) sink.progress(p.node(), p.fraction(), p.message());
        progress.clear();
    }

    private void deliver(Event e) {
        switch (e) {
            case Started s -> sink.runStarted(s.run(), s.nodes());
            case Status s -> sink.status(s.node(), s.status(), s.message());
            case Progress p -> sink.progress(p.node(), p.fraction(), p.message());
            case Log l -> sink.log(l.node(), l.line());
            case Emit em -> sink.emit(em.node(), em.channel(), em.payload());
            case Outputs o -> sink.outputs(o.node(), o.outputs());
            case Finished f -> sink.runFinished(f.run(), f.result());
        }
    }
}
