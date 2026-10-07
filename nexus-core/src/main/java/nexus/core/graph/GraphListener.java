package nexus.core.graph;

/** Notified of every change to a {@link Graph}, on the thread that made it. All methods default to no-ops. */
public interface GraphListener {
    default void nodeAdded(NodeInstance node) {
    }

    default void nodeRemoved(NodeInstance node) {
    }

    default void nodeMoved(NodeInstance node) {
    }

    default void nodeChanged(NodeInstance node, String paramKey) {
    }

    default void edgeAdded(Edge edge) {
    }

    default void edgeRemoved(Edge edge) {
    }
}
