package nexus.app.panels;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import nexus.core.exec.NodeStatus;
import nexus.core.exec.RunResult;
import nexus.engines.system.SystemMonitor;

/**
 * The timeline of the last run, like a profiler: one bar per node from start to end, packed into
 * lanes so that nodes running at the same time sit on different rows - parallelism is visible at a
 * glance. Colours follow the node status.
 *
 * <p>Under the bars, the machine's load during the run: while a run is going, the GPU and CPU are
 * sampled every 100 ms (NVML answers in a few milliseconds), so one sees which node kept the GPU
 * busy and which left it idle.
 */
public final class TimelinePanel extends Pane {
    private final Canvas canvas = new Canvas();
    private RunResult result;
    private SystemMonitor monitor;
    private record Load(long nanos, double gpu, double cpu) {
    }
    private final List<Load> load = new CopyOnWriteArrayList<>();
    private volatile Thread sampler;

    public TimelinePanel() {
        getChildren().add(canvas);
        setStyle("-fx-background-color: #181a20;");
        widthProperty().addListener((o, a, b) -> draw());
        heightProperty().addListener((o, a, b) -> draw());
    }

    /** Enables the load strip. */
    public void setMonitor(SystemMonitor m) {
        monitor = m;
    }

    /** A run started: sample the machine until {@link #show} is called with its result. */
    public void runStarted() {
        var m = monitor;
        if (m == null) return;
        stopSampling();
        load.clear();
        sampler = Thread.ofVirtual().name("timeline-load").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                var s = m.sampleNow();
                load.add(new Load(System.nanoTime(), s.gpu().utilGpu(), s.cpuSystem() * 100));
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    private void stopSampling() {
        var t = sampler;
        sampler = null;
        if (t != null) t.interrupt();
    }

    public void show(RunResult r) {
        stopSampling();
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
        boolean hasLoad = load.size() >= 3 && total > 300e6;      // shorter runs: too few samples to say anything
        double strip = hasLoad ? 46 : 0, stripTop = h - 16 - strip;

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
            if (y + lane > h - 14 - strip) continue;
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
            if (hasLoad) drawLoad(g, t0, total, left, span, stripTop, strip);
    }

    /** GPU (filled) and CPU (line) utilisation over the run, 0-100 %. */
    private void drawLoad(GraphicsContext g, long t0, double total, double left, double span, double top, double height) {
        g.setFill(Color.web("#1d2027"));
        g.fillRect(left, top, span, height);
        var pts = new ArrayList<Load>();
        for (var p : load) if (p.nanos() >= t0 - 50_000_000L && p.nanos() <= t0 + total + 50_000_000L) pts.add(p);
        if (pts.size() < 2) return;
        int n = pts.size();
        double[] xs = new double[n + 2], gy = new double[n + 2], cy = new double[n];
        for (int i = 0; i < n; i++) {
            var p = pts.get(i);
            xs[i] = left + Math.clamp((p.nanos() - t0) / total, 0, 1) * span;
            gy[i] = top + height - Math.clamp(p.gpu(), 0, 100) / 100 * height;
            cy[i] = top + height - Math.clamp(p.cpu(), 0, 100) / 100 * height;
        }
        xs[n] = xs[n - 1];
        gy[n] = top + height;
        xs[n + 1] = xs[0];
        gy[n + 1] = top + height;
        g.setFill(Color.web("#4dabf7", 0.35));
        g.fillPolygon(xs, gy, n + 2);
        g.setStroke(Color.web("#4dabf7"));
        g.setLineWidth(1.4);
        g.strokePolyline(xs, gy, n);
        g.setStroke(Color.web("#20c997"));
        g.strokePolyline(xs, cy, n);
        double gpuAvg = pts.stream().mapToDouble(Load::gpu).filter(v -> v >= 0).average().orElse(0);
        double cpuAvg = pts.stream().mapToDouble(Load::cpu).filter(v -> v >= 0).average().orElse(0);
        g.setFill(Color.web("#8b93a7"));
        g.fillText(String.format("GPU %.0f%% avg (blue)  ·  CPU %.0f%% avg (green)", gpuAvg, cpuAvg), left + 6, top + 13);
    }
}
