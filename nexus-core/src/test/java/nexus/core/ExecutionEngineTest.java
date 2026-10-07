package nexus.core;

import static nexus.core.graph.PortSpec.in;
import static nexus.core.graph.PortSpec.out;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import nexus.core.exec.ExecutionEngine;
import nexus.core.exec.ExecutionListener;
import nexus.core.exec.NodeStatus;
import nexus.core.exec.RunResult;
import nexus.core.exec.Services;
import nexus.core.graph.Edge;
import nexus.core.graph.Graph;
import nexus.core.graph.NodeDefinition;
import nexus.core.graph.ParamSpec;
import nexus.core.nodes.BasicNodes;
import nexus.core.registry.NodeRegistry;
import nexus.core.types.DataTypes;
import nexus.core.types.Table;
import nexus.core.types.TextStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ExecutionEngineTest {
    static final AtomicInteger EXECUTIONS = new AtomicInteger();

    /** Counts its executions and passes text through upper-cased. */
    static final NodeDefinition COUNTING = NodeDefinition.builder("test.count", "Count", "Test")
            .input(in("text", "text", DataTypes.TEXT)).output(out("text", "text", DataTypes.TEXT))
            .behavior(() -> ctx -> {
                EXECUTIONS.incrementAndGet();
                ctx.output("text", ctx.inputText("text").toUpperCase());
            });

    static final NodeDefinition FAILING = NodeDefinition.builder("test.fail", "Fail", "Test")
            .input(in("text", "text", DataTypes.TEXT)).output(out("text", "text", DataTypes.TEXT))
            .behavior(() -> ctx -> {
                throw new IllegalStateException("boom");
            });

    /** Publishes a stream immediately, then writes "a", "b", "c" slowly. */
    static final NodeDefinition STREAMING = NodeDefinition.builder("test.stream", "Stream", "Test")
            .output(out("text", "text", DataTypes.TEXT_STREAM))
            .behavior(() -> ctx -> {
                var s = new TextStream();
                ctx.output("text", s);
                for (var piece : List.of("a", "b", "c")) {
                    Thread.sleep(150);
                    s.append(piece);
                }
                s.complete();
            });

    /** Records when it started relative to the stream's completion. */
    static final Map<String, Long> STARTED = new ConcurrentHashMap<>();
    static final NodeDefinition FIRST_PIECE = NodeDefinition.builder("test.first", "First piece", "Test")
            .input(in("s", "s", DataTypes.TEXT_STREAM)).output(out("first", "first", DataTypes.TEXT))
            .param(ParamSpec.text("tag", "tag", "x", ""))
            .behavior(() -> ctx -> {
                STARTED.put(ctx.paramText("tag"), System.nanoTime());
                var s = (TextStream) ctx.input("s");
                var first = new java.util.concurrent.CompletableFuture<String>();
                s.subscribe(first::complete);
                ctx.output("first", first.get());
            });

    final NodeRegistry registry = new NodeRegistry().add(new BasicNodes()).add(COUNTING).add(FAILING).add(STREAMING).add(FIRST_PIECE);
    final ExecutionEngine engine = new ExecutionEngine(new Services());

    @AfterEach
    void close() {
        engine.close();
    }

    static class Recorder implements ExecutionListener {
        final Map<String, NodeStatus> last = new ConcurrentHashMap<>();
        final List<Object> emitted = new CopyOnWriteArrayList<>();

        @Override
        public void nodeStatus(String nodeId, NodeStatus status, String message) {
            last.put(nodeId, status);
        }

        @Override
        public void nodeEmit(String nodeId, String channel, Object payload) {
            emitted.add(payload);
        }
    }

    RunResult run(Graph g, Recorder r) {
        return engine.runAll(g.snapshot(), r).result().orTimeout(20, java.util.concurrent.TimeUnit.SECONDS).join();
    }

    @Test
    void runs_a_chain_and_delivers_converted_values() {
        var g = new Graph();
        var n = g.addNode(registry.get("number.input"), 0, 0);
        var f = g.addNode(registry.get("math.formula"), 0, 0);
        var up = g.addNode(registry.get("text.transform"), 0, 0);
        g.setParam(n.id(), "value", 20.0);
        g.setParam(f.id(), "expression", "sqrt(a) * 2 + 1");
        g.connect(new Edge(n.id(), "value", f.id(), "a"));
        g.connect(new Edge(f.id(), "value", up.id(), "text"));    // number -> text conversion
        var r = new Recorder();
        var result = run(g, r);
        assertTrue(result.success());
        assertEquals(Math.sqrt(20) * 2 + 1, (Double) engine.lastOutputs(f.id()).get("value"), 1e-12);
        assertEquals(String.valueOf(Math.sqrt(20) * 2 + 1), engine.lastOutputs(up.id()).get("text"));
    }

    @Test
    void independent_branches_run_in_parallel() {
        var g = new Graph();
        var t = g.addNode(registry.get("text.input"), 0, 0);
        var d1 = g.addNode(registry.get("util.delay"), 0, 0);
        var d2 = g.addNode(registry.get("util.delay"), 0, 0);
        var join = g.addNode(registry.get("text.join"), 0, 0);
        g.setParam(d1.id(), "seconds", 0.5);
        g.setParam(d2.id(), "seconds", 0.5);
        g.connect(new Edge(t.id(), "text", d1.id(), "in"));
        g.connect(new Edge(t.id(), "text", d2.id(), "in"));
        g.connect(new Edge(d1.id(), "out", join.id(), "a"));
        g.connect(new Edge(d2.id(), "out", join.id(), "b"));
        long start = System.nanoTime();
        var result = run(g, new Recorder());
        double seconds = (System.nanoTime() - start) / 1e9;
        assertTrue(result.success());
        assertTrue(seconds < 0.9, "two 0.5 s branches took " + seconds + " s: they did not overlap");
    }

    @Test
    void unchanged_nodes_are_served_from_the_cache() {
        var g = new Graph();
        var t = g.addNode(registry.get("text.input"), 0, 0);
        var c = g.addNode(COUNTING, 0, 0);
        g.connect(new Edge(t.id(), "text", c.id(), "text"));
        EXECUTIONS.set(0);
        var r = new Recorder();
        run(g, r);
        assertEquals(1, EXECUTIONS.get());
        run(g, r);
        assertEquals(1, EXECUTIONS.get());
        assertEquals(NodeStatus.CACHED, r.last.get(c.id()));
        g.setParam(t.id(), "text", "changed");                  // upstream change invalidates downstream
        run(g, r);
        assertEquals(2, EXECUTIONS.get());
        assertEquals("CHANGED", engine.lastOutputs(c.id()).get("text"));
    }

    @Test
    void a_failure_skips_what_depends_on_it_but_not_other_branches() {
        var g = new Graph();
        var t = g.addNode(registry.get("text.input"), 0, 0);
        var bad = g.addNode(FAILING, 0, 0);
        var after = g.addNode(registry.get("text.transform"), 0, 0);
        var other = g.addNode(registry.get("text.stats"), 0, 0);
        g.connect(new Edge(t.id(), "text", bad.id(), "text"));
        g.connect(new Edge(bad.id(), "text", after.id(), "text"));
        g.connect(new Edge(t.id(), "text", other.id(), "text"));
        var r = new Recorder();
        var result = run(g, r);
        assertTrue(!result.success());
        assertEquals(NodeStatus.ERROR, r.last.get(bad.id()));
        assertEquals(NodeStatus.SKIPPED, r.last.get(after.id()));
        assertEquals(NodeStatus.DONE, r.last.get(other.id()));
        assertTrue(engine.lastOutputs(other.id()).get("table") instanceof Table);
        assertEquals("boom", result.timings().stream().filter(x -> x.nodeId().equals(bad.id())).findFirst().orElseThrow().message());
    }

    @Test
    void a_stream_lets_consumers_start_before_the_producer_finishes() {
        var g = new Graph();
        var s = g.addNode(STREAMING, 0, 0);
        var first = g.addNode(FIRST_PIECE, 0, 0);
        var whole = g.addNode(registry.get("text.transform"), 0, 0);   // TEXT input: waits for the whole stream
        g.setParam(first.id(), "tag", "early");
        g.connect(new Edge(s.id(), "text", first.id(), "s"));
        g.connect(new Edge(s.id(), "text", whole.id(), "text"));
        var result = run(g, new Recorder());
        assertTrue(result.success());
        assertEquals("a", engine.lastOutputs(first.id()).get("first"));
        assertEquals("ABC", engine.lastOutputs(whole.id()).get("text"));
        var producer = result.timings().stream().filter(x -> x.nodeId().equals(s.id())).findFirst().orElseThrow();
        assertTrue(STARTED.get("early") < producer.endNanos(), "the consumer should start before the producer ends");
    }

    @Test
    void preview_receives_streamed_pieces() {
        var g = new Graph();
        var s = g.addNode(STREAMING, 0, 0);
        var p = g.addNode(registry.get("view.preview"), 0, 0);
        g.connect(new Edge(s.id(), "text", p.id(), "value"));
        var r = new Recorder();
        run(g, r);
        assertEquals("abc", String.join("", r.emitted.stream().map(Object::toString).toList()));
    }

    @Test
    void cancel_stops_a_running_node() {
        var g = new Graph();
        var t = g.addNode(registry.get("text.input"), 0, 0);
        var d = g.addNode(registry.get("util.delay"), 0, 0);
        var after = g.addNode(registry.get("text.transform"), 0, 0);
        g.setParam(d.id(), "seconds", 30.0);
        g.connect(new Edge(t.id(), "text", d.id(), "in"));
        g.connect(new Edge(d.id(), "out", after.id(), "text"));
        var r = new Recorder();
        var running = engine.runAll(g.snapshot(), r);
        long start = System.nanoTime();
        while (running.status(d.id()) != NodeStatus.RUNNING) Thread.onSpinWait();
        running.cancel();
        var result = running.result().orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).join();
        assertTrue(result.cancelled());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 3000);
        assertEquals(NodeStatus.CANCELLED, r.last.get(d.id()));
        assertEquals(NodeStatus.CANCELLED, r.last.get(after.id()));
    }

    @Test
    void running_a_target_only_runs_what_it_needs() {
        var g = new Graph();
        var a = g.addNode(registry.get("text.input"), 0, 0);
        var b = g.addNode(registry.get("text.input"), 0, 0);
        var up = g.addNode(registry.get("text.transform"), 0, 0);
        g.connect(new Edge(a.id(), "text", up.id(), "text"));
        var run = engine.run(g.snapshot(), List.of(up.id()), new Recorder());
        run.result().join();
        assertEquals(java.util.Set.of(a.id(), up.id()), run.nodes());
        assertTrue(!run.nodes().contains(b.id()));
    }
}
