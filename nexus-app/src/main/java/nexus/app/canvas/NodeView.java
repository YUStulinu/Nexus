package nexus.app.canvas;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javafx.css.PseudoClass;
import javafx.geometry.Point2D;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import nexus.app.views.Bodies;
import nexus.app.views.NodeBody;
import nexus.core.exec.NodeStatus;
import nexus.core.graph.NodeInstance;
import nexus.core.graph.PortSpec;

/**
 * A node on the canvas. Layout uses fixed metrics so port positions are known geometrically (the
 * wires never wait for a layout pass):
 * <pre>
 *  ┌──────────────────────────┐  header (title, status)        HEADER
 *  │▔▔▔▔▔ progress ▔▔▔▔▔▔▔▔▔▔▔│
 *  ● input 1        output 1 ●  one row per port pair          ROW each
 *  ● input 2
 *  │ body (live view)          │
 *  └──────────────────────────┘
 * </pre>
 */
public final class NodeView extends Region {
    public static final double HEADER = 30, ROW = 22, PAD = 8, PORT_R = 6;
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    /** A port, identified for hit testing and wiring. */
    public record PortRef(String nodeId, String key, boolean input, PortSpec spec) {
    }

    private final NodeInstance node;
    private final double width;
    private final Pane header = new Pane();
    private final Label title = new Label();
    private final Label status = new Label();
    private final Rectangle progressTrack = new Rectangle();
    private final Rectangle progressFill = new Rectangle();
    private final Map<String, Circle> inputs = new LinkedHashMap<>();
    private final Map<String, Circle> outputs = new LinkedHashMap<>();
    private final java.util.List<Label> portLabels = new java.util.ArrayList<>();
    private final NodeBody body;
    private NodeStatus current = NodeStatus.IDLE;
    private boolean selected;

    public NodeView(NodeInstance node, nexus.app.views.BodyContext bodyContext) {
        this.node = node;
        getStyleClass().add("node-view");
        var def = node.definition();
        width = switch (def.view()) {
            case "preview", "llm" -> 340;
            case "chat-session" -> 380;
            case "duel" -> 560;
            case "search-hits" -> 420;
            case "game-board" -> 460;
            case "training" -> 520;
            case "arena" -> 400;
            case "table", "list" -> 290;
            default -> 236;
        };
        header.getStyleClass().add("header");
        header.setBackground(new Background(new BackgroundFill(Colors.categoryColor(def.category()), new CornerRadii(8, 8, 0, 0, false), null)));
        title.getStyleClass().add("title");
        status.getStyleClass().add("status");
        header.getChildren().addAll(title, status);
        progressTrack.getStyleClass().add("progress-track");
        progressFill.setFill(Color.web("#4dabf7"));
        progressFill.setVisible(false);
        getChildren().addAll(header, progressTrack, progressFill);

        int row = 0;
        for (var p : def.inputs()) addPort(p, true, row++);
        row = 0;
        for (var p : def.outputs()) addPort(p, false, row++);

        body = Bodies.create(def.view());
        body.bind(bodyContext);
        getChildren().add(body);
        setTitle(node.title());
        relocate(node.x(), node.y());
        Tooltip.install(header, new Tooltip(def.title() + " (" + def.id() + ")\n" + def.description()));
    }

    private void addPort(PortSpec p, boolean input, int row) {
        var c = new Circle(PORT_R, Color.web(p.type().color()));
        c.getStyleClass().add("port");
        c.getProperties().put(PortRef.class, new PortRef(node.id(), p.key(), input, p));
        Tooltip.install(c, new Tooltip(p.label() + " : " + p.type().name() + (p.optional() ? " (optional)" : "")));
        var label = new Label(p.label());
        label.getStyleClass().add("port-label");
        label.setMouseTransparent(true);
        double y = portY(row);
        c.setCenterX(input ? 0 : width);
        c.setCenterY(y);
        label.setLayoutY(y - 8);
        if (input) label.setLayoutX(PORT_R + 6);
        else label.layoutXProperty().bind(label.widthProperty().negate().add(width - PORT_R - 6));
        (input ? inputs : outputs).put(p.key(), c);
        portLabels.add(label);
        getChildren().addAll(label, c);
    }

    private static double portY(int row) {
        return HEADER + 6 + row * ROW + ROW / 2;
    }

    private int rows() {
        return Math.max(node.definition().inputs().size(), node.definition().outputs().size());
    }

    private double bodyTop() {
        return HEADER + 8 + rows() * ROW + (rows() > 0 ? 4 : 0);
    }

    public double nodeWidth() {
        return width;
    }

    public double nodeHeight() {
        return bodyTop() + body.preferredHeight() + PAD;
    }

    @Override
    protected double computePrefWidth(double h) {
        return width;
    }

    @Override
    protected double computePrefHeight(double w) {
        return nodeHeight();
    }

