package nexus.engines;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * How to run one engine.
 *
 * @param id             stable identifier ("ember-1.7b")
 * @param name           shown in the UI
 * @param kind           what the engine is for
 * @param command        executable and arguments
 * @param workDir        working directory of the process
 * @param port           the TCP port it serves on (0 for engines without a server)
 * @param healthPath     an HTTP path answering 200 when ready (e.g. "/health"), or null
 * @param startupTimeout how long to wait for the health check to pass
 * @param env            extra environment variables
 * @param vramMiB        an estimate of the GPU memory it holds while running (for the GPU broker)
 * @param description    one line for the UI
 */
public record EngineSpec(String id, String name, Kind kind, List<String> command, Path workDir, int port, String healthPath,
                         Duration startupTimeout, Map<String, String> env, int vramMiB, String description) {

    public enum Kind {
        /** An OpenAI-compatible chat server. */
        LLM,
        /** A long-running job (training) that reports progress on its output. */
        JOB
    }

    public EngineSpec {
        command = List.copyOf(command);
        env = Map.copyOf(env);
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    /**
     * The executable exists, so the engine can be started on this machine. A bare name ("java") is
     * left to the PATH; on Windows "X" also matches "X.exe", as for ProcessBuilder.
     */
    public boolean available() {
        if (command.isEmpty()) return false;
        Path exe = Path.of(command.getFirst());
        if (!exe.isAbsolute()) return true;
        if (exe.toFile().isFile()) return true;
        return System.getProperty("os.name", "").toLowerCase().contains("win") && Path.of(command.getFirst() + ".exe").toFile().isFile();
    }
}
