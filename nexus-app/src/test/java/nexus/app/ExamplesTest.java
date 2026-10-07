package nexus.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import nexus.core.exec.ExecutionEngine;
import nexus.core.exec.ExecutionListener;
import nexus.core.exec.Services;
import nexus.core.graph.Graph;
import nexus.core.io.WorkflowFile;
import nexus.core.nodes.BasicNodes;
import nexus.core.registry.NodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * Every example shipped with the application loads (all node types exist, every wire connects
 * compatible ports), and the examples that need no GPU, model or sibling project run to the end.
 */
class ExamplesTest {
    static NodeRegistry registry() {
        return new NodeRegistry().add(new BasicNodes()).add(new nexus.ml.nodes.RagNodes()).add(new nexus.engines.llm.LlmNodes())
                                 .add(new nexus.engines.train.TrainingNodes()).add(new nexus.games.nodes.GameNodes());
    }

    static WorkflowFile.Content example(String name) throws Exception {
        try (var in = NexusApp.class.getResourceAsStream("examples/" + name + ".nexus")) {
            return WorkflowFile.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void everyExampleLoads() throws Exception {
        var reg = registry();
        for (var name : NexusApp.EXAMPLES) {
            var c = example(name);
            assertEquals(List.of(), WorkflowFile.missingTypes(c, reg), name);
            var g = new Graph();
            WorkflowFile.loadInto(c, g, reg);
            assertEquals(c.nodes().size(), g.nodes().size(), name);
            assertEquals(c.edges().size(), g.edges().size(), name + ": every wire is valid");
        }
    }

    @Test
    void cpuOnlyExamplesRun() throws Exception {
        var reg = registry();
        for (var name : List.of("Text analysis", "Parallel branches", "Formula")) {
            var g = new Graph();
            WorkflowFile.loadInto(example(name), g, reg);
            try (var engine = new ExecutionEngine(new Services())) {
                var result = engine.runAll(g.snapshot(), new ExecutionListener() {
                }).result().orTimeout(60, java.util.concurrent.TimeUnit.SECONDS).join();
                assertTrue(result.success(), name + ": " + result.timings());
            }
        }
    }
}
