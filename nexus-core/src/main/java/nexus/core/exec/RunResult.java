package nexus.core.exec;

import java.util.List;

/**
 * The outcome of a run, with the timing of every node (the data of the run timeline).
 *
 * @param timings in start order
 */
public record RunResult(long runId, boolean success, boolean cancelled, long startNanos, long endNanos, List<NodeTiming> timings) {

    /**
     * @param thread the name of the thread that ran the node (virtual threads are named per node)
     */
    public record NodeTiming(String nodeId, String title, NodeStatus status, long startNanos, long endNanos, String message,
                             String thread) {
        public double millis() {
            return (endNanos - startNanos) / 1e6;
        }
    }

    public double millis() {
        return (endNanos - startNanos) / 1e6;
    }

    public long count(NodeStatus s) {
        return timings.stream().filter(t -> t.status() == s).count();
    }
}
