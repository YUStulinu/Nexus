package nexus.engines.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * A minimal client for OpenAI-compatible chat servers (Ember, and anything else that speaks the
 * protocol), with server-sent-event streaming. Each delta is handed to a callback as it arrives;
 * the call returns the full reply with timing and token counts.
 */
public final class OpenAiClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                                                     .version(HttpClient.Version.HTTP_1_1).build();

    private final String baseUrl;

    public OpenAiClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public record Message(String role, String content) {
        public static Message system(String c) {
            return new Message("system", c);
        }

        public static Message user(String c) {
            return new Message("user", c);
        }

        public static Message assistant(String c) {
            return new Message("assistant", c);
        }
    }

    public record Options(double temperature, int maxTokens, boolean thinking, long seed) {
        public static Options defaults() {
            return new Options(0.7, 512, false, 0);
        }
    }

    /** Receives the reply as it is generated. */
    public interface Listener {
        void content(String piece);

        default void reasoning(String piece) {
        }
    }

    /**
     * @param ttftMs        time to the first token
     * @param tokensPerSecond decode speed (completion tokens over the time after the first token)
     */
    public record Reply(String content, String reasoning, String model, String finishReason, int promptTokens,
                        int completionTokens, double ttftMs, double totalMs, double tokensPerSecond) {
    }

    /** Streams a chat completion. {@code cancelled} is polled between chunks. */
    public Reply chat(List<Message> messages, Options o, Listener listener, BooleanSupplier cancelled) throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        var msgs = body.putArray("messages");
        for (var m : messages) msgs.addObject().put("role", m.role()).put("content", m.content());
        body.put("stream", true);
        body.put("max_tokens", o.maxTokens());
        body.put("temperature", o.temperature());
        if (o.seed() != 0) body.put("seed", o.seed());
        body.putObject("stream_options").put("include_usage", true);
        body.putObject("chat_template_kwargs").put("enable_thinking", o.thinking());

        var req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions"))
                             .timeout(Duration.ofMinutes(10))
                             .header("Content-Type", "application/json")
                             .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        long start = System.nanoTime();
        HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("HTTP " + resp.statusCode() + ": " + err);
        }
        var content = new StringBuilder();
        var reasoning = new StringBuilder();
        String model = "", finish = null;
        int promptTokens = 0, completionTokens = 0;
        long first = 0;
        try (var in = resp.body(); var r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("cancelled");
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.equals("[DONE]")) break;
                JsonNode chunk = JSON.readTree(data);
                if (chunk.has("model")) model = chunk.get("model").asText();
                if (chunk.has("usage") && chunk.get("usage").isObject()) {
                    promptTokens = chunk.get("usage").path("prompt_tokens").asInt();
                    completionTokens = chunk.get("usage").path("completion_tokens").asInt();
                }
                for (JsonNode choice : chunk.path("choices")) {
                    JsonNode delta = choice.path("delta");
                    String t = delta.path("content").asText("");
                    String th = delta.path("reasoning_content").asText("");
                    if ((!t.isEmpty() || !th.isEmpty()) && first == 0) first = System.nanoTime();
                    if (!th.isEmpty()) {
                        reasoning.append(th);
                        listener.reasoning(th);
                    }
                    if (!t.isEmpty()) {
                        content.append(t);
                        listener.content(t);
                    }
                    if (choice.hasNonNull("finish_reason")) finish = choice.get("finish_reason").asText();
                }
            }
        }
        long end = System.nanoTime();
        double ttft = first == 0 ? 0 : (first - start) / 1e6;
        double genSeconds = first == 0 ? 0 : (end - first) / 1e9;
        double tps = genSeconds > 0 && completionTokens > 1 ? (completionTokens - 1) / genSeconds : 0;
        return new Reply(content.toString(), reasoning.toString(), model, finish, promptTokens, completionTokens, ttft,
                         (end - start) / 1e6, tps);
    }

    /** The server's /api/stats document (Ember-specific; empty on other servers). */
    public JsonNode stats() {
        try {
            var req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/stats")).timeout(Duration.ofSeconds(2)).GET().build();
            var resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 ? JSON.readTree(resp.body()) : JSON.createObjectNode();
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }
}
