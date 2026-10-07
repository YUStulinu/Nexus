package nexus.engines;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import nexus.engines.system.GpuProbe;
import nexus.engines.system.SystemMonitor;
import nexus.engines.system.VramBroker;

/**
 * All the engines NEXUS knows about, and where the sibling projects are.
 *
 * <p>The projects (Ember, Kindling, Anvil, Gambit) are looked for in the folder given by the
 * {@code NEXUS_PROJECTS} environment variable or the {@code nexus.projects} system property, else in
 * the nearest folder above the working directory that contains them (the layout of ProiecteVSCode,
 * where NEXUS sits next to them); that folder is remembered in ~/.nexus/projects.txt for the
 * packaged application, which may be started from anywhere. Engines whose executables are missing are still listed, marked unavailable, so the UI can
 * explain what to build.
 *
 * <p>It also owns the {@link SystemMonitor} (GPU, CPU, memory) and the {@link VramBroker} through
 * which every engine leases its GPU memory.
 *
 * <p>A JVM shutdown hook stops every process NEXUS started.
 */
public final class EngineManager implements AutoCloseable {
    private static volatile EngineManager instance;

    private final Path projects;
    private final Map<String, Engine> engines = new LinkedHashMap<>();
    private final List<Consumer<Engine>> listeners = new CopyOnWriteArrayList<>();
    private volatile SystemMonitor monitor;
    private volatile VramBroker broker;
    /** Kept free on the GPU for the display, the driver and allocation slack. */
    public static final int VRAM_MARGIN_MIB = 300;

    /** The application-wide manager (created on first use). */
    public static EngineManager instance() {
        var m = instance;
        if (m == null) {
            synchronized (EngineManager.class) {
                if (instance == null) instance = new EngineManager(locateProjects());
                m = instance;
            }
        }
        return m;
    }

    public EngineManager(Path projects) {
        this(projects, true);
    }