    @Override
    protected void layoutChildren() {
        header.resizeRelocate(0, 0, width, HEADER);
        title.resizeRelocate(10, 0, width - 90, HEADER);
        double sw = status.prefWidth(-1);
        status.resizeRelocate(width - sw - 10, 0, sw, HEADER);
        progressTrack.setX(0);
        progressTrack.setY(HEADER);
        progressTrack.setWidth(width);
        progressTrack.setHeight(3);
        progressFill.setX(0);
        progressFill.setY(HEADER);
        progressFill.setHeight(3);
        for (var l : portLabels) l.autosize();
        body.resizeRelocate(PAD, bodyTop(), width - 2 * PAD, body.preferredHeight());
    }

    // ---- identity and geometry -------------------------------------------------------------------------

    public NodeInstance node() {
        return node;
    }

    public String nodeId() {
        return node.id();
    }

    public NodeBody body() {
        return body;
    }

    /** Port centre in the canvas's world coordinates. */
    public Point2D portCenter(String key, boolean input) {
        var c = (input ? inputs : outputs).get(key);
        if (c == null) return new Point2D(getLayoutX(), getLayoutY());
        return new Point2D(getLayoutX() + c.getCenterX(), getLayoutY() + c.getCenterY());
    }

    /** The port within {@code radius} of a world point, if any. */
    public Optional<PortRef> portNear(double wx, double wy, double radius) {
        for (var map : java.util.List.of(inputs, outputs))
            for (var c : map.values()) {
                double dx = getLayoutX() + c.getCenterX() - wx, dy = getLayoutY() + c.getCenterY() - wy;
                if (dx * dx + dy * dy <= radius * radius) return Optional.of((PortRef) c.getProperties().get(PortRef.class));
            }
        return Optional.empty();
    }

    public boolean containsWorld(double wx, double wy) {
        return wx >= getLayoutX() && wx <= getLayoutX() + width && wy >= getLayoutY() && wy <= getLayoutY() + nodeHeight();
    }

    /** Highlights the ports a wire of the given type could connect to (null clears). */
    public void highlightCompatible(nexus.core.types.DataType type, boolean wantInputs) {
        var map = wantInputs ? inputs : outputs;
        for (var e : map.entrySet()) {
            var ref = (PortRef) e.getValue().getProperties().get(PortRef.class);
            boolean ok = type == null || (wantInputs ? nexus.core.types.DataTypes.canConnect(type, ref.spec().type())
                                                     : nexus.core.types.DataTypes.canConnect(ref.spec().type(), type));
            e.getValue().setOpacity(ok ? 1 : 0.25);
            e.getValue().setRadius(ok && type != null ? PORT_R + 1.5 : PORT_R);
        }
        var other = wantInputs ? outputs : inputs;
        for (var c : other.values()) c.setOpacity(type == null ? 1 : 0.25);
    }

    // ---- state ---------------------------------------------------------------------------------------------

    public void setTitle(String t) {
        title.setText(t);
    }

    public void setSelected(boolean s) {
        selected = s;
        pseudoClassStateChanged(SELECTED, s);
        if (s) toFront();
    }

    public boolean isSelected() {
        return selected;
    }

    public NodeStatus status() {
        return current;
    }

    public void setStatus(NodeStatus s, String message) {
        current = s;
        String text = switch (s) {
            case IDLE -> "";
            case QUEUED -> "◷ queued";
            case RUNNING -> "● running";
            case DONE -> "✓ done";
            case CACHED -> "✓ cached";
            case ERROR -> "✕ error";
            case SKIPPED -> "– skipped";
            case CANCELLED -> "■ stopped";
        };
        status.setText(text);
        status.setStyle("-fx-text-fill: " + switch (s) {
            case DONE -> "#b2f2bb";
            case CACHED -> "#96f2d7";
            case ERROR -> "#ffc9c9";
            case RUNNING -> "#d0ebff";
            default -> "rgba(255,255,255,0.75)";
        });
        if (s == NodeStatus.ERROR && message != null) Tooltip.install(status, new Tooltip(message));
        if (s == NodeStatus.RUNNING) {
            progressFill.setVisible(true);
            progressFill.setFill(Color.web("#4dabf7"));
            progressFill.setWidth(width * 0.04);
        } else if (s.isFinished()) {
            progressFill.setVisible(true);
            progressFill.setWidth(width);
            progressFill.setFill(Color.web(switch (s) {
                case DONE -> "#40c057";
                case CACHED -> "#20c997";
                case ERROR -> "#fa5252";
                default -> "#868e96";
            }));
        } else {
            progressFill.setVisible(false);
        }
        body.status(s, message);
        requestLayout();
    }

    /** {@code fraction} in [0, 1], or negative for an indeterminate pulse. */
    public void setProgress(double fraction, String message) {
        progressFill.setVisible(true);
        if (fraction < 0) {
            double t = (System.nanoTime() / 1e9) % 1.2 / 1.2;
            progressFill.setWidth(width * (0.15 + 0.85 * t));
        } else {
            progressFill.setWidth(width * Math.max(0.02, Math.min(1, fraction)));
        }
        if (message != null && !message.isBlank()) status.setText("● " + message);
        requestLayout();
    }
}
