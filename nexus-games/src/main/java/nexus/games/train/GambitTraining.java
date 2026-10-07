package nexus.games.train;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import nexus.engines.system.VramBroker;

/**
 * AlphaZero self-play training, run by Gambit's own trainer (C#, TorchSharp on CUDA) and supervised
 * from NEXUS:
 * <ul>
 *   <li>the trainer leases its GPU memory from the {@link VramBroker} as a <b>background</b> job;</li>
 *   <li>when interactive work needs the memory (a chat model starting), the broker <b>pauses</b> it:
 *       the process is stopped and the lease returned; as soon as memory is free again it is
 *       restarted, and Gambit resumes after the last finished generation (it saves everything in
 *       the run folder) - at most the generation in progress is lost;</li>
 *   <li>progress comes from the run's {@code log.jsonl} (one JSON record per generation: losses,
 *       self-play speed, the gating match, Elo), and every generation's network is exported as a
 *       {@code .gnet} that NEXUS can load at once.</li>
 * </ul>
 */
public final class GambitTraining {
    /** One finished generation, as Gambit logs it. */
    public record Generation(int generation, int best, boolean accepted, double arenaScore, String arena, int games, double gamesPerSecond,
                             double simulationsPerSecond, double policyLoss, double valueLoss, double elo, Double solverKeepsResult,
                             double elapsedSeconds) {
    }

    public record Options(Path gambit, String game, Path run, int generations, int games, int parallel, int simulations) {
        /** GPU memory the trainer needs: measured ~650 MiB at 512 parallel games, growing with the batch. */
        public int vramMiB() {
            return 400 + (int) (parallel * 0.6);
        }

        List<String> command() {
            return List.of(gambit.toString(), "train", "--game", game, "--run", run.toString(), "--generations", String.valueOf(generations),
                           "--games", String.valueOf(games), "--parallel", String.valueOf(parallel), "--sims", String.valueOf(simulations));
        }
    }

    public interface Listener {
        void line(String text);

        void generation(Generation g);

        /** "running", "waiting for GPU memory", "paused: ... ", "finished", ... */
        void state(String text);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Options options;
    private final VramBroker broker;
    private volatile Process process;
    private volatile boolean paused;
    private volatile String pausedFor;
    private volatile Instant lastProgress = Instant.now();

    public GambitTraining(Options options, VramBroker broker) {
        this.options = options;
        this.broker = broker;
    }

    /** The default location of Gambit's command-line tool inside the projects folder. */
    public static Path defaultExecutable(Path projects) {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        return projects.resolve("Gambit/src/Gambit.Cli/bin/Release/net9.0").resolve(win ? "gambit.exe" : "gambit");
    }

    /** Generations already in the run's log (a resumed run shows its history at once). */
    public static List<Generation> readLog(Path run) throws IOException {
        var out = new ArrayList<Generation>();
        var f = run.resolve("log.jsonl");
        if (!Files.exists(f)) return out;
        for (var line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            var n = JSON.readTree(line);
            out.add(new Generation(n.path("Generation").asInt(), n.path("BestGeneration").asInt(), n.path("Accepted").asBoolean(),
                                   n.path("ArenaScore").asDouble(), n.path("Arena").asText(), n.path("Games").asInt(), n.path("GamesPerSecond").asDouble(),
                                   n.path("SimulationsPerSecond").asDouble(), n.path("PolicyLoss").asDouble(), n.path("ValueLoss").asDouble(),
                                   n.path("Elo").asDouble(), n.path("SolverKeepsResult").isNumber() ? n.path("SolverKeepsResult").asDouble() : null,
                                   n.path("ElapsedSeconds").asDouble()));
        }
        return out;
    }

    /** The holder the broker sees: pausable, low priority. */
    private final VramBroker.Holder holder = new VramBroker.Holder() {
        @Override
        public String name() {
            return "Gambit training (" + options.game() + ")";
        }

        @Override
        public VramBroker.Preemption preemption() {
            return VramBroker.Preemption.PAUSE;
        }

        @Override
        public Instant lastUsed() {
            return lastProgress;
        }

        @Override
        public void preempt(String requester) {
            pausedFor = requester;
            paused = true;
            var p = process;
            if (p != null) kill(p);
        }
    };

    private static void kill(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    /**
     * Trains until the requested generations are done, pausing and resuming as the broker decides.
     *
     * @return the path of the best network ({@code <game>-best.gnet} in the run folder)
     */
    public Path run(Listener listener, BooleanSupplier cancelled) throws Exception {
        if (!Files.isRegularFile(options.gambit()))
            throw new IOException("Gambit's trainer was not found at " + options.gambit() + " (build it: dotnet build -c Release in Gambit)");
        Files.createDirectories(options.run());
        int seen = 0;
        for (var g : readLog(options.run())) {
            listener.generation(g);
            seen++;
        }
        while (true) {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("training stopped");
            if (seen >= options.generations()) break;
            VramBroker.Lease lease = null;
            if (broker != null) {
                listener.state(paused ? "paused: GPU memory lent to " + pausedFor + " - waiting for it back" : "waiting for GPU memory");
                lease = acquire(cancelled);
            }
            paused = false;
            try {
                listener.state(seen > 0 ? "running (resumed after generation " + seen + ")" : "running");
                var pb = new ProcessBuilder(options.command()).redirectErrorStream(true);
                pb.directory(options.gambit().getParent().toFile());
                var p = pb.start();
                process = p;
                if (lease != null) lease.markActive();
                var reader = Thread.ofVirtual().start(() -> {
                    try (var r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                        for (String line; (line = r.readLine()) != null; ) {
                            lastProgress = Instant.now();
                            listener.line(line);
                        }
                    } catch (IOException ignored) {
                        // process ended
                    }
                });
                while (!p.waitFor(1, TimeUnit.SECONDS)) {
                    if (cancelled.getAsBoolean()) {
                        kill(p);
                        throw new java.util.concurrent.CancellationException("training stopped");
                    }
                    var gens = readLog(options.run());
                    for (; seen < gens.size(); seen++) listener.generation(gens.get(seen));
                }
                reader.join(2000);
                var gens = readLog(options.run());
                for (; seen < gens.size(); seen++) listener.generation(gens.get(seen));
                if (paused) continue;                      // stopped by the broker: wait for memory, then resume
                if (p.exitValue() != 0) throw new IOException("Gambit's trainer exited with code " + p.exitValue());
            } finally {
                process = null;
                if (lease != null) lease.close();
            }
        }
        listener.state("finished: " + seen + " generations");
        return options.run().resolve(options.game() + "-best.gnet");
    }

    private VramBroker.Lease acquire(BooleanSupplier cancelled) throws Exception {
        while (true) {
            try {
                return broker.acquire(holder, options.vramMiB(), VramBroker.Priority.BACKGROUND, Duration.ofSeconds(5));
            } catch (java.util.concurrent.TimeoutException e) {
                if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("training stopped");
                // keep waiting: background work waits as long as it must
            }
        }
    }

    public boolean paused() {
        return paused;
    }
}
