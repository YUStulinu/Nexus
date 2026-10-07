package nexus.engines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import nexus.engines.llm.OpenAiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EngineTest {
    final List<Engine> started = new ArrayList<>();

    @AfterEach
    void stopAll() {
        for (var e : started) e.stop();
    }

    static int freePort() throws Exception {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** A spec that runs FakeLlmServer in a separate JVM. */
    EngineSpec fakeSpec(int port, int startupDelayMs) {
        return fakeSpec(port, startupDelayMs, 0);
    }

    EngineSpec fakeSpec(int port, int startupDelayMs, int vramMiB) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("java.class.path");
        var spec = new EngineSpec("fake-" + port, "Fake LLM", EngineSpec.Kind.LLM,
                                  List.of(java, "-cp", cp, FakeLlmServer.class.getName(), String.valueOf(port), String.valueOf(startupDelayMs)),
                                  null, port, "/health", Duration.ofSeconds(30), Map.of(), vramMiB, "test server");
        return spec;
    }

    Engine engine(EngineSpec spec) {
        var e = new Engine(spec);
        started.add(e);
        return e;
    }

    @Test
    void starts_waits_for_health_and_streams_a_reply() throws Exception {
        int port = freePort();
        var e = engine(fakeSpec(port, 500));
        var states = new CopyOnWriteArrayList<EngineState>();
        e.addListener(en -> states.add(en.state()));
        e.awaitReady(Duration.ofSeconds(30));
        assertEquals(EngineState.READY, e.state());
        assertTrue(states.contains(EngineState.STARTING));
        assertTrue(e.pid() > 0);

        var pieces = new StringBuilder();
        var reasoning = new StringBuilder();
        var r = new OpenAiClient(e.spec().baseUrl()).chat(List.of(OpenAiClient.Message.user("salut")), OpenAiClient.Options.defaults(),
                new OpenAiClient.Listener() {
                    @Override
                    public void content(String piece) {
                        pieces.append(piece);
                    }

                    @Override
                    public void reasoning(String piece) {
                        reasoning.append(piece);
                    }
                }, () -> false);
        assertEquals("Bună ziua, lume!", r.content());
        assertEquals(r.content(), pieces.toString());
        assertEquals("hmm", reasoning.toString());
        assertEquals(5, r.completionTokens());
        assertEquals("stop", r.finishReason());
        assertTrue(r.ttftMs() > 0);
    }

    @Test
    void a_crashed_engine_is_restarted() throws Exception {
        int port = freePort();
        var e = engine(fakeSpec(port, 0));
        e.awaitReady(Duration.ofSeconds(30));
        long firstPid = e.pid();
        ProcessHandle.of(firstPid).orElseThrow().destroyForcibly();
        // the watcher notices within a few seconds, then restarts after a 2 s backoff
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < deadline && !(e.state() == EngineState.READY && e.pid() != firstPid)) Thread.sleep(200);
        assertEquals(EngineState.READY, e.state());
        assertTrue(e.pid() != firstPid);
        assertEquals(1, e.restartCount());
        assertTrue(e.logTail(200).stream().anyMatch(l -> l.contains("crash")));
    }

    @Test
    void stop_kills_the_process_and_attach_reuses_a_running_server() throws Exception {
        int port = freePort();
        var owner = engine(fakeSpec(port, 0));
        owner.awaitReady(Duration.ofSeconds(30));
        // A second engine on the same port attaches instead of starting another process.
        var second = engine(fakeSpec(port, 0));
        second.awaitReady(Duration.ofSeconds(10));
        assertEquals(EngineState.ATTACHED, second.state());
        assertEquals(-1, second.pid());
        long pid = owner.pid();
        owner.stop();
        assertEquals(EngineState.STOPPED, owner.state());
        assertTrue(ProcessHandle.of(pid).map(ph -> !ph.isAlive()).orElse(true));
        assertTrue(!owner.healthy());
    }

    @Test
    void a_missing_executable_fails_cleanly() {
        var spec = new EngineSpec("missing", "Missing", EngineSpec.Kind.LLM, List.of("C:/no/such/engine.exe"), null, 1, "/health",
                                  Duration.ofSeconds(5), Map.of(), 0, "");
        var e = engine(spec);
        try {
            e.awaitReady(Duration.ofSeconds(10));
        } catch (Exception ignored) {
        }
        assertEquals(EngineState.FAILED, e.state());
        assertTrue(e.lastError().contains("not found"));
    }

    /**
     * Two engines that do not fit on the (simulated) card together: starting the second stops the
     * first while it is idle, never while it is answering.
     */
    @Test
    void an_idle_engine_is_evicted_to_make_room_but_a_busy_one_is_not() throws Exception {
        var engines = new CopyOnWriteArrayList<Engine>();
        // A 6 GiB card: 700 MiB used by other programs, plus 3000 MiB for every engine that is up.
        java.util.function.Supplier<nexus.engines.system.GpuSample> gpu = () -> {
            long used = 700 + engines.stream().filter(x -> x.state().isUp()).mapToLong(x -> x.spec().vramMiB()).sum();
            return new nexus.engines.system.GpuSample("test", "Fake GPU", 6144, used, 6144 - used, 0, 0, 40, 10, 100, 0, 0, 0, List.of());
        };
        var broker = new nexus.engines.system.VramBroker(gpu, 300, Duration.ofMillis(300), Duration.ofSeconds(60));
        var a = engine(fakeSpec(freePort(), 0, 3000));
        var b = engine(fakeSpec(freePort(), 0, 3000));
        for (var e : List.of(a, b)) {
            e.setBroker(broker);
            engines.add(e);
        }
        a.awaitReady(Duration.ofSeconds(30));
        assertEquals(1, broker.state().leases().size());

        // a is answering: b cannot have its memory.
        try (var busy = a.use()) {
            var start = b.ensureStarted();
            Thread.sleep(1500);
            assertEquals(EngineState.STARTING, b.state(), "b waits for memory");
            assertEquals(EngineState.READY, a.state());
            assertEquals(1, broker.state().waiting().size());
            // a finishes its answer; once idle long enough it is stopped and b starts.
            busy.close();
            start.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(EngineState.READY, b.state());
        assertEquals(EngineState.STOPPED, a.state());
        assertEquals(b.spec().name(), a.evictedFor());
        assertEquals(List.of(b.spec().name()), broker.state().leases().stream().map(l -> l.holder()).toList());
        assertTrue(a.logTail(20).stream().anyMatch(l -> l.contains("stopping to free GPU memory")));

        // The next request to a starts it again, evicting b in turn.
        Thread.sleep(400);
        a.awaitReady(Duration.ofSeconds(30));
        assertEquals(EngineState.STOPPED, b.state());
    }
}
