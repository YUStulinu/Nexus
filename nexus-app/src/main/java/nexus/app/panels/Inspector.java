package nexus.app.panels;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import nexus.app.Workspace;
import nexus.app.views.Bodies;
import nexus.core.edit.Commands;
import nexus.core.exec.RunResult;
import nexus.core.graph.GraphListener;
import nexus.core.graph.NodeInstance;
import nexus.core.graph.ParamSpec;
import nexus.core.types.DataTypes;

/**
 * Shows the selected node: its title (editable), what it does, an editor for every parameter
 * (generated from the parameter specs; every change is an undoable command, and typing merges into
 * one undo step), its ports, and what it produced last time it ran.
 */
public final class Inspector extends VBox {
    private final Workspace ws;
    private final VBox content = new VBox(6);
    private String nodeId;
    private final Map<String, Runnable> refreshers = new HashMap<>();
    private RunResult lastRun;

    public Inspector(Workspace ws) {
        this.ws = ws;
        getStyleClass().addAll("side-panel", "inspector");
        var title = new Label("INSPECTOR");
        title.getStyleClass().add("panel-title");
        content.setPadding(new Insets(0, 12, 12, 12));
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(title, scroll);
        setPrefWidth(300);
        ws.graph.addListener(new GraphListener() {
            @Override
            public void nodeChanged(NodeInstance n, String key) {
                if (n.id().equals(nodeId)) refreshers.values().forEach(Runnable::run);
            }

            @Override
            public void nodeRemoved(NodeInstance n) {
                if (n.id().equals(nodeId)) show(null);
            }
        });
        show(null);
    }

    public void setLastRun(RunResult r) {
        lastRun = r;
        if (nodeId != null) show(nodeId);
    }

