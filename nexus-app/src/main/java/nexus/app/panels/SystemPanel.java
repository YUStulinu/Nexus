package nexus.app.panels;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToDoubleFunction;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import nexus.engines.system.SystemMonitor;
import nexus.engines.system.VramBroker;

/**
 * The machine at a glance: the GPU's memory split by who holds it (each engine's lease, other
 * programs, the safety margin, what is free), five minutes of GPU / VRAM / CPU / power history,
 * the leases and the requests waiting for memory, and the broker's decisions (who was stopped or
 * paused, for whom).
 */
public final class SystemPanel extends HBox {
    static final Color BG = Color.web("#181a20"), GRID = Color.web("#262a33"), TEXT = Color.web("#d5d9e3"), MUTED = Color.web("#8b93a7");
    static final Color OTHER = Color.web("#5c6275"), MARGIN = Color.web("#3a2f2f"), FREE = Color.web("#232733");
    static final Color[] PALETTE = {Color.web("#4dabf7"), Color.web("#f59f00"), Color.web("#b197fc"), Color.web("#20c997"),
                                    Color.web("#ff8787"), Color.web("#94d82d"), Color.web("#e599f7"), Color.web("#ffd43b")};
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final SystemMonitor monitor;
    private final VramBroker broker;
    private final Label gpuTitle = new Label(), gpuStats = new Label(), hostStats = new Label();
    private final VramBar bar = new VramBar();
    private final Chart util, vram, cpu, power;
    private final VBox leases = new VBox(3);
    private final ListView<String> events = new ListView<>();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();

    public SystemPanel(SystemMonitor monitor, VramBroker broker) {
        this.monitor = monitor;
        this.broker = broker;
        setSpacing(14);
        setPadding(new Insets(8, 10, 8, 10));
        setStyle("-fx-background-color: #181a20;");

        gpuTitle.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px;");
        gpuStats.setStyle("-fx-text-fill: #a5adbf; -fx-font-size: 11.5px;");
        hostStats.setStyle("-fx-text-fill: #a5adbf; -fx-font-size: 11.5px;");
        bar.setMinHeight(64);
        bar.setPrefHeight(64);
        var left = new VBox(6, gpuTitle, gpuStats, bar, hostStats);
        left.setMinWidth(360);
        left.setPrefWidth(470);

        util = new Chart("GPU", "%", Color.web("#4dabf7"), s -> s.gpu().utilGpu(), 100);
        vram = new Chart("VRAM", "MiB", Color.web("#b197fc"), s -> s.gpu().usedMiB(), -1);
        cpu = new Chart("CPU · NEXUS", "%", Color.web("#20c997"), s -> s.cpuSystem() * 100, 100);
        cpu.second(Color.web("#f59f00"), s -> s.cpuProcess() * 100);
        power = new Chart("Power", "W", Color.web("#ffd43b"), s -> s.gpu().powerW(), -1);
        var charts = new GridPane(8, 8);
        charts.add(util, 0, 0);
        charts.add(vram, 1, 0);
        charts.add(cpu, 0, 1);
        charts.add(power, 1, 1);
        for (int i = 0; i < 2; i++) {
            var c = new ColumnConstraints();
            c.setPercentWidth(50);
            charts.getColumnConstraints().add(c);
            var r = new RowConstraints();
            r.setPercentHeight(50);
            charts.getRowConstraints().add(r);
        }
        HBox.setHgrow(charts, Priority.ALWAYS);
        charts.setMinWidth(300);

        var leasesTitle = title("GPU MEMORY LEASES");
        var eventsTitle = title("BROKER DECISIONS");
        events.setPlaceholder(new Label("nothing yet"));
        events.setStyle("-fx-background-color: #1d2027; -fx-control-inner-background: #1d2027; -fx-font-size: 11px;");
        VBox.setVgrow(events, Priority.ALWAYS);
        var right = new VBox(4, leasesTitle, leases, eventsTitle, events);
        right.setMinWidth(320);
        right.setPrefWidth(380);

        getChildren().addAll(left, charts, right);
        monitor.addListener(s -> queueRefresh());
        if (broker != null) broker.addListener(this::queueRefresh);
        refresh();
    }

    static Label title(String text) {
        var l = new Label(text);
        l.setStyle("-fx-text-fill: #8b93a7; -fx-font-size: 10.5px; -fx-font-weight: bold;");
        return l;
    }

    static Color colorOf(String holder) {
        return PALETTE[Math.floorMod(holder.hashCode(), PALETTE.length)];
    }

    /** Coalesces updates from the sampling thread and the broker into one per frame. */
    private void queueRefresh() {
        if (refreshQueued.compareAndSet(false, true)) Platform.runLater(() -> {
            refreshQueued.set(false);
            refresh();
        });
    }

