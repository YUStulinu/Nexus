package nexus.core.edit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import nexus.core.graph.Graph;

/**
 * Undo and redo for one graph. Every edit goes through {@link #execute}; a command that arrives
 * within {@link #MERGE_WINDOW_MS} of the previous one and can merge with it (a drag, typing in a
 * field) extends the previous undo step instead of creating a new one.
 */
public final class CommandStack {
    public static final long MERGE_WINDOW_MS = 800;

    private final Graph graph;
    private final Deque<Command> undo = new ArrayDeque<>();
    private final Deque<Command> redo = new ArrayDeque<>();
    private final int limit;
    private long lastTime;
    private boolean mergeBlocked = true;
    private int savedDepth = 0;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public CommandStack(Graph graph) {
        this(graph, 500);
    }

    public CommandStack(Graph graph, int limit) {
        this.graph = graph;
        this.limit = limit;
    }

    public Graph graph() {
        return graph;
    }

    public void addListener(Runnable r) {
        listeners.add(r);
    }

    /** Applies and records a command. Throws (and records nothing) if the command fails. */
    public void execute(Command c) {
        c.apply(graph);
        long now = System.currentTimeMillis();
        boolean merged = !mergeBlocked && !undo.isEmpty() && now - lastTime < MERGE_WINDOW_MS && undo.peek().mergeWith(c);
        if (!merged) {
            undo.push(c);
            if (undo.size() > limit) undo.removeLast();
        }
        mergeBlocked = false;
        lastTime = now;
        redo.clear();
        fire();
    }

    /** The next command starts a new undo step even if it could merge (e.g. at the end of a drag). */
    public void breakMerge() {
        mergeBlocked = true;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public Optional<String> undoLabel() {
        return Optional.ofNullable(undo.peek()).map(Command::label);
    }

    public Optional<String> redoLabel() {
        return Optional.ofNullable(redo.peek()).map(Command::label);
    }

    public void undo() {
        var c = undo.poll();
        if (c == null) return;
        c.revert(graph);
        redo.push(c);
        mergeBlocked = true;
        fire();
    }

    public void redo() {
        var c = redo.poll();
        if (c == null) return;
        c.apply(graph);
        undo.push(c);
        mergeBlocked = true;
        fire();
    }

    public void clear() {
        undo.clear();
        redo.clear();
        savedDepth = 0;
        mergeBlocked = true;
        fire();
    }

    /** Marks the current state as saved (for the "unsaved changes" indicator). */
    public void markSaved() {
        savedDepth = undo.size();
        mergeBlocked = true;
        fire();
    }

    public boolean isDirty() {
        return undo.size() != savedDepth;
    }

    private void fire() {
        for (var l : listeners) l.run();
    }
}
