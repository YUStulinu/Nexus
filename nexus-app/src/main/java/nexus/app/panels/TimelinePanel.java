package nexus.app.panels;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import nexus.core.exec.NodeStatus;
import nexus.core.exec.RunResult;

/**
 * The timeline of the last run, like a profiler: one bar per node from start to end, packed into
 * lanes so that nodes running at the same time sit on different rows - parallelism is visible at a
 * glance. Colours follow the node status.
 */
public final class TimelinePanel extends Pane {
    private final Canvas canvas = new Canvas();
    private RunResult result;

    public TimelinePanel() {
        getChildren().add(canvas);
        setStyle("-fx-background-color: #181a20;");
        widthProperty().addListener((o, a, b) -> draw());
        heightProperty().addListener((o, a, b) -> draw());
    }

    public void show(RunResult r) {
        result = r;
        draw();
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        draw();
    }

    private static Color colorOf(NodeStatus s) {
        return Color.web(switch (s) {
            case DONE -> "#2f9e44";
            case CACHED -> "#0ca678";
            case ERROR -> "#e03131";
            case CANCELLED -> "#868e96";
            case SKIPPED -> "#f08c00";
            default -> "#1c7ed6";
        });
    }

    private void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        g.setFill(Color.web("#181a20"));
        g.fillRect(0, 0, w, h);
        g.setFont(Font.font("Segoe UI", 11));
        if (result == null || result.timings().isEmpty()) {
            g.setFill(Color.web("#59607a"));
            g.fillText("Run the workflow (F5) to see when each node ran.", 14, 24);
            return;
        }
        long t0 = result.startNanos();
        double total = Math.max(1, result.endNanos() - t0);
        double left = 14, right = 14, top = 26, lane = 22;
        double span = w - left - right;

        // axis
        g.setFill(Color.web("#8b93a7"));
        g.fillText(String.format("run #%d  ·  %.1f ms  ·  %d nodes (%d cached)", result.runId(), result.millis(),
                                 result.timings().size(), result.count(NodeStatus.CACHED)), left, 16);
        for (int i = 0; i <= 4; i++) {
            double x = left + span * i / 4;
            g.setStroke(Color.web("#262a33"));
            g.strokeLine(x, top - 4, x, h);
            g.setFill(Color.web("#59607a"));
            g.fillText(String.format("%.0f ms", total / 1e6 * i / 4), Math.min(x + 3, w - 50), h - 4);
        }

        // pack bars into lanes (first lane where the previous bar has ended)
        List<Long> laneEnds = new ArrayList<>();
        for (var t : result.timings()) {
            int l = 0;
            while (l < laneEnds.size() && laneEnds.get(l) > t.startNanos()) l++;
            if (l == laneEnds.size()) laneEnds.add(t.endNanos());
            else laneEnds.set(l, t.endNanos());
            double x = left + (t.startNanos() - t0) / total * span;
            double bw = Math.max(3, (t.endNanos() - t.startNanos()) / total * span);
            double y = top + l * lane;
            if (y + lane > h - 14) continue;
            g.setFill(colorOf(t.status()));
            g.fillRoundRect(x, y, bw, lane - 5, 5, 5);
            g.setFill(Color.WHITE);
            String label = t.title() + "  " + String.format("%.1f ms", t.millis());
            if (bw > 50) {
                g.save();
                g.beginPath();
                g.rect(x, y, bw, lane - 5);
                g.clip();
                g.fillText(label, x + 5, y + 12);
                g.restore();
            } else {
                g.setFill(Color.web("#c1c7d6"));
                g.fillText(label, x + bw + 4, y + 12);
            }
        }
    }
}
