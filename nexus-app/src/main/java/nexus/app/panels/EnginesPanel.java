package nexus.app.panels;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import nexus.engines.Engine;
import nexus.engines.EngineManager;
import nexus.engines.EngineSpec;
import nexus.engines.EngineState;
import nexus.engines.llm.OpenAiClient;

/**
 * The engines NEXUS supervises: state, controls, live metrics (for Ember: decode speed, requests,
 * KV-cache occupancy, free GPU memory) and their output log.
 */
public final class EnginesPanel extends ScrollPane {
    private final VBox rows = new VBox(6);
    private final Map<String, Row> byId = new HashMap<>();

    public EnginesPanel(EngineManager manager) {
        rows.setPadding(new Insets(8));
        setContent(rows);
        setFitToWidth(true);
        setStyle("-fx-background: #181a20; -fx-background-color: #181a20;");
        var header = new Label("Projects folder: " + manager.projects());
        header.getStyleClass().add("body-muted");
        header.setStyle("-fx-text-fill: #8b93a7; -fx-font-size: 11px;");
        rows.getChildren().add(header);
        for (var e : manager.all()) {
            var r = new Row(e);
            byId.put(e.spec().id(), r);
            rows.getChildren().add(r);
        }
        manager.addListener(e -> Platform.runLater(() -> {
            var r = byId.get(e.spec().id());
            if (r != null) r.refresh();
        }));
        // Live metrics of running LLM engines, once a second, off the UI thread.
        Thread.ofVirtual().name("engine-metrics").start(() -> {
            while (true) {
                for (var r : byId.values()) r.poll();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    return;
                }
            }
        });
    }

    static String human(Duration d) {
        long s = d.toSeconds();
        return s < 60 ? s + " s" : s < 3600 ? s / 60 + " min" : s / 3600 + " h " + (s % 3600) / 60 + " min";
    }

    private final class Row extends HBox {
        private final Engine engine;
        private final Circle lamp = new Circle(6);
        private final Label state = new Label();
        private final Label metrics = new Label();
        private final Button start = new Button("Start"), stop = new Button("Stop"), restart = new Button("Restart"),
                logButton = new Button("Log");

        Row(Engine engine) {
            this.engine = engine;
            var spec = engine.spec();
            var name = new Label(spec.name());
            name.setStyle("-fx-font-weight: bold; -fx-text-fill: #e6e8ee;");
            var desc = new Label(spec.description() + (spec.port() > 0 ? "  ·  port " + spec.port() : "")
                                 + (spec.vramMiB() > 0 ? "  ·  ~" + spec.vramMiB() + " MiB VRAM" : ""));
            desc.setStyle("-fx-text-fill: #8b93a7; -fx-font-size: 11px;");
            state.setStyle("-fx-font-size: 11px;");
            metrics.setStyle("-fx-text-fill: #a5d8ff; -fx-font-size: 11px; -fx-font-family: 'Cascadia Mono', 'Consolas', monospace;");
            var text = new VBox(2, name, desc, state, metrics);
            HBox.setHgrow(text, Priority.ALWAYS);
            start.setOnAction(e -> engine.ensureStarted());
            stop.setOnAction(e -> Thread.ofVirtual().start(engine::stop));
            restart.setOnAction(e -> Thread.ofVirtual().start(engine::restart));
            logButton.setOnAction(e -> showLog());
            var buttons = new HBox(4, start, stop, restart, logButton);
            buttons.setAlignment(Pos.CENTER_RIGHT);
            setSpacing(10);
            setAlignment(Pos.CENTER_LEFT);
            setPadding(new Insets(8, 10, 8, 10));
            setStyle("-fx-background-color: #20232b; -fx-background-radius: 8;");
            getChildren().addAll(lamp, text, buttons);
            refresh();
        }

        void refresh() {
            var s = engine.state();
            lamp.setFill(Color.web(switch (s) {
                case READY, ATTACHED -> "#40c057";
                case STARTING, STOPPING -> "#fab005";
                case FAILED -> "#fa5252";
                default -> "#495057";
            }));
            String extra = switch (s) {
                case READY -> "ready · pid " + engine.pid() + " · up " + human(Duration.between(engine.since(), Instant.now()));
                case ATTACHED -> "attached to an engine already running on the port";
                case FAILED -> "failed: " + engine.lastError();
                case STARTING -> "starting…";
                default -> engine.spec().available() ? "stopped" : "not built: " + engine.spec().command().getFirst();
            };
            if (engine.restartCount() > 0) extra += " · " + engine.restartCount() + " automatic restart(s)";
            state.setText(extra);
            state.setStyle("-fx-font-size: 11px; -fx-text-fill: " + (s == EngineState.FAILED ? "#ff8787" : "#c1c7d6") + ";");
            start.setDisable(s.isUp() || s == EngineState.STARTING || !engine.spec().available());
            stop.setDisable(s == EngineState.STOPPED);
            restart.setDisable(!s.isUp());
            if (!s.isUp()) metrics.setText("");
        }

        void poll() {
            if (!engine.state().isUp() || engine.spec().kind() != EngineSpec.Kind.LLM) return;
            var st = new OpenAiClient(engine.spec().baseUrl()).stats();
            if (st.isEmpty()) return;
            var kv = st.path("kv");
            var mem = st.path("memory");
            String m = String.format(Locale.ROOT, "%s %s · %.0f tok/s · %d running, %d waiting · KV %d/%d blocks · GPU free %.0f MiB",
                                     st.path("model").asText(), st.path("weights").asText(), st.path("tokens_per_second").asDouble(),
                                     st.path("running").asInt(), st.path("waiting").asInt(), kv.path("used").asInt(), kv.path("blocks").asInt(),
                                     mem.path("device_free_mib").asDouble());
            Platform.runLater(() -> {
                metrics.setText(m);
                refresh();
            });
        }

        void showLog() {
            var area = new TextArea(String.join("\n", engine.logTail(2000)));
            area.setEditable(false);
            area.setStyle("-fx-font-family: 'Cascadia Mono', 'Consolas', monospace; -fx-font-size: 11px;");
            area.positionCaret(area.getLength());
            engine.addLogListener(line -> Platform.runLater(() -> area.appendText("\n" + line)));
            var stage = new Stage();
            stage.setTitle("Log - " + engine.spec().name());
            var scene = new Scene(new VBox(area), 900, 500);
            VBox.setVgrow(area, Priority.ALWAYS);
            scene.getStylesheets().addAll(getScene().getStylesheets());
            stage.setScene(scene);
            stage.show();
        }
    }
}
