package nexus.core.edit;

import nexus.core.graph.Graph;

/** A reversible change to a graph. */
public interface Command {
    /** Applies the change (also used for redo). */
    void apply(Graph g);

    /** Reverts exactly what {@link #apply} did. */
    void revert(Graph g);

    /** Shown in the Edit menu ("Undo move", ...). */
    String label();

    /**
     * Folds a following command into this one, so that e.g. the many small moves of one drag become
     * a single undo step. Returns false if the commands cannot be merged.
     */
    default boolean mergeWith(Command next) {
        return false;
    }
}
