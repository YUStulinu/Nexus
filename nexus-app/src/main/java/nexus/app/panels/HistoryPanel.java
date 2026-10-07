package nexus.app.panels;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import nexus.store.WorkflowHistory;

/**
 * The history of the open workflow, kept in Tessera: every version that was run or saved (only
 * real changes count), what changed from one version to the next, a button to go back to any of
 * them, and the durations of past runs.
 */
public final class HistoryPanel extends HBox {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM HH:mm:ss", Locale.ENGLISH);

    private final WorkflowHistory history;
    private final Supplier<String> workflowName;
    private final Consumer<String> restore;
    private final ListView<WorkflowHistory.Version> versions = new ListView<>();
    private final TextArea diff = new TextArea();
    private final Label engine = new Label(), runsTitle = new Label("RUNS");
    private final Canvas runs = new Canvas();
    private final Button restoreButton = new Button("Restore this version");
    private List<WorkflowHistory.RunRecord> runList = List.of();

    public HistoryPanel(WorkflowHistory history, Supplier<String> workflowName, Consumer<String> restore) {
        this.history = history;
        this.workflowName = workflowName;
        this.restore = restore;
        setSpacing(10);
        setPadding(new Insets(6, 8, 6, 8));
        setStyle("-fx-background-color: #181a20;");
        versions.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(WorkflowHistory.Version v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null
                                           : String.format("v%d   %s   %s   (%d nodes, %d wires)", v.number(),
                                                           TIME.format(LocalDateTime.ofInstant(v.time(), ZoneId.systemDefault())), v.note(), v.nodes(),
                                                           v.edges()));
            }
        });
        versions.setPrefWidth(420);
        versions.getSelectionModel().selectedItemProperty().addListener((o, a, v) -> showDiff(v));
        diff.setEditable(false);
        diff.setStyle("-fx-font-family: 'Cascadia Mono', Consolas, monospace; -fx-font-size: 11px;");
        restoreButton.setOnAction(e -> {
            var v = versions.getSelectionModel().getSelectedItem();
            if (v == null) return;
            try {
                restore.accept(history.load(workflowName.get(), v.number()));
            } catch (Exception ex) {
                diff.setText("cannot restore: " + ex.getMessage());
            }
        });
        engine.setStyle("-fx-text-fill: #8b93a7; -fx-font-size: 10.5px;");
        engine.setText("stored in " + history.store().engine());
        runsTitle.setStyle("-fx-text-fill: #8b93a7; -fx-font-size: 10.5px; -fx-font-weight: bold;");
        var left = new VBox(4, title("VERSIONS"), versions, engine);
        VBox.setVgrow(versions, Priority.ALWAYS);
        var middle = new VBox(4, title("CHANGES FROM THE PREVIOUS VERSION"), diff, restoreButton);
        VBox.setVgrow(diff, Priority.ALWAYS);
        HBox.setHgrow(middle, Priority.ALWAYS);
        var right = new VBox(4, runsTitle, runs);
        right.setPrefWidth(360);
        right.setMinWidth(260);
        right.heightProperty().addListener((o, a, b) -> drawRuns());
        right.widthProperty().addListener((o, a, b) -> drawRuns());
        getChildren().addAll(left, middle, right);
    }

    static Label title(String text) {
        var l = new Label(text);
        l.setStyle("-fx-text-fill: #8b93a7; -fx-font-size: 10.5px; -fx-font-weight: bold;");
        return l;
    }

    /** Reloads the versions and runs of the current workflow. */
    public void refresh() {
        try {
            String name = workflowName.get();
            var list = history.versions(name);
            versions.setItems(FXCollections.observableArrayList(list.reversed()));
            if (!list.isEmpty()) versions.getSelectionModel().selectFirst();
            else diff.setText("No versions yet: running or saving the workflow records one.");
            runList = history.runs(name);
            runsTitle.setText("RUNS (" + runList.size() + ")");
            drawRuns();
        } catch (Exception e) {
            diff.setText("history unavailable: " + e.getMessage());
        }
    }

    private void showDiff(WorkflowHistory.Version v) {
        if (v == null) return;
        try {
            String name = workflowName.get();
            if (v.number() == 1) {
                diff.setText("The first recorded version (" + v.nodes() + " nodes, " + v.edges() + " wires).");
                return;
            }
            var lines = WorkflowHistory.diff(history.load(name, v.number() - 1), history.load(name, v.number()));
            diff.setText(lines.isEmpty() ? "(only layout changes)" : String.join("\n", lines));
        } catch (Exception e) {
            diff.setText(e.getMessage());
        }
    }

    /** One bar per run: its duration, coloured by result. */
    private void drawRuns() {
        var parent = (VBox) runs.getParent();
        if (parent == null) return;
        double w = Math.max(10, parent.getWidth()), h = Math.max(10, parent.getHeight() - 22);
        runs.setWidth(w);
        runs.setHeight(h);
        var g = runs.getGraphicsContext2D();
        g.setFill(Color.web("#1d2027"));
        g.fillRoundRect(0, 0, w, h, 6, 6);
        var list = runList.size() > 60 ? runList.subList(runList.size() - 60, runList.size()) : runList;
        if (list.isEmpty()) return;
        double max = list.stream().mapToDouble(WorkflowHistory.RunRecord::millis).max().orElse(1);
        double bw = Math.min(24, (w - 12) / list.size());
        for (int i = 0; i < list.size(); i++) {
            var r = list.get(i);
            double bh = Math.max(2, (h - 34) * r.millis() / max);
            g.setFill(r.errors() > 0 ? Color.web("#e03131") : r.cached() > 0 && r.done() == 0 ? Color.web("#0ca678") : Color.web("#2f9e44"));
            g.fillRoundRect(6 + i * bw, h - 18 - bh, Math.max(2, bw - 3), bh, 3, 3);
        }
        g.setFill(Color.web("#8b93a7"));
        g.setFont(Font.font("System", 10));
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.TOP);
        var last = list.getLast();
        g.fillText(String.format(Locale.ROOT, "longest %s  ·  last %s (%d done, %d cached, %d errors)", duration(max), duration(last.millis()),
                                 last.done(), last.cached(), last.errors()), 6, h - 15);
    }

    static String duration(double ms) {
        return ms < 1000 ? String.format(Locale.ROOT, "%.0f ms", ms) : String.format(Locale.ROOT, "%.1f s", ms / 1000);
    }
}
