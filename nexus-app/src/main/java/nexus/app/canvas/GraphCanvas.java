package nexus.app.canvas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import javafx.animation.AnimationTimer;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;
import nexus.app.Workspace;
import nexus.core.edit.Command;
import nexus.core.edit.Commands;
import nexus.core.exec.NodeStatus;
import nexus.core.graph.Edge;
import nexus.core.graph.GraphListener;
import nexus.core.graph.NodeDefinition;
import nexus.core.graph.NodeInstance;
import nexus.core.io.WorkflowFile;
import nexus.core.types.DataType;

/**
 * The node editor: an infinite, zoomable canvas showing the graph of a {@link Workspace}.
 *
 * <p>Nodes and wires live in a "world" group under a translate + scale transform (pan and zoom); a
 * dot grid is drawn underneath on a canvas in screen space. Every edit goes through the workspace's
 * command stack, so it can be undone, and the canvas itself only reacts to graph events - undo, redo,
 * loading a file and editing all update the view the same way.
 */
public final class GraphCanvas extends Pane {
    public static final String DRAG_PREFIX = "nexus:node:";
    private static final double MIN_ZOOM = 0.2, MAX_ZOOM = 2.5;

    private final Workspace ws;
    private final Canvas grid = new Canvas();
    private final Group world = new Group();
    private final Group edgeLayer = new Group();
    private final Group nodeLayer = new Group();
    private final Group overlay = new Group();      // world-space overlay (the wire being dragged)
    private final Translate pan = new Translate();
    private final Scale zoom = new Scale(1, 1, 0, 0);
    private final Rectangle marquee = new Rectangle();
    private final Label hint = new Label("Drag a node from the palette, or double-click the canvas to add one");

    private final Map<String, NodeView> views = new LinkedHashMap<>();
    private final Map<Edge, EdgeView> edgeViews = new LinkedHashMap<>();
    private final Set<String> selection = new LinkedHashSet<>();
    private EdgeView selectedEdge;
    private final ReadOnlyObjectWrapper<String> primary = new ReadOnlyObjectWrapper<>();
    private final QuickAdd quickAdd;

    // interaction state
    private enum Mode { NONE, PAN, MOVE, WIRE, MARQUEE }
    private Mode mode = Mode.NONE;
    private double pressX, pressY, lastX, lastY;
    private final Map<String, double[]> moveStart = new HashMap<>();
    private NodeView.PortRef wireFrom;       // the port the dragged wire is anchored at
    private boolean wireFromInput;           // dragging backwards from an input
    private final CubicCurve tempWire = new CubicCurve();
    private boolean spaceDown;
    private Point2D lastMouseWorld = new Point2D(0, 0);
    private final long startNanos = System.nanoTime();

    public GraphCanvas(Workspace ws) {
        this.ws = ws;
        this.quickAdd = new QuickAdd(ws.registry);
        getStyleClass().add("graph-canvas");
        setFocusTraversable(true);
        world.getTransforms().addAll(pan, zoom);
        world.getChildren().addAll(edgeLayer, nodeLayer, overlay);
        marquee.getStyleClass().add("marquee");
        marquee.setVisible(false);
        marquee.setManaged(false);
        tempWire.getStyleClass().add("edge");
        tempWire.setVisible(false);
        tempWire.setMouseTransparent(true);
        tempWire.setFill(Color.TRANSPARENT);
        overlay.getChildren().add(tempWire);
        hint.getStyleClass().add("hint-label");
        hint.setMouseTransparent(true);
        getChildren().addAll(grid, world, marquee, hint);

        // clip to the visible area
        var clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);
        widthProperty().addListener((o, a, b) -> drawGrid());
        heightProperty().addListener((o, a, b) -> drawGrid());

        ws.graph.addListener(new GraphListener() {
            @Override
            public void nodeAdded(NodeInstance n) {
                addView(n);
            }

            @Override
            public void nodeRemoved(NodeInstance n) {
                removeView(n.id());
            }

            @Override
            public void nodeMoved(NodeInstance n) {
                var v = views.get(n.id());
                if (v != null) {
                    v.relocate(n.x(), n.y());
                    updateEdgesOf(n.id());
                }
            }

            @Override
            public void nodeChanged(NodeInstance n, String key) {
                var v = views.get(n.id());
                if (v != null) v.setTitle(n.title());
            }

            @Override
            public void edgeAdded(Edge e) {
                addEdgeView(e);
            }

            @Override
            public void edgeRemoved(Edge e) {
                var ev = edgeViews.remove(e);
                if (ev != null) {
                    edgeLayer.getChildren().remove(ev);
                    if (ev == selectedEdge) selectedEdge = null;
                }
            }
        });
        ws.onLoaded(view -> {
            setView(view);
            clearSelection();
        });

