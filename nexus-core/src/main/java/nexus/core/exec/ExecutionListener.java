package nexus.core.exec;

import java.util.Map;
import java.util.Set;

/**
 * Events of a run, delivered on the worker threads as they happen (a UI must hop to its own thread).
 * All methods default to no-ops.
 */
public interface ExecutionListener {
    default void runStarted(Run run, Set<String> nodes) {
    }

    default void nodeStatus(String nodeId, NodeStatus status, String message) {
    }

    default void nodeProgress(String nodeId, double fraction, String message) {
    }

    default void nodeLog(String nodeId, String line) {
    }

    default void nodeEmit(String nodeId, String channel, Object payload) {
    }

    default void nodeOutputs(String nodeId, Map<String, Object> outputs) {
    }

    default void runFinished(Run run, RunResult result) {
    }
}
