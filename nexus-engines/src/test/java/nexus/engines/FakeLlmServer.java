package nexus.engines;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * A stand-in for Ember in tests: /health, and /v1/chat/completions streaming a fixed answer word by
 * word as server-sent events (with a usage chunk). Run as its own process by the supervisor tests.
 */
public final class FakeLlmServer {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int startupDelayMs = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        Thread.sleep(startupDelayMs);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/health", ex -> {
            byte[] ok = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, ok.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(ok);
            }
        });
        server.createContext("/v1/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream o = ex.getResponseBody()) {
                send(o, "{\"model\":\"fake\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}");
                send(o, "{\"model\":\"fake\",\"choices\":[{\"delta\":{\"reasoning_content\":\"hmm\"}}]}");
                for (var w : new String[]{"Bună", " ziua", ",", " lume", "!"}) {
                    send(o, "{\"model\":\"fake\",\"choices\":[{\"delta\":{\"content\":\"" + w + "\"}}]}");
                    sleep(20);
                }
                send(o, "{\"model\":\"fake\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");
                send(o, "{\"model\":\"fake\",\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":5}}");
                o.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        System.out.println("fake server ready on " + port);
    }

    private static void send(OutputStream o, String json) throws IOException {
        o.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        o.flush();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
