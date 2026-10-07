package nexus.engines.train;

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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import nexus.engines.system.VramBroker;

/**
 * Trains Kindling's GPT (a 14M-parameter Romanian language model) with one of two frameworks, both
 * the user's own projects:
 * <ul>
 *   <li><b>Anvil</b> - a deep-learning framework written from scratch (CuPy, custom CUDA kernels,
 *       FlashAttention); it cannot resume a run, so its memory is never taken back;</li>
 *   <li><b>PyTorch</b> - Kindling's original training script; it saves {@code last.pt} at every
 *       evaluation and resumes from it, so the broker may pause it for interactive work.</li>
 * </ul>
 * Both print the same log format, parsed here into live points: training loss and throughput
 * every few steps, train/validation loss at every evaluation.
 */
public final class GptTraining {
    public enum Framework {
        ANVIL("Anvil", 1300, VramBroker.Preemption.NEVER),
        PYTORCH("PyTorch", 2700, VramBroker.Preemption.PAUSE);

        public final String label;
        final int vramMiB;
        final VramBroker.Preemption preemption;

        Framework(String label, int vramMiB, VramBroker.Preemption preemption) {
            this.label = label;
            this.vramMiB = vramMiB;
            this.preemption = preemption;
        }
    }

    public record Options(Framework framework, Path projects, Path out, int steps, int evalInterval, int batchSize) {
    }

    /** A training-loss point. {@code seconds} is wall-clock time since the start of the run. */
    public record Point(String framework, int step, double loss, double tokensPerSecond, double seconds) {
    }

    /** An evaluation: mean losses over fixed batches. */
    public record Eval(String framework, int step, double train, double val, double seconds) {
        public double perplexity() {
            return Math.exp(val);
        }
    }

    public interface Listener {
        void line(String text);

        void point(Point p);

        void eval(Eval e);

        void state(String text);
    }

    static final Pattern STEP = Pattern.compile("^step\\s+(\\d+) \\| loss ([\\d.]+) .*\\|\\s+([\\d.]+)k tok/s");
    static final Pattern EVAL = Pattern.compile("^\\[eval] step\\s+(\\d+) \\| train ([\\d.]+) \\| val ([\\d.]+)");

    private static final Set<Process> LIVE = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> LIVE.forEach(GptTraining::kill), "gpt-training-shutdown"));
    }

    private final Options o;
    private final VramBroker broker;
    private volatile Process process;
    private volatile boolean paused;
    private volatile String pausedFor;
    private volatile Instant lastProgress = Instant.now();

    public GptTraining(Options options, VramBroker broker) {
        this.o = options;
        this.broker = broker;
    }

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    static Path python(Path project) {
        return project.resolve(windows() ? ".venv/Scripts/python.exe" : ".venv/bin/python");
    }

    List<String> command(boolean resume) {
        Path kindling = o.projects().resolve("Kindling"), anvil = o.projects().resolve("Anvil");
        var common = List.of("--max_steps", String.valueOf(o.steps()), "--out_dir", o.out().toString(), "--eval_interval",
                             String.valueOf(o.evalInterval()), "--log_interval", "25", "--batch_size", String.valueOf(o.batchSize()));
        var cmd = new ArrayList<String>();
        if (o.framework() == Framework.ANVIL) {
            cmd.addAll(List.of(python(anvil).toString(), "-u", anvil.resolve("examples/gpt/train.py").toString(), "--data_dir",
                               kindling.resolve("data").toString()));
        } else {
            cmd.addAll(List.of(python(kindling).toString(), "-u", kindling.resolve("train.py").toString(), "--data_dir",
                               kindling.resolve("data").toString()));
            if (resume) cmd.addAll(List.of("--resume", "true"));
        }
        cmd.addAll(common);
        return cmd;
    }

    Path workDir() {
        return o.projects().resolve(o.framework() == Framework.ANVIL ? "Anvil" : "Kindling");
    }

    private final VramBroker.Holder holder = new VramBroker.Holder() {
        @Override
        public String name() {
            return "GPT training (" + o.framework().label + ")";
        }

        @Override
        public VramBroker.Preemption preemption() {
            return o.framework().preemption;
        }

        @Override
        public Instant lastUsed() {
            return lastProgress;
        }

        @Override
        public void preempt(String requester) {
            if (o.framework().preemption != VramBroker.Preemption.PAUSE) return;
            pausedFor = requester;
            paused = true;
            var p = process;
            if (p != null) kill(p);
        }
    };

    static void kill(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    /** Trains to the end (pausing and resuming if the broker asks); returns the evaluations. */
    public List<Eval> run(Listener l, BooleanSupplier cancelled) throws Exception {
        Path py = o.framework() == Framework.ANVIL ? python(o.projects().resolve("Anvil")) : python(o.projects().resolve("Kindling"));
        if (!Files.isRegularFile(py)) throw new IOException("no Python environment at " + py + " (see the project's README)");
        Files.createDirectories(o.out());
        var evals = new ArrayList<Eval>();
        long start = System.nanoTime();
        boolean resume = false;
        while (true) {
            VramBroker.Lease lease = null;
            if (broker != null) {
                l.state(paused ? "paused: GPU memory lent to " + pausedFor + " - waiting for it back" : "waiting for GPU memory");
                lease = broker.acquire(holder, o.framework().vramMiB, VramBroker.Priority.BACKGROUND, Duration.ofDays(7), cancelled);
            }
            paused = false;
            l.state(resume ? "running (resumed from the last checkpoint)" : "running");
            try {
                var pb = new ProcessBuilder(command(resume)).redirectErrorStream(true).directory(workDir().toFile());
                pb.environment().putAll(Map.of("PYTHONIOENCODING", "utf-8", "PYTHONUNBUFFERED", "1"));
                var p = pb.start();
                process = p;
                LIVE.add(p);
                if (lease != null) lease.markActive();
                try (var r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = r.readLine()) != null; ) {
                        if (cancelled.getAsBoolean()) {
                            kill(p);
                            throw new CancellationException("training stopped");
                        }
                        lastProgress = Instant.now();
                        l.line(line);
                        double t = (System.nanoTime() - start) / 1e9;
                        var m = STEP.matcher(line);
                        if (m.find()) l.point(new Point(o.framework().label, Integer.parseInt(m.group(1)), Double.parseDouble(m.group(2)),
                                                        Double.parseDouble(m.group(3)) * 1000, t));
                        var e = EVAL.matcher(line);
                        if (e.find()) {
                            var ev = new Eval(o.framework().label, Integer.parseInt(e.group(1)), Double.parseDouble(e.group(2)),
                                              Double.parseDouble(e.group(3)), t);
                            evals.removeIf(x -> x.step() >= ev.step());
                            evals.add(ev);
                            l.eval(ev);
                        }
                    }
                }
                p.waitFor(10, TimeUnit.SECONDS);
                if (paused) {
                    resume = true;
                    continue;
                }
                if (cancelled.getAsBoolean()) throw new CancellationException("training stopped");
                if (p.exitValue() != 0) throw new IOException(o.framework().label + " training exited with code " + p.exitValue());
                l.state("finished");
                return evals;
            } finally {
                var p = process;
                if (p != null) {
                    if (p.isAlive()) kill(p);
                    LIVE.remove(p);
                }
                process = null;
                if (lease != null) lease.close();
            }
        }
    }
}