    public void show(String id) {
        nodeId = id;
        refreshers.clear();
        content.getChildren().clear();
        if (id == null || ws.graph.find(id).isEmpty()) {
            var none = new Label("Select a node to see and edit its settings.\n\n"
                                 + "Tips\n• Double-click the canvas (or press Tab) to add a node\n• Drag from a port to wire nodes\n"
                                 + "• Drop a wire on empty space to add a matching node\n• F5 runs the workflow, Esc stops it\n"
                                 + "• Middle or right drag pans, the wheel zooms, F fits");
            none.getStyleClass().add("muted");
            none.setWrapText(true);
            content.getChildren().add(none);
            return;
        }
        var n = ws.graph.node(id);
        var def = n.definition();

        var title = new TextField(n.title());
        title.getStyleClass().add("heading");
        title.setOnAction(e -> rename(title));
        title.focusedProperty().addListener((o, was, is) -> {
            if (!is) rename(title);
        });
        refreshers.put("__title", () -> {
            if (!title.isFocused() && ws.graph.find(id).isPresent()) title.setText(ws.graph.node(id).title());
        });
        var type = new Label(def.category() + " · " + def.id());
        type.getStyleClass().add("muted");
        var desc = new Label(def.description());
        desc.setWrapText(true);
        content.getChildren().addAll(title, type, desc);

        if (!def.params().isEmpty()) {
            content.getChildren().add(section("SETTINGS"));
            for (var p : def.params()) content.getChildren().add(editor(n, p));
        }
        if (!def.inputs().isEmpty() || !def.outputs().isEmpty()) {
            content.getChildren().add(section("PORTS"));
            for (var p : def.inputs()) content.getChildren().add(portLine("in ", p.label(), p.type().name(), p.optional(), ws.graph.edgeInto(id, p.key()).isPresent()));
            for (var p : def.outputs()) content.getChildren().add(portLine("out", p.label(), p.type().name(), false, !ws.graph.edgesFrom(id).isEmpty()));
        }
        content.getChildren().add(section("LAST RESULT"));
        if (lastRun != null)
            lastRun.timings().stream().filter(t -> t.nodeId().equals(id)).findFirst().ifPresent(t -> {
                var l = new Label(t.status().name().toLowerCase() + " in " + String.format("%.1f ms", t.millis())
                                  + (t.message() != null ? " - " + t.message() : ""));
                l.getStyleClass().add("muted");
                l.setWrapText(true);
                content.getChildren().add(l);
            });
        var outs = ws.engine.lastOutputs(id);
        if (outs.isEmpty()) {
            var l = new Label("not run yet");
            l.getStyleClass().add("muted");
            content.getChildren().add(l);
        }
        for (var e : outs.entrySet()) {
            var label = new Label(e.getKey());
            label.setStyle("-fx-font-weight: bold;");
            var area = new TextArea(truncate(Bodies.describe(e.getValue()), 20_000));
            area.setEditable(false);
            area.setWrapText(true);
            area.setPrefRowCount(6);
            content.getChildren().addAll(label, area);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n… (" + (s.length() - max) + " more characters)";
    }

    private void rename(TextField f) {
        if (nodeId == null || ws.graph.find(nodeId).isEmpty()) return;
        if (!f.getText().equals(ws.graph.node(nodeId).title())) ws.stack.execute(new Commands.Rename(nodeId, f.getText()));
    }

    private static Label section(String s) {
        var l = new Label(s);
        l.getStyleClass().add("section");
        return l;
    }

    private static Node portLine(String dir, String label, String type, boolean optional, boolean connected) {
        var l = new Label(dir + "  " + label + "  ·  " + type + (optional ? " (optional)" : "") + (connected ? "" : "  - not connected"));
        l.getStyleClass().add("muted");
        return l;
    }

    private void set(String id, String key, Object value) {
        if (ws.graph.find(id).isEmpty()) return;
        if (Objects.equals(ws.graph.node(id).param(key), value)) return;
        try {
            ws.stack.execute(new Commands.SetParam(id, key, value));
        } catch (IllegalArgumentException ignored) {
            // invalid intermediate input (e.g. "-" while typing a number)
        }
    }

    private Node editor(NodeInstance n, ParamSpec p) {
        String id = n.id();
        var label = new Label(p.label());
        if (p.help() != null && !p.help().isBlank()) label.setTooltip(new Tooltip(p.help()));
        Control control;
        Runnable refresh;
        switch (p.kind()) {
            case MULTILINE -> {
                var a = new TextArea(String.valueOf(n.param(p.key())));
                a.setWrapText(true);
                a.setPrefRowCount(Math.min(10, Math.max(3, String.valueOf(n.param(p.key())).split("\n").length + 1)));
                a.textProperty().addListener((o, x, v) -> set(id, p.key(), v));
                control = a;
                refresh = () -> {
                    String v = String.valueOf(ws.graph.node(id).param(p.key()));
                    if (!a.isFocused() && !a.getText().equals(v)) a.setText(v);
                };
            }
            case INTEGER, NUMBER -> {
                var f = new TextField(DataTypes.formatNumber(((Number) n.param(p.key())).doubleValue()));
                f.textProperty().addListener((o, x, v) -> {
                    try {
                        set(id, p.key(), Double.parseDouble(v.trim()));
                    } catch (NumberFormatException ignored) {
                    }
                });
                control = f;
                refresh = () -> {
                    String v = DataTypes.formatNumber(((Number) ws.graph.node(id).param(p.key())).doubleValue());
                    if (!f.isFocused() && !f.getText().equals(v)) f.setText(v);
                };
                if (p.max() - p.min() <= 10_000 && p.max() > p.min()) {
                    var slider = new Slider(p.min(), p.max(), ((Number) n.param(p.key())).doubleValue());
                    slider.valueProperty().addListener((o, x, v) -> {
                        if (slider.isValueChanging() || slider.isFocused())
                            f.setText(DataTypes.formatNumber(p.kind() == ParamSpec.Kind.INTEGER ? Math.rint(v.doubleValue())
                                                                                              : Math.round(v.doubleValue() * 100) / 100.0));
                    });
                    HBox.setHgrow(slider, Priority.ALWAYS);
                    f.setPrefColumnCount(6);
                    var box = new HBox(8, slider, f);
                    Runnable base = refresh;
                    refreshers.put(p.key(), () -> {
                        base.run();
                        if (!slider.isValueChanging()) slider.setValue(((Number) ws.graph.node(id).param(p.key())).doubleValue());
                    });
                    return new VBox(3, label, box);
                }
            }
            case BOOLEAN -> {
                var c = new CheckBox(p.label());
                c.setSelected((Boolean) n.param(p.key()));
                c.selectedProperty().addListener((o, x, v) -> set(id, p.key(), v));
                if (p.help() != null) c.setTooltip(new Tooltip(p.help()));
                refreshers.put(p.key(), () -> c.setSelected((Boolean) ws.graph.node(id).param(p.key())));
                return c;
            }
            case CHOICE -> {
                var c = new ComboBox<String>();
                c.getItems().setAll(p.choices());
                c.setValue(String.valueOf(n.param(p.key())));
                c.setMaxWidth(Double.MAX_VALUE);
                c.valueProperty().addListener((o, x, v) -> {
                    if (v != null) set(id, p.key(), v);
                });
                control = c;
                refresh = () -> c.setValue(String.valueOf(ws.graph.node(id).param(p.key())));
            }
            case FILE, FOLDER -> {
                var f = new TextField(String.valueOf(n.param(p.key())));
                f.textProperty().addListener((o, x, v) -> set(id, p.key(), v));
                var browse = new Button("…");
                browse.setOnAction(e -> {
                    File chosen;
                    if (p.kind() == ParamSpec.Kind.FILE) {
                        var fc = new FileChooser();
                        fc.setTitle(p.label());
                        chosen = fc.showOpenDialog(getScene().getWindow());
                    } else {
                        var dc = new DirectoryChooser();
                        dc.setTitle(p.label());
                        chosen = dc.showDialog(getScene().getWindow());
                    }
                    if (chosen != null) f.setText(chosen.getAbsolutePath());
                });
                HBox.setHgrow(f, Priority.ALWAYS);
                refreshers.put(p.key(), () -> {
                    String v = String.valueOf(ws.graph.node(id).param(p.key()));
                    if (!f.isFocused() && !f.getText().equals(v)) f.setText(v);
                });
                return new VBox(3, label, new HBox(4, f, browse));
            }
            default -> {
                var f = new TextField(String.valueOf(n.param(p.key())));
                f.textProperty().addListener((o, x, v) -> set(id, p.key(), v));
                control = f;
                refresh = () -> {
                    String v = String.valueOf(ws.graph.node(id).param(p.key()));
                    if (!f.isFocused() && !f.getText().equals(v)) f.setText(v);
                };
            }
        }
        if (p.help() != null && !p.help().isBlank()) control.setTooltip(new Tooltip(p.help()));
        control.setMaxWidth(Double.MAX_VALUE);
        refreshers.put(p.key(), refresh);
        return new VBox(3, label, control);
    }
}
