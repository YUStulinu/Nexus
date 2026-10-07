package nexus.app.views;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import nexus.core.exec.NodeStatus;
import nexus.core.types.Table;
import nexus.engines.train.GptTraining;

/** Views for language-model training: a live loss curve, and several runs compared. */
public final class TrainingViews {
    private TrainingViews() {
    }

    public static void register() {
        Bodies.register("loss-curve", LossBody::new);
        Bodies.register("curves", CompareBody::new);
    }

    static final Color BG = Color.web("#181a20"), PANEL = Color.web("#1d2027"), GRID = Color.web("#2b3040"), MUTED = Color.web("#8b93a7"),
            TRAIN = Color.web("#4dabf7"), VAL = Color.web("#ff922b");
    static final Color[] RUNS = {Color.web("#ff922b"), Color.web("#4dabf7"), Color.web("#40c057")};

    /** A line chart with numeric axes. */
    record Series(String name, Color color, double[] xs, double[] ys, boolean dots) {
    }

    static void plot(GraphicsContext g, double x0, double y0, double w, double h, String title, String xLabel, List<Series> series) {
        g.setFill(PANEL);
        g.fillRoundRect(x0, y0, w, h, 6, 6);
        g.setTextBaseline(VPos.TOP);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFill(MUTED);
        g.setFont(Font.font("System", FontWeight.BOLD, 10.5));
        g.fillText(title, x0 + 6, y0 + 4);
        double xmin = Double.MAX_VALUE, xmax = -Double.MAX_VALUE, ymin = Double.MAX_VALUE, ymax = -Double.MAX_VALUE;
        for (var s : series)
            for (int i = 0; i < s.xs.length; i++) {
                xmin = Math.min(xmin, s.xs[i]);
                xmax = Math.max(xmax, s.xs[i]);
                ymin = Math.min(ymin, s.ys[i]);
                ymax = Math.max(ymax, s.ys[i]);
            }
        if (xmin > xmax) return;
        if (xmax - xmin < 1e-9) xmax = xmin + 1;
        if (ymax - ymin < 1e-9) {
            ymax += 0.5;
            ymin -= 0.5;
        }
        double pad = (ymax - ymin) * 0.08;
        ymin -= pad;
        ymax += pad;
        double left = x0 + 38, right = x0 + w - 8, top = y0 + 22, bottom = y0 + h - 16;
        g.setStroke(GRID);
        g.setLineWidth(1);
        g.setFont(Font.font("System", 9.5));
        for (int i = 0; i <= 4; i++) {
            double y = top + (bottom - top) * i / 4;
            g.strokeLine(left, y, right, y);
            g.setFill(MUTED);
            g.setTextAlign(TextAlignment.RIGHT);
            g.setTextBaseline(VPos.CENTER);
            g.fillText(String.format(Locale.ROOT, "%.2f", ymax - (ymax - ymin) * i / 4), left - 4, y);
        }
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.TOP);
        g.fillText(String.format(Locale.ROOT, "%.0f", xmin), left, bottom + 2);
        g.fillText(String.format(Locale.ROOT, "%.0f %s", xmax, xLabel), right - 20, bottom + 2);
        double lx = right;
        for (var s : series) {
            g.setStroke(s.color);
            g.setFill(s.color);
            g.setLineWidth(s.dots ? 2 : 1.2);
            for (int i = 0; i < s.xs.length; i++) {
                double x = left + (right - left) * (s.xs[i] - xmin) / (xmax - xmin);
                double y = bottom - (bottom - top) * (s.ys[i] - ymin) / (ymax - ymin);
                if (i > 0) {
                    double px = left + (right - left) * (s.xs[i - 1] - xmin) / (xmax - xmin);
                    double py = bottom - (bottom - top) * (s.ys[i - 1] - ymin) / (ymax - ymin);
                    g.strokeLine(px, py, x, y);
                }
                if (s.dots) g.fillOval(x - 2.5, y - 2.5, 5, 5);
            }
        }
        // legend
        g.setFont(Font.font("System", FontWeight.BOLD, 10));
        g.setTextAlign(TextAlignment.RIGHT);
        g.setTextBaseline(VPos.TOP);
        for (int i = series.size() - 1; i >= 0; i--) {
            var s = series.get(i);
            g.setFill(s.color);
            g.fillText(s.name, lx, y0 + 4);
            lx -= s.name.length() * 6 + 14;
        }
    }

    // ---- one run, live --------------------------------------------------------------------------------------

    static final class LossBody extends NodeBody {
        private final Canvas canvas = new Canvas();
        private final Label state = new Label("not started"), speed = new Label();
        private final ListView<String> lines = new ListView<>();
        private final List<GptTraining.Point> points = new ArrayList<>();
        private final List<GptTraining.Eval> evals = new ArrayList<>();

        LossBody() {
            state.setStyle("-fx-text-fill: #ffd43b; -fx-font-size: 11.5px; -fx-font-weight: bold;");
            speed.setStyle("-fx-text-fill: #a5adbf; -fx-font-size: 11px;");
            lines.setStyle("-fx-font-size: 10px;");
            getChildren().addAll(state, speed, canvas, lines);
        }

        @Override
        public double preferredHeight() {
            return 320;
        }

        @Override
        public boolean interactive() {
            return true;
        }

        @Override
        protected void layoutChildren() {
            double w = getWidth(), h = getHeight();
            state.resizeRelocate(0, 0, w, 16);
            speed.resizeRelocate(0, 16, w, 16);
            canvas.setWidth(w);
            canvas.setHeight(h - 34 - 70);
            canvas.relocate(0, 34);
            lines.resizeRelocate(0, h - 66, w, 66);
            draw();
        }

        @Override
        public void reset() {
            points.clear();
            evals.clear();
            lines.getItems().clear();
            state.setText("starting…");
            speed.setText("");
            draw();
        }

        @Override
        public void status(NodeStatus s, String message) {
            if (s == NodeStatus.ERROR) state.setText(message);
        }

        @Override
        public void emit(String channel, Object payload) {
            switch (channel) {
                case "point" -> {
                    var p = (GptTraining.Point) payload;
                    points.add(p);
                    speed.setText(String.format(Locale.ROOT, "step %d  ·  loss %.3f  ·  %.1fk tokens/s  ·  %.0f s", p.step(), p.loss(),
                                                p.tokensPerSecond() / 1000, p.seconds()));
                    draw();
                }
                case "eval" -> {
                    var e = (GptTraining.Eval) payload;
                    evals.add(e);
                    draw();
                }
                case "state" -> state.setText(String.valueOf(payload));
                case "line" -> {
                    for (var l : String.valueOf(payload).split("\n")) if (!l.isBlank()) lines.getItems().add(l);
                    if (lines.getItems().size() > 300) lines.getItems().remove(0, lines.getItems().size() - 300);
                    lines.scrollTo(lines.getItems().size() - 1);
                }
                default -> {
                }
            }
        }

        private void draw() {
            var g = canvas.getGraphicsContext2D();
            double w = canvas.getWidth(), h = canvas.getHeight();
            g.setFill(BG);
            g.fillRect(0, 0, w, h);
            if (w < 40 || h < 40) return;
            var series = new ArrayList<Series>();
            if (!points.isEmpty())
                series.add(new Series("train loss", TRAIN, points.stream().mapToDouble(GptTraining.Point::step).toArray(),
                                      points.stream().mapToDouble(GptTraining.Point::loss).toArray(), false));
            if (!evals.isEmpty())
                series.add(new Series("validation", VAL, evals.stream().mapToDouble(GptTraining.Eval::step).toArray(),
                                      evals.stream().mapToDouble(GptTraining.Eval::val).toArray(), true));
            plot(g, 0, 0, w, h, "loss", "steps", series);
        }
    }

    // ---- runs compared ---------------------------------------------------------------------------------------

    static final class CompareBody extends NodeBody {
        private final Canvas canvas = new Canvas();
        private List<?> runs = List.of();

        CompareBody() {
            getChildren().add(canvas);
        }

        @Override
        public double preferredHeight() {
            return 250;
        }

        @Override
        protected void layoutChildren() {
            canvas.setWidth(getWidth());
            canvas.setHeight(getHeight());
            draw();
        }

        @Override
        public void outputs(Map<String, Object> outputs) {
            if (outputs.get("runs") instanceof List<?> l) runs = l;
            draw();
        }

        private void draw() {
            var g = canvas.getGraphicsContext2D();
            double w = canvas.getWidth(), h = canvas.getHeight();
            g.setFill(BG);
            g.fillRect(0, 0, w, h);
            if (w < 40) return;
            var series = new ArrayList<Series>();
            int i = 0;
            for (var o : runs) {
                if (!(o instanceof Table t) || t.size() == 0) continue;
                var name = String.valueOf(t.rows().getFirst().get(0));
                double[] xs = t.rows().stream().mapToDouble(r -> ((Number) r.get(2)).doubleValue()).toArray();
                double[] ys = t.rows().stream().mapToDouble(r -> ((Number) r.get(4)).doubleValue()).toArray();
                series.add(new Series(name, RUNS[i++ % RUNS.length], xs, ys, true));
            }
            if (series.isEmpty()) {
                g.setFill(MUTED);
                g.setFont(Font.font("System", 11));
                g.setTextBaseline(VPos.TOP);
                g.setTextAlign(TextAlignment.LEFT);
                g.fillText("connect the curves of training runs", 6, 6);
                return;
            }
            plot(g, 0, 0, w, h, "validation loss against wall-clock time", "s", series);
        }
    }
}
