package nexus.app.views;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import nexus.core.exec.NodeStatus;
import nexus.engines.EngineManager;
import nexus.engines.llm.OpenAiClient;

/** Views for the language-model nodes: a streaming answer, a duel, and an interactive conversation. */
public final class LlmViews {
    private LlmViews() {
    }

    public static void register() {
        Bodies.register("llm", AnswerBody::new);
        Bodies.register("duel", DuelBody::new);
        Bodies.register("chat-session", ChatBody::new);
    }

    static String statsLine(OpenAiClient.Reply r) {
        return String.format(Locale.ROOT, "%s · %d tokens · %.0f tok/s · first token %.0f ms", r.model(), r.completionTokens(),
                             r.tokensPerSecond(), r.ttftMs());
    }

    /** One answer streaming in, with the model's reasoning (if any) above it and speed below. */
    static final class AnswerBody extends NodeBody {
        private final Label reasoning = new Label();
        private final TextArea text = new TextArea();
        private final Label stats = new Label("not run yet");

        AnswerBody() {
            text.setEditable(false);
            text.setWrapText(true);
            reasoning.getStyleClass().add("body-muted");
            reasoning.setWrapText(true);
            reasoning.setMaxHeight(48);
            reasoning.setVisible(false);
            stats.getStyleClass().add("body-muted");
            getChildren().addAll(reasoning, text, stats);
        }

        @Override
        public double preferredHeight() {
            return 220;
        }

        @Override
        public boolean interactive() {
            return true;
        }

        @Override
        protected void layoutChildren() {
            double w = getWidth(), h = getHeight(), y = 0;
            if (reasoning.isVisible()) {
                reasoning.resizeRelocate(0, 0, w, 44);
                y = 48;
            }
            text.resizeRelocate(0, y, w, h - y - 18);
            stats.resizeRelocate(0, h - 16, w, 16);
        }

        @Override
        public void reset() {
            text.clear();
            reasoning.setText("");
            reasoning.setVisible(false);
            stats.setText("…");
            requestLayout();
        }

        @Override
        public void status(NodeStatus status, String message) {
            if (status == NodeStatus.ERROR) {
                stats.getStyleClass().setAll("error-text");
                stats.setText(message);
            } else stats.getStyleClass().setAll("body-muted");
        }

        @Override
        public void emit(String channel, Object payload) {
            switch (channel) {
                case "text" -> text.appendText((String) payload);
                case "text-reasoning" -> {
                    reasoning.setVisible(true);
                    String all = reasoning.getText() + payload;
                    reasoning.setText("thinking: " + (all.length() > 400 ? "…" + all.substring(all.length() - 400) : all).replace("thinking: ", ""));
                    requestLayout();
                }
                case "text-stats" -> stats.setText(statsLine((OpenAiClient.Reply) payload));
                default -> {
                }
            }
        }
    }

    /** Two answers side by side. */
    static final class DuelBody extends NodeBody {
        private final Label nameA = new Label("model A"), nameB = new Label("model B");
        private final TextArea a = new TextArea(), b = new TextArea();
        private final Label statsA = new Label(), statsB = new Label();

        DuelBody() {
            for (var t : List.of(a, b)) {
                t.setEditable(false);
                t.setWrapText(true);
            }
            for (var l : List.of(nameA, nameB)) l.setStyle("-fx-text-fill: #ffd8a8; -fx-font-weight: bold; -fx-font-size: 11px;");
            for (var l : List.of(statsA, statsB)) l.getStyleClass().add("body-muted");
            getChildren().addAll(nameA, nameB, a, b, statsA, statsB);
        }

        @Override
        public double preferredHeight() {
            return 260;
        }

        @Override
        public boolean interactive() {
            return true;
        }

        @Override
        protected void layoutChildren() {
            double w = (getWidth() - 8) / 2, h = getHeight();
            nameA.resizeRelocate(0, 0, w, 16);
            nameB.resizeRelocate(w + 8, 0, w, 16);
            a.resizeRelocate(0, 18, w, h - 38);
            b.resizeRelocate(w + 8, 18, w, h - 38);
            statsA.resizeRelocate(0, h - 17, w, 16);
            statsB.resizeRelocate(w + 8, h - 17, w, 16);
        }

        @Override
        public void reset() {
            a.clear();
            b.clear();
            statsA.setText("…");
            statsB.setText("…");
        }

        @Override
        public void emit(String channel, Object payload) {
            switch (channel) {
                case "names" -> {
                    var names = (List<?>) payload;
                    nameA.setText(String.valueOf(names.get(0)));
                    nameB.setText(String.valueOf(names.get(1)));
                }
                case "a" -> a.appendText((String) payload);
                case "b" -> b.appendText((String) payload);
                case "a-stats" -> statsA.setText(statsLine((OpenAiClient.Reply) payload));
                case "b-stats" -> statsB.setText(statsLine((OpenAiClient.Reply) payload));
                default -> {
                }
            }
        }
    }