    private void refresh() {
        var s = monitor.latest();
        var g = s.gpu();
        var hist = monitor.history();
        if (g.present()) {
            gpuTitle.setText(g.name() + "   ·   " + g.source());
            gpuStats.setText(String.format(Locale.ROOT, "%d%% busy   ·   %d °C   ·   %.0f / %.0f W   ·   SM %d MHz   ·   memory %d MHz%s",
                                           g.utilGpu(), g.temperatureC(), g.powerW(), g.powerLimitW(), g.smClockMHz(), g.memClockMHz(),
                                           g.fanPercent() >= 0 ? "   ·   fan " + g.fanPercent() + "%" : ""));
        } else {
            gpuTitle.setText("No NVIDIA GPU found");
            gpuStats.setText("Engines run without GPU memory coordination.");
        }
        hostStats.setText(String.format(Locale.ROOT, "CPU %.0f%% (NEXUS %.0f%%)   ·   RAM %.1f / %.1f GB   ·   Java heap %d / %d MiB   ·   %d threads",
                                        s.cpuSystem() * 100, s.cpuProcess() * 100, (s.ramTotalMiB() - s.ramFreeMiB()) / 1024.0,
                                        s.ramTotalMiB() / 1024.0, s.heapUsedMiB(), s.heapMaxMiB(), s.threads()));
        util.setData(hist);
        vram.setMax(g.totalMiB());
        vram.setData(hist);
        cpu.setData(hist);
        power.setMax(g.powerLimitW() > 0 ? g.powerLimitW() : -1);
        power.setData(hist);

        if (broker != null) {
            var st = broker.state();
            bar.show(st);
            leases.getChildren().clear();
            if (st.leases().isEmpty() && st.waiting().isEmpty()) {
                var none = new Label("none - no engine is holding GPU memory");
                none.setStyle("-fx-text-fill: #6c7389; -fx-font-size: 11px; -fx-font-style: italic;");
                leases.getChildren().add(none);
            }
            for (var l : st.leases()) {
                var row = new Label(String.format(Locale.ROOT, "■  %s   %d MiB   %s%s   since %s", l.holder(), l.mib(), l.priority().name().toLowerCase(Locale.ROOT),
                                                  l.active() ? "" : ", loading", TIME.format(LocalTime.ofInstant(l.granted(), ZoneId.systemDefault()))
                                                  + (l.preemptedFor() != null ? "   → releasing for " + l.preemptedFor() : "")));
                row.setTextFill(colorOf(l.holder()));
                row.setStyle("-fx-font-size: 11.5px;");
                leases.getChildren().add(row);
            }
            for (var w : st.waiting()) {
                var row = new Label(String.format(Locale.ROOT, "⧗  %s waits for %d MiB (%s) for %s", w.holder(), w.mib(), w.priority().name().toLowerCase(Locale.ROOT),
                                                  EnginesPanel.human(Duration.between(w.since(), Instant.now()))));
                row.setStyle("-fx-text-fill: #ffd43b; -fx-font-size: 11.5px;");
                leases.getChildren().add(row);
            }
            var ev = broker.events();
            var lines = new ArrayList<String>(ev.size());
            for (int i = ev.size() - 1; i >= 0; i--)
                lines.add(TIME.format(LocalTime.ofInstant(ev.get(i).time(), ZoneId.systemDefault())) + "  " + ev.get(i).text());
            if (!lines.equals(events.getItems())) events.getItems().setAll(lines);
        }
    }

    // ---- the memory bar -----------------------------------------------------------------------------------------

    /** The card's memory as one bar: leases (coloured), other programs, margin, free. */
    static final class VramBar extends Region {
        private final Canvas canvas = new Canvas();
        private VramBroker.State state;

        VramBar() {
            getChildren().add(canvas);
        }

        void show(VramBroker.State s) {
            state = s;
            draw();
        }

        @Override
        protected void layoutChildren() {
            canvas.setWidth(getWidth());
            canvas.setHeight(getHeight());
            draw();
        }

        private void draw() {
            var g = canvas.getGraphicsContext2D();
            double w = canvas.getWidth(), h = canvas.getHeight(), barH = 26;
            g.setFill(BG);
            g.fillRect(0, 0, w, h);
            if (state == null || state.total() <= 0 || w < 10) return;
            record Seg(String label, double mib, Color color) {
            }
            var segs = new ArrayList<Seg>();
            for (var l : state.leases()) segs.add(new Seg(l.holder(), l.mib(), colorOf(l.holder())));
            segs.add(new Seg("other programs", state.other(), OTHER));
            segs.add(new Seg("margin", state.margin(), MARGIN));
            double used = segs.stream().mapToDouble(Seg::mib).sum();
            segs.add(new Seg("free", Math.max(0, state.total() - used), FREE));
            double x = 0, scale = w / Math.max(state.total(), used);
            g.setFont(Font.font("System", FontWeight.BOLD, 10.5));
            g.setTextBaseline(VPos.CENTER);
            g.setTextAlign(TextAlignment.CENTER);
            for (var s : segs) {
                double sw = s.mib() * scale;
                if (sw <= 0) continue;
                g.setFill(s.color());
                g.fillRoundRect(x + 0.5, 0, Math.max(1, sw - 1), barH, 4, 4);
                String text = s.label() + " " + Math.round(s.mib());
                if (sw <= text.length() * 6.2 + 6) text = String.valueOf(Math.round(s.mib()));      // the lease list is the legend
                if (sw > text.length() * 6.2 + 6) {
                    g.setFill(s.color() == FREE || s.color() == MARGIN || s.color() == OTHER ? TEXT : Color.web("#111318"));
                    g.fillText(text, x + sw / 2, barH / 2);
                }
                x += sw;
            }
            // Measured usage as a tick: what the driver says is in use (others + loaded leases).
            double measured = state.measuredUsed() * scale;
            g.setStroke(Color.WHITE);
            g.setLineWidth(1.5);
            g.strokeLine(measured, barH + 2, measured, barH + 8);
            g.setTextAlign(TextAlignment.LEFT);
            g.setFont(Font.font("System", 11));
            g.setFill(MUTED);
            g.fillText(String.format(Locale.ROOT, "%d MiB total   ·   %d measured in use (tick)   ·   %d available for new work",
                                     state.total(), state.measuredUsed(), Math.max(0, state.available())), 0, barH + 20);
        }
    }

