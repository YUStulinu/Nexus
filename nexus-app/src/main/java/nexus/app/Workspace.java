package nexus.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import nexus.core.edit.CommandStack;
import nexus.core.exec.ExecutionEngine;
import nexus.core.exec.ExecutionListener;
import nexus.core.exec.Run;
import nexus.core.exec.Services;
import nexus.core.graph.Graph;
import nexus.core.io.WorkflowFile;
import nexus.core.registry.NodeRegistry;

/**
 * Everything one open workflow consists of: the graph, its undo history, the engine that runs it,
 * the file it lives in and the run in progress. The UI components share one Workspace.
 */
public final class Workspace {
    public final NodeRegistry registry;
    public final Services services;
    public final Graph graph = new Graph();
    public final CommandStack stack = new CommandStack(graph);
    public final ExecutionEngine engine;
    public final ObjectProperty<Path> file = new SimpleObjectProperty<>();
    public final StringProperty name = new SimpleStringProperty("untitled");
    public final ObjectProperty<Run> currentRun = new SimpleObjectProperty<>();
    private final CopyOnWriteArrayList<ExecutionListener> runListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<WorkflowFile.View>> loadListeners = new CopyOnWriteArrayList<>();

    public Workspace(NodeRegistry registry, Services services) {
        this.registry = registry;
        this.services = services;
        this.engine = new ExecutionEngine(services);
    }

    /** Listeners that receive the events of every run (the UI bridge, the log...). */
    public void addRunListener(ExecutionListener l) {
        runListeners.add(l);
    }

    /** Called after a workflow is loaded, with the view it was saved with. */
    public void onLoaded(Consumer<WorkflowFile.View> l) {
        loadListeners.add(l);
    }

    public boolean isRunning() {
        var r = currentRun.get();
        return r != null && !r.isDone();
    }

    /** Runs the targets (or every node if null) and whatever they depend on. */
    public Run run(Collection<String> targets) {
        if (isRunning()) currentRun.get().cancel();
        var snap = graph.snapshot();
        var fanout = new FanOut(runListeners);
        var r = targets == null ? engine.runAll(snap, fanout) : engine.run(snap, targets, fanout);
        currentRun.set(r);
        return r;
    }

    public void stop() {
        var r = currentRun.get();
        if (r != null) r.cancel();
    }

    public void newWorkflow() {
        stop();
        graph.clear();
        stack.clear();
        engine.clearCache();
        file.set(null);
        name.set("untitled");
        for (var l : loadListeners) l.accept(WorkflowFile.View.DEFAULT);
    }

    public void open(Path p) throws IOException {
        var content = WorkflowFile.load(p);
        load(content);
        file.set(p);
        name.set(stripExtension(p.getFileName().toString()));
    }

    public void load(WorkflowFile.Content content) throws IOException {
        stop();
        WorkflowFile.loadInto(content, graph, registry);
        stack.clear();
        engine.clearCache();
        file.set(null);
        name.set(content.name());
        for (var l : loadListeners) l.accept(content.view());
    }

    public void save(Path p, WorkflowFile.View view) throws IOException {
        name.set(stripExtension(p.getFileName().toString()));
        WorkflowFile.save(p, name.get(), graph, view);
        file.set(p);
        stack.markSaved();
    }

    private static String stripExtension(String s) {
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    /** Forwards engine events to every registered listener. */
    private record FanOut(Collection<ExecutionListener> targets) implements ExecutionListener {
        @Override
        public void runStarted(Run run, java.util.Set<String> nodes) {
            for (var t : targets) t.runStarted(run, nodes);
        }

        @Override
        public void nodeStatus(String nodeId, nexus.core.exec.NodeStatus status, String message) {
            for (var t : targets) t.nodeStatus(nodeId, status, message);
        }

        @Override
        public void nodeProgress(String nodeId, double fraction, String message) {
            for (var t : targets) t.nodeProgress(nodeId, fraction, message);
        }

        @Override
        public void nodeLog(String nodeId, String line) {
            for (var t : targets) t.nodeLog(nodeId, line);
        }

        @Override
        public void nodeEmit(String nodeId, String channel, Object payload) {
            for (var t : targets) t.nodeEmit(nodeId, channel, payload);
        }

        @Override
        public void nodeOutputs(String nodeId, java.util.Map<String, Object> outputs) {
            for (var t : targets) t.nodeOutputs(nodeId, outputs);
        }

        @Override
        public void runFinished(Run run, nexus.core.exec.RunResult result) {
            for (var t : targets) t.runFinished(run, result);
        }
    }
}
