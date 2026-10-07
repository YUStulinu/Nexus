package nexus.engines;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A stand-in for Ember in tests: /health, and /v1/chat/completions streaming a fixed answer word by
 * word as server-sent events (with a usage chunk). Run as its own process by the supervisor tests.
 *
 * A minimal HTTP/1.1 server on a plain socket (one request per connection), so the tests need
 * nothing outside java.base.
 */
public final class FakeLlmServer {
    private FakeLlmServer() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int startupDelayMs = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        Thread.sleep(startupDelayMs);
        try (var server = new ServerSocket(port, 50, InetAddress.getLoopbackAddress())) {
            System.out.println("fake server ready on " + port);
            while (true) {
                Socket s = server.accept();
                Thread.ofVirtual().start(() -> serve(s));
            }
        }
    }

    private static void serve(Socket socket) {
        try (socket) {
            var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = in.readLine();
            if (requestLine == null) return;
            int contentLength = 0;
            for (String h; (h = in.readLine()) != null && !h.isEmpty(); )
                if (h.toLowerCase().startsWith("content-length:")) contentLength = Integer.parseInt(h.substring(15).strip());
            for (int i = 0; i < contentLength; i++) in.read();          // the request body is not needed
            String path = requestLine.split(" ")[1];
            OutputStream o = socket.getOutputStream();
            if (path.equals("/health")) {
                byte[] ok = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                o.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + ok.length + "\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.UTF_8));
                o.write(ok);
            } else if (path.equals("/v1/chat/completions")) {
                o.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                send(o, "{\"model\":\"fake\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}");
                send(o, "{\"model\":\"fake\",\"choices\":[{\"delta\":{\"reasoning_content\":\"hmm\"}}]}");
                for (var w : new String[]{"Bună", " ziua", ",", " lume", "!"}) {
                    send(o, "{\"model\":\"fake\",\"choices\":[{\"delta\":{\"content\":\"" + w + "\"}}]}");
                    sleep(20);
                }
                send(o, "{\"model\":\"fake\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");
                send(o, "{\"model\":\"fake\",\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":5}}");
                o.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            } else {
                o.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            }
            o.flush();
        } catch (IOException ignored) {
            // the client went away
        }
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