        addEventHandler(MouseEvent.MOUSE_PRESSED, this::onPressed);
        addEventHandler(MouseEvent.MOUSE_DRAGGED, this::onDragged);
        addEventHandler(MouseEvent.MOUSE_RELEASED, this::onReleased);
        addEventHandler(MouseEvent.MOUSE_MOVED, e -> lastMouseWorld = toWorld(e.getX(), e.getY()));
        addEventHandler(MouseEvent.MOUSE_CLICKED, this::onClicked);
        addEventHandler(ScrollEvent.SCROLL, this::onScroll);
        addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.SPACE && !(e.getTarget() instanceof javafx.scene.control.TextInputControl)) spaceDown = true;
        });
        addEventHandler(KeyEvent.KEY_RELEASED, e -> {
            if (e.getCode() == KeyCode.SPACE) spaceDown = false;
        });
        setOnDragOver(this::onDragOver);
        setOnDragDropped(this::onDragDropped);

        new AnimationTimer() {
            @Override
            public void handle(long now) {
                double t = (now - startNanos) / 1e9;
                for (var ev : edgeViews.values()) ev.tick(t);
                for (var v : views.values()) if (v.status() == NodeStatus.RUNNING && v.isVisible()) v.requestLayout();
            }
        }.start();
    }

    @Override
    protected void layoutChildren() {
        grid.setWidth(getWidth());
        grid.setHeight(getHeight());
        hint.setVisible(views.isEmpty());
        hint.autosize();
        hint.relocate((getWidth() - hint.getWidth()) / 2, (getHeight() - hint.getHeight()) / 2);
    }

    // ---- view state --------------------------------------------------------------------------------------

    public WorkflowFile.View view() {
        return new WorkflowFile.View(pan.getX(), pan.getY(), zoom.getX());
    }

    public void setView(WorkflowFile.View v) {
        pan.setX(v.x());
        pan.setY(v.y());
        zoom.setX(v.zoom());
        zoom.setY(v.zoom());
        drawGrid();
    }

    public double zoomLevel() {
        return zoom.getX();
    }

    public Point2D toWorld(double sx, double sy) {
        return new Point2D((sx - pan.getX()) / zoom.getX(), (sy - pan.getY()) / zoom.getY());
    }

    /** Pans and zooms so every node is visible. */
    public void fitView() {
        if (views.isEmpty()) {
            setView(WorkflowFile.View.DEFAULT);
            return;
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (var v : views.values()) {
            minX = Math.min(minX, v.getLayoutX());
            minY = Math.min(minY, v.getLayoutY());
            maxX = Math.max(maxX, v.getLayoutX() + v.nodeWidth());
            maxY = Math.max(maxY, v.getLayoutY() + v.nodeHeight());
        }
        double w = Math.max(getWidth(), 400), h = Math.max(getHeight(), 300), margin = 60;
        double z = Math.min(1.25, Math.min((w - 2 * margin) / (maxX - minX), (h - 2 * margin) / (maxY - minY)));
        z = Math.max(MIN_ZOOM, z);
        setView(new WorkflowFile.View((w - (maxX - minX) * z) / 2 - minX * z, (h - (maxY - minY) * z) / 2 - minY * z, z));
    }

    private void drawGrid() {
        var g = grid.getGraphicsContext2D();
        double w = grid.getWidth(), h = grid.getHeight();
        g.setFill(Color.web("#15171c"));
        g.fillRect(0, 0, w, h);
        double step = 24 * zoom.getX();
        if (step < 8) step *= 4;
        double ox = ((pan.getX() % step) + step) % step, oy = ((pan.getY() % step) + step) % step;
        g.setFill(Color.web("#2a2e38"));
        double r = Math.max(1, 1.4 * Math.min(1, zoom.getX()));
        for (double x = ox; x < w; x += step)
            for (double y = oy; y < h; y += step) g.fillOval(x - r / 2, y - r / 2, r, r);
    }

    // ---- views ----------------------------------------------------------------------------------------------

    private void addView(NodeInstance n) {
        String id = n.id();
        var v = new NodeView(n, new nexus.app.views.BodyContext() {
            @Override
            public String nodeId() {
                return id;
            }

            @Override
            public nexus.core.exec.Services services() {
                return ws.services;
            }

            @Override
            public Object param(String key) {
                return ws.graph.node(id).param(key);
            }

            @Override
            public Map<String, Object> lastOutputs() {
                return ws.engine.lastOutputs(id);
            }

            @Override
            public void setParam(String key, Object value) {
                ws.stack.execute(new Commands.SetParam(id, key, value));
            }
        });
        views.put(n.id(), v);
        nodeLayer.getChildren().add(v);
        v.applyCss();
        v.autosize();
        requestLayout();
    }

    private void removeView(String id) {
        var v = views.remove(id);
        if (v != null) nodeLayer.getChildren().remove(v);
        if (selection.remove(id)) fireSelection();
        requestLayout();
    }

    private void addEdgeView(Edge e) {
        var from = ws.graph.node(e.fromNode()).definition().output(e.fromPort());
        var ev = new EdgeView(e, from.type());
        edgeViews.put(e, ev);
        edgeLayer.getChildren().add(ev);
        updateEdge(ev);
    }

    private void updateEdge(EdgeView ev) {
        var a = views.get(ev.edge().fromNode());
        var b = views.get(ev.edge().toNode());
        if (a == null || b == null) return;
        ev.update(a.portCenter(ev.edge().fromPort(), false), b.portCenter(ev.edge().toPort(), true));
    }

    private void updateEdgesOf(String nodeId) {
        for (var ev : edgeViews.values())
            if (ev.edge().fromNode().equals(nodeId) || ev.edge().toNode().equals(nodeId)) updateEdge(ev);
    }

    public Optional<NodeView> view(String nodeId) {
        return Optional.ofNullable(views.get(nodeId));
    }

    // ---- selection ------------------------------------------------------------------------------------------

    /** The node shown in the inspector (the last one clicked), or null. */
    public ReadOnlyObjectProperty<String> primarySelection() {
        return primary.getReadOnlyProperty();
    }

    public Set<String> selection() {
        return Set.copyOf(selection);
    }

    public void select(String id, boolean add) {
        if (!add) clearSelectionQuietly();
        selection.add(id);
        var v = views.get(id);
        if (v != null) v.setSelected(true);
        fireSelection();
    }

    public void selectAll() {
        for (var id : views.keySet()) {
            selection.add(id);
            views.get(id).setSelected(true);
        }
        fireSelection();
    }

    public void clearSelection() {
        clearSelectionQuietly();
        fireSelection();
    }

    private void clearSelectionQuietly() {
        for (var id : selection) {
            var v = views.get(id);
            if (v != null) v.setSelected(false);
        }
        selection.clear();
        if (selectedEdge != null) {
            selectedEdge.setSelected(false);
            selectedEdge = null;
        }
    }

    private void fireSelection() {
        String p = selection.isEmpty() ? null : selection.stream().reduce((a, b) -> b).orElse(null);
        primary.set(p);
    }

    // ---- editing operations ------------------------------------------------------------------------------

    public void deleteSelection() {
        if (selectedEdge != null) {
            ws.stack.execute(new Commands.Disconnect(selectedEdge.edge()));
            selectedEdge = null;
            return;
        }
        if (selection.isEmpty()) return;
        ws.stack.execute(new Commands.RemoveNodes(List.copyOf(selection)));
        selection.clear();
        fireSelection();
    }

    public String addNode(NodeDefinition def, double wx, double wy) {
        var cmd = new Commands.AddNode(def, snap(wx), snap(wy));
        ws.stack.execute(cmd);
        select(cmd.id(), false);
        return cmd.id();
    }

    private static double snap(double v) {
        return Math.round(v / 8) * 8;
    }

    /** Copies the selected nodes (and the wires between them) to the clipboard as workflow JSON. */
    public void copySelection() {
        if (selection.isEmpty()) return;
        var nodes = selection.stream().map(ws.graph::node).toList();
        var edges = ws.graph.edges().stream()
                            .filter(e -> selection.contains(e.fromNode()) && selection.contains(e.toNode())).toList();
        var content = new ClipboardContent();
        content.putString(WorkflowFile.toJson("clipboard", nodes, edges, null));
        Clipboard.getSystemClipboard().setContent(content);
    }

    public void cutSelection() {
        copySelection();
        deleteSelection();
    }

    /** Pastes clipboard workflow JSON near the mouse, with fresh ids, as one undo step. */
    public void paste() {
        String s = Clipboard.getSystemClipboard().getString();
        if (s == null || !s.contains(WorkflowFile.FORMAT)) return;
        try {
            var c = WorkflowFile.parse(s);
            if (!WorkflowFile.missingTypes(c, ws.registry).isEmpty() || c.nodes().isEmpty()) return;
            double minX = c.nodes().stream().mapToDouble(WorkflowFile.NodeEntry::x).min().orElse(0);
            double minY = c.nodes().stream().mapToDouble(WorkflowFile.NodeEntry::y).min().orElse(0);
            var ids = new HashMap<String, String>();
            var commands = new ArrayList<Command>();
            for (var n : c.nodes()) {
                String id = ws.graph.newId();
                ids.put(n.id(), id);
                commands.add(new Commands.AddNode(ws.registry.get(n.type()), snap(lastMouseWorld.getX() + n.x() - minX),
                                                  snap(lastMouseWorld.getY() + n.y() - minY), n.params(), id));
            }
            for (var e : c.edges())
                if (ids.containsKey(e.fromNode()) && ids.containsKey(e.toNode()))
                    commands.add(new Commands.Connect(new Edge(ids.get(e.fromNode()), e.fromPort(), ids.get(e.toNode()), e.toPort())));
            ws.stack.execute(new Commands.Batch("paste", commands));
            clearSelectionQuietly();
            for (var id : ids.values()) select(id, true);
        } catch (Exception ex) {
            // not a workflow: ignore
        }
    }

    public void duplicateSelection() {
        if (selection.isEmpty()) return;
        var first = views.get(selection.iterator().next());
        lastMouseWorld = new Point2D(first.getLayoutX() + 40, first.getLayoutY() + 40);
        copySelection();
        paste();
    }

    // ---- mouse ------------------------------------------------------------------------------------------------

    private Optional<NodeView> nodeViewOf(Node target) {
        for (Node n = target; n != null && n != this; n = n.getParent()) if (n instanceof NodeView v) return Optional.of(v);
        return Optional.empty();
    }

    private boolean insideInteractiveBody(Node target, NodeView v) {
        if (!v.body().interactive()) return false;
        for (Node n = target; n != null && n != v; n = n.getParent()) if (n == v.body()) return true;
        return false;
    }

    private void onPressed(MouseEvent e) {
        requestFocus();
        quickAdd.hide();
        pressX = lastX = e.getX();
        pressY = lastY = e.getY();
        var target = e.getPickResult().getIntersectedNode();
        boolean panGesture = e.getButton() == MouseButton.MIDDLE || e.getButton() == MouseButton.SECONDARY
                             || (e.getButton() == MouseButton.PRIMARY && spaceDown);
        if (panGesture) {
            mode = Mode.PAN;
            return;
        }
        if (e.getButton() != MouseButton.PRIMARY) return;

        // a port: start a wire
        if (target != null && target.getProperties().get(NodeView.PortRef.class) instanceof NodeView.PortRef ref) {
            startWire(ref);
            e.consume();
            return;
        }
        // an edge
        if (target != null && target.getProperties().get(EdgeView.class) instanceof EdgeView ev) {
            clearSelectionQuietly();
            fireSelection();
            selectedEdge = ev;
            ev.setSelected(true);
            mode = Mode.NONE;
            return;
        }
        // a node
        var nv = target == null ? Optional.<NodeView>empty() : nodeViewOf(target);
        if (nv.isPresent()) {
            var v = nv.get();
            if (insideInteractiveBody(target, v)) {
                if (!selection.contains(v.nodeId())) select(v.nodeId(), false);
                mode = Mode.NONE;
                return;
            }
            boolean additive = e.isShiftDown() || e.isShortcutDown();
            if (additive && selection.contains(v.nodeId())) {
                selection.remove(v.nodeId());
                v.setSelected(false);
                fireSelection();
                mode = Mode.NONE;
                return;
            }
            if (!selection.contains(v.nodeId())) select(v.nodeId(), additive);
            else {
                v.toFront();
                primary.set(v.nodeId());
            }
            mode = Mode.MOVE;
            moveStart.clear();
            for (var id : selection) {
                var n = ws.graph.node(id);
                moveStart.put(id, new double[]{n.x(), n.y()});
            }
            return;
        }
        // empty canvas: marquee
        if (!e.isShiftDown()) clearSelection();
        mode = Mode.MARQUEE;
        marquee.setX(pressX);
        marquee.setY(pressY);
        marquee.setWidth(0);
        marquee.setHeight(0);
        marquee.setVisible(true);
    }

    private void onDragged(MouseEvent e) {
        double dx = e.getX() - lastX, dy = e.getY() - lastY;
        lastX = e.getX();
        lastY = e.getY();
        lastMouseWorld = toWorld(e.getX(), e.getY());
        switch (mode) {
            case PAN -> {
                pan.setX(pan.getX() + dx);
                pan.setY(pan.getY() + dy);
                drawGrid();
            }
            case MOVE -> {
                double wx = (e.getX() - pressX) / zoom.getX(), wy = (e.getY() - pressY) / zoom.getY();
                for (var id : selection) {
                    var start = moveStart.get(id);
                    var v = views.get(id);
                    if (start == null || v == null) continue;
                    v.relocate(start[0] + wx, start[1] + wy);
                    updateEdgesOf(id);
                }
            }
            case WIRE -> {
                var p = toWorld(e.getX(), e.getY());
                var anchor = views.get(wireFrom.nodeId()).portCenter(wireFrom.key(), wireFrom.input());
                if (wireFromInput) EdgeView.set(tempWire, p, anchor);
                else EdgeView.set(tempWire, anchor, p);
            }
            case MARQUEE -> {
                marquee.setX(Math.min(pressX, e.getX()));
                marquee.setY(Math.min(pressY, e.getY()));
                marquee.setWidth(Math.abs(e.getX() - pressX));
                marquee.setHeight(Math.abs(e.getY() - pressY));
            }
            default -> {
            }
        }
    }

    private void onReleased(MouseEvent e) {
        switch (mode) {
            case MOVE -> {
                var moves = new LinkedHashMap<String, double[]>();
                for (var id : selection) {
                    var start = moveStart.get(id);
                    var v = views.get(id);
                    if (start == null || v == null) continue;
                    double nx = snap(v.getLayoutX()), ny = snap(v.getLayoutY());
                    if (nx != start[0] || ny != start[1]) moves.put(id, new double[]{start[0], start[1], nx, ny});
                }
                if (!moves.isEmpty()) {
                    ws.stack.breakMerge();
                    ws.stack.execute(new Commands.MoveNodes(moves));
                }
            }
            case WIRE -> finishWire(e);
            case MARQUEE -> {
                marquee.setVisible(false);
                var a = toWorld(marquee.getX(), marquee.getY());
                var b = toWorld(marquee.getX() + marquee.getWidth(), marquee.getY() + marquee.getHeight());
                for (var v : views.values()) {
                    boolean inside = v.getLayoutX() < b.getX() && v.getLayoutX() + v.nodeWidth() > a.getX()
                                     && v.getLayoutY() < b.getY() && v.getLayoutY() + v.nodeHeight() > a.getY();
                    if (inside) {
                        selection.add(v.nodeId());
                        v.setSelected(true);
                    }
                }
                fireSelection();
            }
            case PAN -> {
                boolean click = Math.abs(e.getX() - pressX) < 4 && Math.abs(e.getY() - pressY) < 4;
                if (click && e.getButton() == MouseButton.SECONDARY) showContextMenu(e);
            }
            default -> {
            }
        }
        mode = Mode.NONE;
    }

    private void onClicked(MouseEvent e) {
        if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
            var target = e.getPickResult().getIntersectedNode();
            if (target == this || target == grid || target == hint) openQuickAdd(e.getX(), e.getY(), null);
        }
    }

    private void onScroll(ScrollEvent e) {
        var target = e.getPickResult().getIntersectedNode();
        var nv = target == null ? Optional.<NodeView>empty() : nodeViewOf(target);
        if (nv.isPresent() && insideInteractiveBody(target, nv.get()) && !e.isControlDown()) return;   // let lists scroll
        double factor = Math.pow(1.0015, e.getDeltaY());
        zoomAt(e.getX(), e.getY(), factor);
        e.consume();
    }

    public void zoomAt(double sx, double sy, double factor) {
        double z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom.getX() * factor));
        double f = z / zoom.getX();
        pan.setX(sx - (sx - pan.getX()) * f);
        pan.setY(sy - (sy - pan.getY()) * f);
        zoom.setX(z);
        zoom.setY(z);
        drawGrid();
    }

    // ---- wires ---------------------------------------------------------------------------------------------

    private void startWire(NodeView.PortRef ref) {
        mode = Mode.WIRE;
        if (ref.input()) {
            // Dragging from a connected input picks its wire up (to move or delete it).
            var existing = ws.graph.edgeInto(ref.nodeId(), ref.key());
            if (existing.isPresent()) {
                var e = existing.get();
                ws.stack.execute(new Commands.Disconnect(e));
                var src = ws.graph.node(e.fromNode()).definition().output(e.fromPort());
                wireFrom = new NodeView.PortRef(e.fromNode(), e.fromPort(), false, src);
                wireFromInput = false;
            } else {
                wireFrom = ref;
                wireFromInput = true;
            }
        } else {
            wireFrom = ref;
            wireFromInput = false;
        }
        tempWire.setStroke(Color.web(wireFrom.spec().type().color()));
        tempWire.setStrokeWidth(2.4);
        var anchor = views.get(wireFrom.nodeId()).portCenter(wireFrom.key(), wireFrom.input());
        EdgeView.set(tempWire, anchor, anchor);
        tempWire.setVisible(true);
        DataType t = wireFrom.spec().type();
        for (var v : views.values()) v.highlightCompatible(t, !wireFromInput);
    }

    private void finishWire(MouseEvent e) {
        tempWire.setVisible(false);
        for (var v : views.values()) v.highlightCompatible(null, true);
        var p = toWorld(e.getX(), e.getY());
        Optional<NodeView.PortRef> target = Optional.empty();
        for (var v : views.values()) {
            target = v.portNear(p.getX(), p.getY(), 14 / Math.min(1, zoom.getX()));
            if (target.isPresent()) break;
        }
        if (target.isPresent()) {
            var t = target.get();
            if (t.input() == wireFromInput) return;
            var edge = wireFromInput ? new Edge(t.nodeId(), t.key(), wireFrom.nodeId(), wireFrom.key())
                                     : new Edge(wireFrom.nodeId(), wireFrom.key(), t.nodeId(), t.key());
            if (ws.graph.checkEdge(edge).isEmpty()) ws.stack.execute(new Commands.Connect(edge));
            return;
        }
        // Dropped on empty space: offer the nodes that can take (or give) this type, and wire the new node in.
        var from = wireFrom;
        boolean backwards = wireFromInput;
        if (Math.hypot(e.getX() - pressX, e.getY() - pressY) < 6) return;
        openQuickAdd(e.getX(), e.getY(), new QuickAdd.Filter(from.spec().type(), !backwards), def -> {
            var add = new Commands.AddNode(def, snap(p.getX()), snap(p.getY() - 20));
            ws.stack.execute(add);
            var created = ws.graph.node(add.id());
            Optional<Edge> edge = Optional.empty();
            if (!backwards) {
                for (var in : created.definition().inputs())
                    if (nexus.core.types.DataTypes.canConnect(from.spec().type(), in.type())) {
                        edge = Optional.of(new Edge(from.nodeId(), from.key(), created.id(), in.key()));
                        break;
                    }
            } else {
                for (var out : created.definition().outputs())
                    if (nexus.core.types.DataTypes.canConnect(out.type(), from.spec().type())) {
                        edge = Optional.of(new Edge(created.id(), out.key(), from.nodeId(), from.key()));
                        break;
                    }
            }
            edge.filter(ed -> ws.graph.checkEdge(ed).isEmpty()).ifPresent(ed -> ws.stack.execute(new Commands.Connect(ed)));
            select(created.id(), false);
        });
    }

    // ---- quick add, context menu, drag and drop ------------------------------------------------------------

    public void openQuickAddAtMouse() {
        var p = new Point2D(lastMouseWorld.getX() * zoom.getX() + pan.getX(), lastMouseWorld.getY() * zoom.getY() + pan.getY());
        openQuickAdd(p.getX(), p.getY(), null);
    }

    private void openQuickAdd(double sx, double sy, QuickAdd.Filter filter) {
        var w = toWorld(sx, sy);
        openQuickAdd(sx, sy, filter, def -> addNode(def, w.getX(), w.getY()));
    }

    private void openQuickAdd(double sx, double sy, QuickAdd.Filter filter, Consumer<NodeDefinition> onPick) {
        var screen = localToScreen(sx, sy);
        if (screen == null) return;
        quickAdd.show(this, screen.getX(), screen.getY(), filter, onPick);
    }

    private void showContextMenu(MouseEvent e) {
        var target = e.getPickResult().getIntersectedNode();
        var nv = target == null ? Optional.<NodeView>empty() : nodeViewOf(target);
        var menu = new ContextMenu();
        if (nv.isPresent()) {
            var id = nv.get().nodeId();
            if (!selection.contains(id)) select(id, false);
            var run = new MenuItem("Run up to this node");
            run.setOnAction(a -> ws.run(List.of(id)));
            var dup = new MenuItem("Duplicate");
            dup.setOnAction(a -> duplicateSelection());
            var copy = new MenuItem("Copy");
            copy.setOnAction(a -> copySelection());
            var refresh = new MenuItem("Forget cached result");
            refresh.setOnAction(a -> ws.engine.invalidate(id));
            var del = new MenuItem("Delete");
            del.setOnAction(a -> deleteSelection());
            menu.getItems().addAll(run, new SeparatorMenuItem(), dup, copy, refresh, new SeparatorMenuItem(), del);
        } else {
            double sx = e.getX(), sy = e.getY();
            var add = new MenuItem("Add node…");
            add.setOnAction(a -> openQuickAdd(sx, sy, null));
            var paste = new MenuItem("Paste");
            paste.setOnAction(a -> {
                lastMouseWorld = toWorld(sx, sy);
                paste();
            });
            var fit = new MenuItem("Fit view");
            fit.setOnAction(a -> fitView());
            var all = new MenuItem("Select all");
            all.setOnAction(a -> selectAll());
            menu.getItems().addAll(add, paste, new SeparatorMenuItem(), all, fit);
        }
        menu.show(this, e.getScreenX(), e.getScreenY());
    }

    private void onDragOver(DragEvent e) {
        var s = e.getDragboard().getString();
        if (s != null && s.startsWith(DRAG_PREFIX)) e.acceptTransferModes(TransferMode.COPY);
        e.consume();
    }

    private void onDragDropped(DragEvent e) {
        var s = e.getDragboard().getString();
        if (s != null && s.startsWith(DRAG_PREFIX)) {
            var def = ws.registry.find(s.substring(DRAG_PREFIX.length()));
            if (def.isPresent()) {
                var p = toWorld(e.getX(), e.getY());
                addNode(def.get(), p.getX() - 40, p.getY() - 14);
                e.setDropCompleted(true);
            }
        }
        e.consume();
    }

    // ---- run events (called on the FX thread by the run bridge) ---------------------------------------------

    public void runStarted(Set<String> nodes) {
        for (var id : nodes) {
            var v = views.get(id);
            if (v != null) {
                v.body().reset();
                v.setStatus(NodeStatus.QUEUED, null);
            }
        }
    }

    public void nodeStatus(String id, NodeStatus s, String message) {
        var v = views.get(id);
        if (v == null) return;
        v.setStatus(s, message);
        for (var ev : edgeViews.values()) if (ev.edge().fromNode().equals(id)) ev.setFlowing(s == NodeStatus.RUNNING);
    }

    public void nodeProgress(String id, double f, String message) {
        var v = views.get(id);
        if (v != null) v.setProgress(f, message);
    }

    public void nodeEmit(String id, String channel, Object payload) {
        var v = views.get(id);
        if (v != null) v.body().emit(channel, payload);
    }

    public void nodeOutputs(String id, Map<String, Object> outputs) {
        var v = views.get(id);
        if (v != null) v.body().outputs(outputs);
    }
}
