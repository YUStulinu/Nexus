package nexus.app.canvas;

import javafx.css.PseudoClass;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.shape.CubicCurve;
import nexus.core.graph.Edge;
import nexus.core.types.DataType;

/**
 * A wire: a horizontal-tangent Bézier curve from an output port to an input port, coloured by the
 * data type. A second, wide, transparent curve on top makes the thin wire easy to click. While the
 * producing node runs, the wire's dashes flow towards the consumer.
 */
public final class EdgeView extends Group {
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private final Edge edge;
    private final CubicCurve line = new CubicCurve();
    private final CubicCurve hit = new CubicCurve();
    private boolean flowing;

    public EdgeView(Edge edge, DataType type) {
        this.edge = edge;
        line.getStyleClass().add("edge");
        line.setStroke(Color.web(type.color()));
        line.setMouseTransparent(true);
        hit.getStyleClass().add("edge-hit");
        hit.getProperties().put(EdgeView.class, this);
        getChildren().addAll(line, hit);
    }

    public Edge edge() {
        return edge;
    }

    public void update(Point2D from, Point2D to) {
        set(line, from, to);
        set(hit, from, to);
    }

    static void set(CubicCurve c, Point2D from, Point2D to) {
        double dx = Math.max(40, Math.abs(to.getX() - from.getX()) * 0.5);
        c.setStartX(from.getX());
        c.setStartY(from.getY());
        c.setControlX1(from.getX() + dx);
        c.setControlY1(from.getY());
        c.setControlX2(to.getX() - dx);
        c.setControlY2(to.getY());
        c.setEndX(to.getX());
        c.setEndY(to.getY());
    }

    public void setSelected(boolean s) {
        line.pseudoClassStateChanged(SELECTED, s);
    }

    /** Animated dashes while the source node runs. */
    public void setFlowing(boolean f) {
        if (f == flowing) return;
        flowing = f;
        if (f) line.getStrokeDashArray().setAll(10.0, 6.0);
        else {
            line.getStrokeDashArray().clear();
            line.setStrokeDashOffset(0);
        }
    }

    /** Advances the dash animation; called every frame by the canvas. */
    public void tick(double seconds) {
        if (flowing) line.setStrokeDashOffset(-(seconds * 40) % 16);
    }
}
