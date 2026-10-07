package nexus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import nexus.core.edit.CommandStack;
import nexus.core.edit.Commands;
import nexus.core.graph.Edge;
import nexus.core.graph.Graph;
import nexus.core.io.WorkflowFile;
import nexus.core.registry.NodeRegistry;
import nexus.core.nodes.BasicNodes;
import org.junit.jupiter.api.Test;

class GraphTest {
    final NodeRegistry registry = new NodeRegistry().add(new BasicNodes());

    @Test
    void rejects_cycles_type_mismatches_and_unknown_ports() {
        var g = new Graph();
        var t = g.addNode(registry.get("text.input"), 0, 0);
        var up = g.addNode(registry.get("text.transform"), 0, 0);
        var up2 = g.addNode(registry.get("text.transform"), 0, 0);
        var num = g.addNode(registry.get("number.input"), 0, 0);
        var stats = g.addNode(registry.get("text.stats"), 0, 0);
        g.connect(new Edge(t.id(), "text", up.id(), "text"));
        g.connect(new Edge(up.id(), "text", up2.id(), "text"));
        assertTrue(g.checkEdge(new Edge(up2.id(), "text", up.id(), "text")).orElseThrow().contains("cycle"));
        assertTrue(g.checkEdge(new Edge(stats.id(), "table", num.id(), "value")).isPresent());     // no such input
        assertTrue(g.checkEdge(new Edge(up.id(), "text", up.id(), "text")).isPresent());           // self loop
        // a number can go into a text input (conversion), a table cannot go into a number input
        assertTrue(g.checkEdge(new Edge(num.id(), "value", up2.id(), "text")).isEmpty());
        var formula = g.addNode(registry.get("math.formula"), 0, 0);
        assertTrue(g.checkEdge(new Edge(stats.id(), "table", formula.id(), "a")).isPresent());
        assertEquals(List.of(t.id(), up.id(), up2.id()),
                     g.topologicalOrder().stream().map(n -> n.id()).filter(id -> List.of(t.id(), up.id(), up2.id()).contains(id)).toList());
    }

    @Test
    void connecting_an_input_replaces_its_previous_wire() {
        var g = new Graph();
        var a = g.addNode(registry.get("text.input"), 0, 0);
        var b = g.addNode(registry.get("text.input"), 0, 0);
        var up = g.addNode(registry.get("text.transform"), 0, 0);
        g.connect(new Edge(a.id(), "text", up.id(), "text"));
        var replaced = g.connect(new Edge(b.id(), "text", up.id(), "text"));
        assertEquals(a.id(), replaced.orElseThrow().fromNode());
        assertEquals(1, g.edges().size());
    }

    @Test
    void undo_redo_restores_nodes_wires_and_parameters() {
        var g = new Graph();
        var stack = new CommandStack(g);
        var add1 = new Commands.AddNode(registry.get("text.input"), 10, 10);
        stack.execute(add1);
        var add2 = new Commands.AddNode(registry.get("text.transform"), 200, 10);
        stack.execute(add2);
        stack.execute(new Commands.Connect(new Edge(add1.id(), "text", add2.id(), "text")));
        stack.execute(new Commands.SetParam(add1.id(), "text", "salut"));
        stack.execute(new Commands.RemoveNodes(List.of(add1.id())));
        assertEquals(1, g.size());
        assertEquals(0, g.edges().size());
        stack.undo();                                       // node and wire come back
        assertEquals(2, g.size());
        assertEquals(1, g.edges().size());
        assertEquals("salut", g.node(add1.id()).param("text"));
        stack.undo();                                       // parameter edit undone
        assertEquals("Bună! Scrie aici textul tău.", g.node(add1.id()).param("text"));
        stack.redo();
        assertEquals("salut", g.node(add1.id()).param("text"));
        while (stack.canUndo()) stack.undo();
        assertEquals(0, g.size());
        while (stack.canRedo()) stack.redo();
        assertEquals(1, g.size());
    }

    @Test
    void consecutive_moves_merge_into_one_undo_step() {
        var g = new Graph();
        var stack = new CommandStack(g);
        var add = new Commands.AddNode(registry.get("text.input"), 0, 0);
        stack.execute(add);
        stack.breakMerge();
        for (int i = 1; i <= 10; i++)
            stack.execute(new Commands.MoveNodes(Map.of(add.id(), new double[]{i - 1, 0, i, 0})));
        assertEquals(10, g.node(add.id()).x());
        stack.undo();
        assertEquals(0, g.node(add.id()).x());
        assertEquals("add Text", stack.undoLabel().orElseThrow());
    }

    @Test
    void workflow_files_round_trip() throws Exception {
        var g = new Graph();
        var t = g.addNode(registry.get("text.input"), 12.5, 40);
        var tr = g.addNode(registry.get("text.transform"), 300, 40);
        g.setParam(t.id(), "text", "Ana are mere\n„ghilimele” și diacritice");
        g.setParam(tr.id(), "op", "lower case");
        g.setTitle(tr.id(), "Lower");
        g.connect(new Edge(t.id(), "text", tr.id(), "text"));
        String json = WorkflowFile.toJson("demo", g.nodes(), g.edges(), new WorkflowFile.View(5, 6, 1.5));
        var content = WorkflowFile.parse(json);
        var g2 = new Graph();
        WorkflowFile.loadInto(content, g2, registry);
        assertEquals(WorkflowFile.toJson("demo", g2.nodes(), g2.edges(), content.view()), json);
        assertEquals("Lower", g2.node(tr.id()).title());
        assertEquals(1.5, content.view().zoom());
    }

    @Test
    void unknown_node_types_are_reported() throws Exception {
        String json = """
            {"format":"nexus-workflow","version":1,"nodes":[{"id":"n1","type":"no.such","x":0,"y":0,"params":{}}],"edges":[]}""";
        var c = WorkflowFile.parse(json);
        assertEquals(List.of("no.such"), WorkflowFile.missingTypes(c, registry));
        assertThrows(java.io.IOException.class, () -> WorkflowFile.loadInto(c, new Graph(), registry));
        assertFalse(WorkflowFile.missingTypes(WorkflowFile.parse(json.replace("no.such", "text.input")), registry).size() > 0);
    }
}
