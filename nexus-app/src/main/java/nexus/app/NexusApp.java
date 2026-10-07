package nexus.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import nexus.app.canvas.GraphCanvas;
import nexus.app.panels.Inspector;
import nexus.app.panels.LogPanel;
import nexus.app.panels.Palette;
import nexus.app.panels.TimelinePanel;
import nexus.app.util.Png;
import nexus.core.exec.NodeStatus;
import nexus.core.exec.Run;
import nexus.core.exec.RunResult;
import nexus.core.exec.Services;
import nexus.core.io.WorkflowFile;
import nexus.core.registry.NodeRegistry;

/**
 * The NEXUS application window.
 *
 * <p>Command line (mostly for documentation screenshots and smoke tests):
 * <pre>
 *   --open FILE | --example NAME   load a workflow
 *   --run                          run it after loading
 *   --select NODE_ID               select a node (shows it in the inspector)
 *   --wait SECONDS                 wait before the screenshot (default 2)
 *   --screenshot FILE.png          save a screenshot of the window, then exit
 *   --size WIDTHxHEIGHT            window size (default 1500x900)
 *   --chat NODE_ID:MESSAGE         type a message into a Conversation node
 *   --tab NAME                     show a bottom tab (Timeline, Log, Engines, System)
 *   --split FRACTION               height of the canvas above the bottom tabs (default 0.76)
 * </pre>
 */
public class NexusApp extends Application {
    public static final List<String> EXAMPLES = List.of("Text analysis", "Parallel branches", "Formula", "Brief and quiz with Ember",
                                                        "Model duel", "Chat with a document",
                                                        "Ask your documents", "AlphaZero (Gambit)", "Train while you chat",
                                                        "Kindling vs Qwen3", "Anvil vs PyTorch");

    /** Extra services and node libraries, contributed by other modules before launch. */
    public static final Services SERVICES = new Services();

    private Workspace ws;
    private GraphCanvas canvas;
    private Inspector inspector;
    private LogPanel log;
    private TimelinePanel timeline;
    private final Label status = new Label("ready");
    private final Label info = new Label();
    private Stage stage;
    private Button runButton, stopButton;
    private final Map<String, Long> started = new HashMap<>();
    private TabPane bottom;
    private nexus.store.WorkflowHistory history;
    private nexus.app.panels.HistoryPanel historyPanel;
    private SplitPane center;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        nexus.app.views.LlmViews.register();
        nexus.app.views.RagViews.register();
        nexus.app.views.GameViews.register();
        nexus.app.views.TrainingViews.register();
        var engines = nexus.engines.EngineManager.instance();
        SERVICES.register(nexus.engines.EngineManager.class, engines);
        var registry = NodeRegistry.discover();
        ws = new Workspace(registry, SERVICES);
        canvas = new GraphCanvas(ws);
        inspector = new Inspector(ws);
        log = new LogPanel();
        timeline = new TimelinePanel();
        timeline.setMonitor(engines.monitor());
        canvas.primarySelection().addListener((o, a, id) -> inspector.show(id));

        ws.addRunListener(new RunBridge(new RunBridge.Sink() {
            @Override
            public void runStarted(Run run, Set<String> nodes) {
                recordVersion("run");
                canvas.runStarted(nodes);
                timeline.runStarted();
                started.clear();
                log.add(LogPanel.Level.INFO, "run #" + run.id() + " started (" + nodes.size() + " nodes)");
                status.setText("running…");
                runButton.setDisable(false);
                stopButton.setDisable(false);
            }

            @Override
            public void status(String id, NodeStatus s, String message) {
                canvas.nodeStatus(id, s, message);
                String title = ws.graph.find(id).map(n -> n.title()).orElse(id);
                switch (s) {
                    case RUNNING -> started.put(id, System.nanoTime());
                    case DONE -> log.add(LogPanel.Level.OK, title + ": done"
                                         + (started.containsKey(id) ? String.format(" in %.1f ms", (System.nanoTime() - started.get(id)) / 1e6) : ""));
                    case ERROR -> log.add(LogPanel.Level.ERROR, title + ": " + message);
                    case SKIPPED -> log.add(LogPanel.Level.WARN, title + ": skipped (" + message + ")");
                    default -> {
                    }
                }
            }

            @Override
            public void progress(String id, double fraction, String message) {
                canvas.nodeProgress(id, fraction, message);
            }

            @Override
            public void log(String id, String line) {
                log.add(LogPanel.Level.INFO, ws.graph.find(id).map(n -> n.title()).orElse(id) + ": " + line);
            }

            @Override
            public void emit(String id, String channel, Object payload) {
                canvas.nodeEmit(id, channel, payload);
            }

            @Override
            public void outputs(String id, Map<String, Object> outputs) {
                canvas.nodeOutputs(id, outputs);
            }

            @Override
            public void runFinished(Run run, RunResult r) {
                timeline.show(r);
                inspector.setLastRun(r);
                String summary = String.format("run #%d %s in %.0f ms: %d done, %d cached, %d errors, %d skipped", run.id(),
                                               r.cancelled() ? "stopped" : r.success() ? "finished" : "failed", r.millis(),
                                               r.count(NodeStatus.DONE), r.count(NodeStatus.CACHED), r.count(NodeStatus.ERROR),
                                               r.count(NodeStatus.SKIPPED));
                log.add(r.success() ? LogPanel.Level.OK : r.cancelled() ? LogPanel.Level.WARN : LogPanel.Level.ERROR, summary);
                status.setText(summary);
                stopButton.setDisable(true);
                recordRun(r);
            }
        }));