    /** @param coordinateGpu whether engines lease GPU memory through a broker (false in tests) */
    public EngineManager(Path projects, boolean coordinateGpu) {
        this.projects = projects;
        if (coordinateGpu) {
            monitor = new SystemMonitor(GpuProbe.detect(), java.time.Duration.ofSeconds(1));
            var probe = monitor.probe();
            broker = new VramBroker(probe::sample, VRAM_MARGIN_MIB);
        }
        for (var spec : defaultSpecs(projects)) add(spec);
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "nexus-engines-shutdown"));
    }

    public static Path locateProjects() {
        String p = System.getenv("NEXUS_PROJECTS");
        if (p == null || p.isBlank()) p = System.getProperty("nexus.projects");
        if (p != null && !p.isBlank()) return Path.of(p).toAbsolutePath();
        Path cwd = Path.of("").toAbsolutePath();
        Path remembered = Path.of(System.getProperty("user.home"), ".nexus", "projects.txt");
        for (Path dir = cwd; dir != null; dir = dir.getParent())
            if (Files.isDirectory(dir.resolve("Ember")) || Files.isDirectory(dir.resolve("Gambit"))) {
                // Remember it, so that the packaged application (started from anywhere) finds the projects too.
                try {
                    Files.createDirectories(remembered.getParent());
                    Files.writeString(remembered, dir.toString());
                } catch (java.io.IOException ignored) {
                    // only a convenience
                }
                return dir;
            }
        try {
            if (Files.isRegularFile(remembered)) {
                Path dir = Path.of(Files.readString(remembered).strip());
                if (Files.isDirectory(dir)) return dir;
            }
        } catch (java.io.IOException | java.nio.file.InvalidPathException ignored) {
            // fall through
        }
        return cwd.getParent() != null ? cwd.getParent() : cwd;
    }

    /** The machine monitor (started on first call), or null if GPU coordination is off. */
    public SystemMonitor monitor() {
        var m = monitor;
        if (m != null) m.start();
        return m;
    }

    /** The GPU memory broker, or null if coordination is off. */
    public VramBroker broker() {
        return broker;
    }

    public Path projects() {
        return projects;
    }

    public Path project(String name) {
        return projects.resolve(name);
    }

    /** The engines NEXUS can run out of the box (if the sibling projects are built). */
    static List<EngineSpec> defaultSpecs(Path projects) {
        var specs = new ArrayList<EngineSpec>();
        Path ember = projects.resolve("Ember");
        Path exe = ember.resolve(isWindows() ? "build/Release/ember.exe" : "build/ember");
        // Each instance gets an explicit KV-cache budget (instead of Ember's default of most of the
        // card), so both fit on a 6 GB GPU together and leave room for training jobs:
        // 1.7B = 1275 MiB weights + 230 buffers + 640 KV (5800 tokens); 0.6B = 465 + 158 + 384.
        record Model(String id, String dir, String name, int port, int kvMiB, int vram) {
        }
        for (var m : List.of(new Model("ember-1.7b", "Qwen3-1.7B", "Ember · Qwen3-1.7B", 8090, 640, 2150),
                             new Model("ember-0.6b", "Qwen3-0.6B", "Ember · Qwen3-0.6B", 8091, 384, 1010))) {
            specs.add(new EngineSpec(m.id(), m.name(), EngineSpec.Kind.LLM,
                    List.of(exe.toString(), "serve", "-m", ember.resolve("models").resolve(m.dir()).toString(), "-q", "int4",
                            "--kv-cache-mib", String.valueOf(m.kvMiB()), "--port", String.valueOf(m.port())),
                    ember, m.port(), "/health", Duration.ofSeconds(90), Map.of(), m.vram(),
                    "LLM inference engine in C++/CUDA (" + m.dir() + ", int4 weights)"));
        }
        // Kindling: the small Romanian GPT, behind an OpenAI-compatible server written for NEXUS.
        Path kindling = projects.resolve("Kindling");
        Path python = kindling.resolve(isWindows() ? ".venv/Scripts/python.exe" : ".venv/bin/python");
        Path server = nexusDir(projects).resolve("engines/kindling/kindling_server.py");
        specs.add(new EngineSpec("kindling", "Kindling · Romanian GPT (10.6M)", EngineSpec.Kind.LLM,
                List.of(python.toString(), "-u", server.toString(), "--kindling", kindling.toString(), "--ckpt", "runs/rope/best.pt",
                        "--port", "8092"),
                kindling, 8092, "/health", Duration.ofSeconds(90), Map.of("PYTHONIOENCODING", "utf-8"), 400,
                "A 10.6M-parameter GPT trained from scratch on Romanian text (PyTorch); continues text rather than following instructions"));
        return specs;
    }

    /** The NEXUS folder (where engines/ lives): the working directory, or projects/Nexus. */
    static Path nexusDir(Path projects) {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.isDirectory(cwd.resolve("engines"))) return cwd;
        return projects.resolve("Nexus");
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public synchronized Engine add(EngineSpec spec) {
        var e = new Engine(spec);
        e.setBroker(broker);
        e.addListener(en -> listeners.forEach(l -> l.accept(en)));
        engines.put(spec.id(), e);
        return e;
    }

    public synchronized Collection<Engine> all() {
        return List.copyOf(engines.values());
    }

    public synchronized List<Engine> ofKind(EngineSpec.Kind kind) {
        return engines.values().stream().filter(e -> e.spec().kind() == kind).toList();
    }

    public synchronized Optional<Engine> find(String id) {
        return Optional.ofNullable(engines.get(id));
    }

    public Engine get(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("unknown engine '" + id + "'"));
    }

    /** Notified whenever any engine changes state. */
    public void addListener(Consumer<Engine> l) {
        listeners.add(l);
    }

    /**
     * The LLM engine to use for "auto": an instruction-following model that is already up, else the
     * first available one (Kindling, a base model that only continues text, is chosen only by name).
     */
    public Engine pickLlm(String requested) {
        if (requested != null && !requested.isBlank() && !requested.equals("auto")) return get(requested);
        var llms = ofKind(EngineSpec.Kind.LLM).stream().filter(e -> !e.spec().id().equals("kindling")).toList();
        return llms.stream().filter(e -> e.state().isUp()).findFirst()
                   .or(() -> llms.stream().filter(e -> e.spec().available()).findFirst())
                   .orElseThrow(() -> new IllegalStateException("no language model engine is available: build Ember in "
                                                                + projects.resolve("Ember")));
    }

    @Override
    public void close() {
        for (var e : all()) {
            try {
                e.stop();
            } catch (Exception ignored) {
                // best effort at shutdown
            }
        }
        var m = monitor;
        if (m != null) m.close();
    }
}