    // ---- a history chart ------------------------------------------------------------------------------------------

    /** Five minutes of one (or two) measures as filled lines, with the current value. */
    static final class Chart extends Region {
        private final Canvas canvas = new Canvas();
        private final String name, unit;
        private final Color color;
        private final ToDoubleFunction<SystemMonitor.Sample> value;
        private Color color2;
        private ToDoubleFunction<SystemMonitor.Sample> value2;
        private double max;
        private List<SystemMonitor.Sample> data = List.of();

        Chart(String name, String unit, Color color, ToDoubleFunction<SystemMonitor.Sample> value, double max) {
            this.name = name;
            this.unit = unit;
            this.color = color;
            this.value = value;
            this.max = max;
            getChildren().add(canvas);
            setMinHeight(60);
        }

        void second(Color c, ToDoubleFunction<SystemMonitor.Sample> v) {
            color2 = c;
            value2 = v;
        }

        void setMax(double m) {
            max = m;
        }

        void setData(List<SystemMonitor.Sample> d) {
            data = d;
            draw();
        }

        @Override
        protected void layoutChildren() {
            canvas.setWidth(getWidth());
            canvas.setHeight(getHeight());
            draw();
        }

        private void draw() {
            var g = canvas.getGraphicsContext2D();
            double w = canvas.getWidth(), h = canvas.getHeight();
            g.setFill(Color.web("#1d2027"));
            g.fillRoundRect(0, 0, w, h, 6, 6);
            if (w < 20 || h < 20) return;
            double top = 18, bottom = h - 4, plotH = bottom - top;
            double hi = max;
            if (hi <= 0) {
                hi = 1;
                for (var s : data) hi = Math.max(hi, value.applyAsDouble(s) * 1.15);
            }
            g.setStroke(GRID);
            g.setLineWidth(1);
            for (int i = 1; i < 4; i++) g.strokeLine(4, top + plotH * i / 4, w - 4, top + plotH * i / 4);
            if (value2 != null) line(g, value2, color2, hi, w, top, plotH, false);
            line(g, value, color, hi, w, top, plotH, true);
            g.setTextBaseline(VPos.TOP);
            g.setTextAlign(TextAlignment.LEFT);
            g.setFont(Font.font("System", FontWeight.BOLD, 11));
            g.setFill(MUTED);
            g.fillText(name, 7, 4);
            if (!data.isEmpty()) {
                double v = value.applyAsDouble(data.getLast());
                String now = v < 0 ? "n/a" : (unit.equals("MiB") ? String.format(Locale.ROOT, "%.0f / %.0f MiB", v, hi)
                                                                 : String.format(Locale.ROOT, "%.0f %s", v, unit));
                if (value2 != null && value2.applyAsDouble(data.getLast()) >= 0)
                    now += String.format(Locale.ROOT, " / %.0f %s", value2.applyAsDouble(data.getLast()), unit);
                g.setTextAlign(TextAlignment.RIGHT);
                g.setFill(color);
                g.fillText(now, w - 7, 4);
            }
        }

        private void line(GraphicsContext g, ToDoubleFunction<SystemMonitor.Sample> f, Color c, double hi, double w, double top, double plotH,
                          boolean fill) {
            int n = data.size();
            if (n < 2) return;
            // The first minute fills the chart; after that it scrolls and gradually widens to five minutes.
            double step = (w - 8) / Math.max(n - 1, 60), x0 = w - 4 - (n - 1) * step;
            double[] xs = new double[n + 2], ys = new double[n + 2];
            for (int i = 0; i < n; i++) {
                double v = Math.max(0, f.applyAsDouble(data.get(i)));
                xs[i] = x0 + i * step;
                ys[i] = top + plotH - Math.min(1, v / hi) * plotH;
            }
            if (fill) {
                xs[n] = xs[n - 1];
                ys[n] = top + plotH;
                xs[n + 1] = xs[0];
                ys[n + 1] = top + plotH;
                g.setFill(c.deriveColor(0, 1, 1, 0.18));
                g.fillPolygon(xs, ys, n + 2);
            }
            g.setStroke(c);
            g.setLineWidth(1.6);
            g.strokePolyline(xs, ys, n);
        }
    }
}