    /**
     * A chat inside the node: type, press Enter, the answer streams in. The system prompt and the
     * engine come from the node's settings; the text connected to the node's input (after the
     * workflow has run) is appended to the system prompt as context.
     */
    static final class ChatBody extends NodeBody {
        private final VBox transcript = new VBox(6);
        private final ScrollPane scroll = new ScrollPane(transcript);
        private final TextField input = new TextField();
        private final Button send = new Button("Send");
        private final Label status = new Label("type a message and press Enter");
        private final List<OpenAiClient.Message> history = new ArrayList<>();
        private volatile boolean busy;
        private Thread worker;

        ChatBody() {
            transcript.setPadding(new Insets(4));
            scroll.setFitToWidth(true);
            scroll.setStyle("-fx-background: #181a20; -fx-background-color: #181a20;");
            input.setPromptText("Ask something…");
            input.setOnAction(e -> sendMessage());
            send.setOnAction(e -> {
                if (busy && worker != null) worker.interrupt();
                else sendMessage();
            });
            status.getStyleClass().add("body-muted");
            transcript.heightProperty().addListener((o, x, v) -> scroll.setVvalue(1));
            getChildren().addAll(scroll, input, send, status);
        }

        @Override
        public double preferredHeight() {
            return 300;
        }

        @Override
        public boolean interactive() {
            return true;
        }

        @Override
        protected void layoutChildren() {
            double w = getWidth(), h = getHeight();
            scroll.resizeRelocate(0, 0, w, h - 52);
            input.resizeRelocate(0, h - 48, w - 64, 28);
            send.resizeRelocate(w - 60, h - 48, 60, 28);
            status.resizeRelocate(0, h - 16, w, 16);
        }

        private Label bubble(String text, boolean user) {
            var l = new Label(text);
            l.setWrapText(true);
            l.setMaxWidth(Double.MAX_VALUE);
            l.setStyle(user ? "-fx-background-color: #1c3d5a; -fx-text-fill: #e7f5ff; -fx-padding: 5 8; -fx-background-radius: 8;"
                            : "-fx-background-color: #2b2f3a; -fx-text-fill: #e9ecef; -fx-padding: 5 8; -fx-background-radius: 8;");
            transcript.getChildren().add(l);
            return l;
        }

        @Override
        public boolean submit(String text) {
            input.setText(text);
            sendMessage();
            return true;
        }

        private void sendMessage() {
            String msg = input.getText().strip();
            if (msg.isEmpty() || busy) return;
            input.clear();
            bubble(msg, true);
            var answer = bubble("…", false);
            history.add(OpenAiClient.Message.user(msg));
            busy = true;
            send.setText("Stop");
            String system = String.valueOf(context.param("system"));
            Object ctxText = context.lastOutputs().get("context");
            if (ctxText != null && !ctxText.toString().isBlank()) system += "\n\nContext:\n" + ctxText;
            var messages = new ArrayList<OpenAiClient.Message>();
            messages.add(OpenAiClient.Message.system(system));
            messages.addAll(history);
            var opts = new OpenAiClient.Options(((Number) context.param("temperature")).doubleValue(),
                                                (int) Math.round(((Number) context.param("maxTokens")).doubleValue()),
                                                (Boolean) context.param("thinking"), 0);
            String engineId = String.valueOf(context.param("engine"));
            worker = Thread.ofVirtual().name("chat-" + context.nodeId()).start(() -> {
                var sb = new StringBuilder();
                try {
                    var engine = EngineManager.instance().pickLlm(engineId);
                    OpenAiClient.Reply r;
                    try (var _ = engine.use()) {     // marks the engine busy: it is never evicted mid-answer
                        if (!engine.state().isUp()) {
                            Platform.runLater(() -> status.setText("starting " + engine.spec().name() + "…"));
                            engine.awaitReady(java.time.Duration.ofSeconds(150));
                        }
                        Platform.runLater(() -> status.setText(engine.spec().name() + " is answering…"));
                        var pending = new StringBuilder();
                        r = new OpenAiClient(engine.spec().baseUrl()).chat(messages, opts, piece -> {
                            sb.append(piece);
                            synchronized (pending) {
                                boolean schedule = pending.isEmpty();
                                pending.append(piece);
                                if (schedule) Platform.runLater(() -> {
                                    synchronized (pending) {
                                        answer.setText(sb.toString());
                                        pending.setLength(0);
                                    }
                                });
                            }
                        }, () -> Thread.currentThread().isInterrupted());
                    }
                    history.add(OpenAiClient.Message.assistant(r.content()));
                    Platform.runLater(() -> {
                        answer.setText(r.content());
                        status.setText(statsLine(r));
                    });
                } catch (Exception ex) {
                    history.removeLast();
                    String why = ex instanceof java.util.concurrent.CancellationException ? "stopped" : ex.getMessage();
                    Platform.runLater(() -> {
                        answer.setText(sb.isEmpty() ? "(" + why + ")" : sb + " …(" + why + ")");
                        status.setText(why);
                    });
                } finally {
                    busy = false;
                    Platform.runLater(() -> send.setText("Send"));
                }
            });
        }
    }
}