        var palette = new Palette(registry, def -> {
            var c = canvas.toWorld(canvas.getWidth() / 2, canvas.getHeight() / 2);
            canvas.addNode(def, c.getX() - 110, c.getY() - 60);
        });
        bottom = new TabPane(new Tab("Timeline", timeline), new Tab("Log", log),
                             new Tab("Engines", new nexus.app.panels.EnginesPanel(engines)));
        if (engines.monitor() != null)
            bottom.getTabs().add(new Tab("System", new nexus.app.panels.SystemPanel(engines.monitor(), engines.broker())));
        openHistory();
        if (history != null) {
            historyPanel = new nexus.app.panels.HistoryPanel(history, ws.name::get, this::restoreVersion);
            var tab = new Tab("History", historyPanel);
            tab.setOnSelectionChanged(e -> {
                if (tab.isSelected()) historyPanel.refresh();
            });
            bottom.getTabs().add(tab);
        }
        bottom.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        center = new SplitPane(canvas, bottom);
        center.setOrientation(Orientation.VERTICAL);
        center.setDividerPositions(0.76);
        var main = new SplitPane(palette, center, inspector);
        main.setDividerPositions(0.16, 0.79);
        SplitPane.setResizableWithParent(palette, false);
        SplitPane.setResizableWithParent(inspector, false);

        var root = new BorderPane(main);
        root.setTop(new VBox(menuBar(), toolBar()));
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var statusBar = new HBox(10, status, spacer, info);
        statusBar.getStyleClass().add("status-bar");
        root.setBottom(statusBar);

        var params = getParameters().getNamed();
        var raw = getParameters().getRaw();
        var opts = parseOptions(raw);
        String[] size = opts.getOrDefault("size", "1500x900").split("x");
        var scene = new Scene(root, Double.parseDouble(size[0]), Double.parseDouble(size[1]));
        scene.getStylesheets().add(Objects.requireNonNull(NexusApp.class.getResource("nexus.css")).toExternalForm());
        installShortcuts(scene);
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        ws.stack.addListener(this::updateTitle);
        ws.name.addListener((o, a, b) -> updateTitle());
        updateTitle();
        stage.setOnCloseRequest(e -> {
            if (!confirmDiscard()) e.consume();
            else shutdown();
        });
        var timer = new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                info.setText(ws.graph.size() + " nodes · " + ws.graph.edges().size() + " wires · zoom "
                             + Math.round(canvas.zoomLevel() * 100) + "%");
            }
        };
        timer.start();
        stage.show();
        if (params.isEmpty() && raw.isEmpty()) loadExample("Text analysis");
        else Platform.runLater(() -> applyOptions(opts));
    }

    /** Stops runs and every engine process NEXUS started. */
    private void shutdown() {
        ws.stop();
        ws.engine.close();
        try {
            if (history != null) history.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        Thread.ofVirtual().start(() -> nexus.engines.EngineManager.instance().close());
    }

    // ---- history (Tessera) ------------------------------------------------------------------------------------

    /** Opens the history store in ~/.nexus (Tessera, or a plain file if its native library is missing). */
    private void openHistory() {
        try {
            var file = nexus.ml.bert.EmbeddingModels.home().resolve("nexus.tdb");
            history = new nexus.store.WorkflowHistory(nexus.store.KeyValueStore.open(file));
            log.add(LogPanel.Level.INFO, "history: " + history.store().engine());
        } catch (Exception e) {
            history = null;
            log.add(LogPanel.Level.WARN, "history is off: " + e.getMessage());
        }
    }

    private String currentJson() {
        return WorkflowFile.toJson(ws.name.get(), ws.graph.nodes(), ws.graph.edges(), canvas.view());
    }

    private void recordVersion(String note) {
        if (history == null || ws.graph.nodes().isEmpty()) return;
        try {
            history.save(ws.name.get(), currentJson(), note);
        } catch (Exception e) {
            log.add(LogPanel.Level.WARN, "could not record a version: " + e.getMessage());
        }
    }

    private void recordRun(RunResult r) {
        if (history == null || r.cancelled()) return;
        try {
            var nodes = new HashMap<String, Double>();
            for (var t : r.timings()) nodes.put(t.nodeId(), t.millis());
            history.recordRun(new nexus.store.WorkflowHistory.RunRecord(ws.name.get(), java.time.Instant.now(), r.millis(), (int) r.count(NodeStatus.DONE),
                                                                        (int) r.count(NodeStatus.CACHED), (int) r.count(NodeStatus.ERROR),
                                                                        (int) r.count(NodeStatus.SKIPPED), nodes));
            if (historyPanel != null && historyPanel.isVisible()) historyPanel.refresh();
        } catch (Exception e) {
            log.add(LogPanel.Level.WARN, "could not record the run: " + e.getMessage());
        }
    }

    private void restoreVersion(String json) {
        try {
            String name = ws.name.get();
            ws.load(WorkflowFile.parse(json));
            ws.name.set(name);
            log.add(LogPanel.Level.OK, "restored an earlier version of " + name);
            Platform.runLater(canvas::fitView);
        } catch (IOException e) {
            error("Cannot restore the version", e);
        }
    }

    // ---- menus, toolbar, shortcuts -------------------------------------------------------------------------

    private MenuBar menuBar() {
        var file = new Menu("File");
        file.getItems().addAll(item("New", "Shortcut+N", this::newWorkflow), item("Open…", "Shortcut+O", this::open),
                               item("Save", "Shortcut+S", this::save), item("Save as…", "Shortcut+Shift+S", this::saveAs),
                               new SeparatorMenuItem(), item("Exit", null, stage::close));
        var edit = new Menu("Edit");
        var undo = item("Undo", "Shortcut+Z", ws.stack::undo);
        var redo = item("Redo", "Shortcut+Y", ws.stack::redo);
        edit.setOnShowing(e -> {
            undo.setText(ws.stack.undoLabel().map(l -> "Undo " + l).orElse("Undo"));
            redo.setText(ws.stack.redoLabel().map(l -> "Redo " + l).orElse("Redo"));
            undo.setDisable(!ws.stack.canUndo());
            redo.setDisable(!ws.stack.canRedo());
        });
        edit.getItems().addAll(undo, redo, new SeparatorMenuItem(),
                               item("Cut", "Shortcut+X", canvas::cutSelection), item("Copy", "Shortcut+C", canvas::copySelection),
                               item("Paste", "Shortcut+V", canvas::paste), item("Duplicate", "Shortcut+D", canvas::duplicateSelection),
                               item("Delete", null, canvas::deleteSelection), new SeparatorMenuItem(),
                               item("Select all", "Shortcut+A", canvas::selectAll), item("Add node…", null, canvas::openQuickAddAtMouse));
        var view = new Menu("View");
        view.getItems().addAll(item("Fit view", null, canvas::fitView), item("Zoom in", "Shortcut+Equals",
                () -> canvas.zoomAt(canvas.getWidth() / 2, canvas.getHeight() / 2, 1.2)), item("Zoom out", "Shortcut+Minus",
                () -> canvas.zoomAt(canvas.getWidth() / 2, canvas.getHeight() / 2, 1 / 1.2)));
        var run = new Menu("Run");
        run.getItems().addAll(item("Run all", null, () -> ws.run(null)), item("Run selected", null, this::runSelected),
                              item("Stop", null, ws::stop), new SeparatorMenuItem(),
                              item("Clear cache", null, () -> {
                                  ws.engine.clearCache();
                                  log.add(LogPanel.Level.INFO, "cache cleared: every node will run again");
                              }), item("Clear log", null, log::clearLog));
        var examples = new Menu("Examples");
        for (var name : EXAMPLES) examples.getItems().add(item(name, null, () -> {
            if (confirmDiscard()) loadExample(name);
        }));
        var help = new Menu("Help");
        help.getItems().add(item("About NEXUS", null, this::about));
        return new MenuBar(file, edit, view, run, examples, help);
    }

    private ToolBar toolBar() {
        runButton = new Button("▶  Run");
        runButton.getStyleClass().add("run");
        runButton.setTooltip(new Tooltip("Run the whole workflow (F5)"));
        runButton.setOnAction(e -> ws.run(null));
        stopButton = new Button("■  Stop");
        stopButton.getStyleClass().add("stop");
        stopButton.setDisable(true);
        stopButton.setTooltip(new Tooltip("Stop the run (Esc)"));
        stopButton.setOnAction(e -> ws.stop());
        var runSel = new Button("▶ Selected");
        runSel.setTooltip(new Tooltip("Run the selected nodes and what they need (F6)"));
        runSel.setOnAction(e -> runSelected());
        var undo = new Button("↶");
        undo.setTooltip(new Tooltip("Undo (Ctrl+Z)"));
        undo.setOnAction(e -> ws.stack.undo());
        var redo = new Button("↷");
        redo.setTooltip(new Tooltip("Redo (Ctrl+Y)"));
        redo.setOnAction(e -> ws.stack.redo());
        var add = new Button("+ Node");
        add.setTooltip(new Tooltip("Add a node (Tab or double-click the canvas)"));
        add.setOnAction(e -> canvas.openQuickAddAtMouse());
        var fit = new Button("⤢ Fit");
        fit.setTooltip(new Tooltip("Fit the whole workflow in view (F)"));
        fit.setOnAction(e -> canvas.fitView());
        var save = new Button("💾");
        save.setTooltip(new Tooltip("Save (Ctrl+S)"));
        save.setOnAction(e -> save());
        ws.stack.addListener(() -> {
            undo.setDisable(!ws.stack.canUndo());
            redo.setDisable(!ws.stack.canRedo());
        });
        undo.setDisable(true);
        redo.setDisable(true);
        return new ToolBar(runButton, runSel, stopButton, new javafx.scene.control.Separator(), add, fit,
                           new javafx.scene.control.Separator(), undo, redo, save);
    }

    private MenuItem item(String text, String accel, Runnable action) {
        var m = new MenuItem(text);
        if (accel != null) m.setAccelerator(KeyCombination.keyCombination(accel));
        m.setOnAction(e -> action.run());
        return m;
    }

    private void installShortcuts(Scene scene) {
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F5), () -> ws.run(null));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F6), this::runSelected);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN), () -> ws.run(null));
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            boolean typing = scene.getFocusOwner() instanceof javafx.scene.control.TextInputControl;
            if (typing) return;
            switch (e.getCode()) {
                case ESCAPE -> ws.stop();
                case DELETE, BACK_SPACE -> {
                    canvas.deleteSelection();
                    e.consume();
                }
                case TAB -> {
                    canvas.openQuickAddAtMouse();
                    e.consume();
                }
                case F -> {
                    if (!e.isShortcutDown()) {
                        canvas.fitView();
                        e.consume();
                    }
                }
                default -> {
                }
            }
        });
    }

    private void runSelected() {
        var sel = canvas.selection();
        if (!sel.isEmpty()) ws.run(sel);
    }

    // ---- files -----------------------------------------------------------------------------------------------

    private void updateTitle() {
        stage.setTitle((ws.stack.isDirty() ? "• " : "") + ws.name.get() + " - NEXUS");
    }

    private boolean confirmDiscard() {
        if (!ws.stack.isDirty()) return true;
        var a = new Alert(Alert.AlertType.CONFIRMATION, "The workflow has unsaved changes. Discard them?", ButtonType.YES, ButtonType.NO);
        a.setHeaderText(null);
        a.initOwner(stage);
        return a.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }

    private FileChooser chooser() {
        var fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("NEXUS workflow", "*.nexus"));
        if (ws.file.get() != null && ws.file.get().getParent() != null) fc.setInitialDirectory(ws.file.get().getParent().toFile());
        return fc;
    }

    private void newWorkflow() {
        if (confirmDiscard()) ws.newWorkflow();
    }

    private void open() {
        if (!confirmDiscard()) return;
        var f = chooser().showOpenDialog(stage);
        if (f == null) return;
        try {
            ws.open(f.toPath());
            log.add(LogPanel.Level.INFO, "opened " + f);
        } catch (IOException ex) {
            error("Cannot open the workflow", ex);
        }
    }

    private void save() {
        if (ws.file.get() == null) saveAs();
        else saveTo(ws.file.get());
    }

    private void saveAs() {
        var fc = chooser();
        fc.setInitialFileName(ws.name.get() + ".nexus");
        var f = fc.showSaveDialog(stage);
        if (f != null) saveTo(f.toPath());
    }

    private void saveTo(Path p) {
        try {
            ws.save(p, canvas.view());
            log.add(LogPanel.Level.OK, "saved " + p);
            recordVersion("saved");
        } catch (IOException ex) {
            error("Cannot save the workflow", ex);
        }
    }

    private void loadExample(String name) {
        try (InputStream in = NexusApp.class.getResourceAsStream("examples/" + name + ".nexus")) {
            if (in == null) throw new IOException("example '" + name + "' not found");
            ws.load(WorkflowFile.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            ws.name.set(name);
            Platform.runLater(canvas::fitView);
        } catch (IOException ex) {
            error("Cannot load the example", ex);
        }
    }

    private void error(String title, Exception ex) {
        var a = new Alert(Alert.AlertType.ERROR, ex.getMessage(), ButtonType.OK);
        a.setHeaderText(title);
        a.initOwner(stage);
        a.showAndWait();
    }

    private void about() {
        var a = new Alert(Alert.AlertType.INFORMATION);
        a.initOwner(stage);
        a.setHeaderText("NEXUS 0.1");
        a.setContentText("A visual, real-time AI studio.\n\nNode libraries: " + String.join(", ", ws.registry.libraries())
                         + "\nNode types: " + ws.registry.all().size() + "\nJava " + Runtime.version()
                         + ", JavaFX " + System.getProperty("javafx.version"));
        a.showAndWait();
    }

    // ---- command-line automation ------------------------------------------------------------------------------

    private static Map<String, String> parseOptions(List<String> raw) {
        var m = new HashMap<String, String>();
        for (int i = 0; i < raw.size(); i++) {
            String a = raw.get(i);
            if (!a.startsWith("--")) continue;
            String key = a.substring(2);
            String value = i + 1 < raw.size() && !raw.get(i + 1).startsWith("--") ? raw.get(++i) : "true";
            m.put(key, value);
        }
        return m;
    }

    private void applyOptions(Map<String, String> o) {
        try {
            if (o.containsKey("example")) loadExample(o.get("example"));
            if (o.containsKey("open")) ws.open(Path.of(o.get("open")));
            Platform.runLater(() -> {
                canvas.fitView();
                if (o.containsKey("select")) canvas.select(o.get("select"), false);
                if (o.containsKey("tab"))
                    bottom.getTabs().stream().filter(t -> t.getText().equalsIgnoreCase(o.get("tab"))).findFirst()
                          .ifPresent(t -> bottom.getSelectionModel().select(t));
                if (o.containsKey("split")) center.setDividerPositions(Double.parseDouble(o.get("split")));
                if (o.containsKey("run")) ws.run(null);
                if (o.containsKey("chat")) {
                    // --chat "nodeId:message": types a message into a conversation node (after the run settles)
                    String[] parts = o.get("chat").split(":", 2);
                    var later = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5));
                    later.setOnFinished(e -> canvas.view(parts[0]).ifPresent(v -> v.body().submit(parts[1])));
                    later.play();
                }
                if (o.containsKey("screenshot")) {
                    double wait = Double.parseDouble(o.getOrDefault("wait", "2"));
                    var pause = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(wait));
                    pause.setOnFinished(e -> {
                        try {
                            Png.write(stage.getScene().snapshot(null), Path.of(o.get("screenshot")));
                        } catch (IOException ex) {
                            ex.printStackTrace();
                        }
                        shutdown();
                        Platform.exit();
                    });
                    pause.play();
                }
            });
        } catch (IOException ex) {
            error("Cannot load", ex);
        }
    }
}
