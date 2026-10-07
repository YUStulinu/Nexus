package nexus.core.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import nexus.core.types.DataTypes;

/**
 * The workflow: nodes and the wires between them. It is a directed acyclic graph; every change is
 * validated (port existence, type compatibility, one wire per input, no cycles) and reported to the
 * listeners. Graphs are edited from one thread (the UI's); the execution engine works on
 * {@link #snapshot() snapshots}, so a running workflow is never affected by later edits.
 */
public final class Graph {
    private final Map<String, NodeInstance> nodes = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private final List<GraphListener> listeners = new CopyOnWriteArrayList<>();
    private int nextId = 1;

    public void addListener(GraphListener l) {
        listeners.add(l);
    }

    public void removeListener(GraphListener l) {
        listeners.remove(l);
    }

    // ---- queries ------------------------------------------------------------------------------------

    public Collection<NodeInstance> nodes() {
        return List.copyOf(nodes.values());
    }

    public List<Edge> edges() {
        return List.copyOf(edges);
    }

    public Optional<NodeInstance> find(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    public NodeInstance node(String id) {
        var n = nodes.get(id);
        if (n == null) throw new IllegalArgumentException("no node '" + id + "'");
        return n;
    }

    public int size() {
        return nodes.size();
    }

    /** The wire feeding an input, if any. */
    public Optional<Edge> edgeInto(String nodeId, String port) {
        return edges.stream().filter(e -> e.toNode().equals(nodeId) && e.toPort().equals(port)).findFirst();
    }

    public List<Edge> edgesFrom(String nodeId) {
        return edges.stream().filter(e -> e.fromNode().equals(nodeId)).toList();
    }

    public List<Edge> edgesTouching(String nodeId) {
        return edges.stream().filter(e -> e.fromNode().equals(nodeId) || e.toNode().equals(nodeId)).toList();
    }

    /** Nodes in an order where every node comes after all the nodes it depends on (Kahn's algorithm). */
    public List<NodeInstance> topologicalOrder() {
        var indegree = new HashMap<String, Integer>();
        for (var id : nodes.keySet()) indegree.put(id, 0);
        for (var e : edges) indegree.merge(e.toNode(), 1, Integer::sum);
        var ready = new ArrayDeque<String>();
        for (var id : nodes.keySet()) if (indegree.get(id) == 0) ready.add(id);
        var order = new ArrayList<NodeInstance>();
        while (!ready.isEmpty()) {
            var id = ready.poll();
            order.add(nodes.get(id));
            for (var e : edges)
                if (e.fromNode().equals(id) && indegree.merge(e.toNode(), -1, Integer::sum) == 0) ready.add(e.toNode());
        }
        if (order.size() != nodes.size()) throw new IllegalStateException("the graph has a cycle");
        return order;
    }

    /** {@code ids} and every node they (transitively) depend on. */
    public Set<String> withUpstream(Collection<String> ids) {
        var result = new LinkedHashSet<String>();
        var stack = new ArrayDeque<>(ids);
        while (!stack.isEmpty()) {
            var id = stack.pop();
            if (!result.add(id)) continue;
            for (var e : edges) if (e.toNode().equals(id)) stack.push(e.fromNode());
        }
        return result;
    }

    /** {@code id} and every node that (transitively) depends on it. */
    public Set<String> downstreamOf(String id) {
        var result = new LinkedHashSet<String>();
        var stack = new ArrayDeque<String>();
        stack.push(id);
        while (!stack.isEmpty()) {
            var cur = stack.pop();
            if (!result.add(cur)) continue;
            for (var e : edges) if (e.fromNode().equals(cur)) stack.push(e.toNode());
        }
        return result;
    }

    /** Nodes whose outputs feed nothing: what "run everything" means. */
    public List<String> sinks() {
        var feeding = new HashSet<String>();
        for (var e : edges) feeding.add(e.fromNode());
        return nodes.keySet().stream().filter(id -> !feeding.contains(id)).toList();
    }

    // ---- validation ----------------------------------------------------------------------------------

    /** Why {@code e} cannot be added, or empty if it can (ignoring a wire it would replace). */
    public Optional<String> checkEdge(Edge e) {
        var from = nodes.get(e.fromNode());
        var to = nodes.get(e.toNode());
        if (from == null || to == null) return Optional.of("unknown node");
        if (e.fromNode().equals(e.toNode())) return Optional.of("a node cannot feed itself");
        if (!from.definition().hasOutput(e.fromPort())) return Optional.of("no output '" + e.fromPort() + "'");
        if (!to.definition().hasInput(e.toPort())) return Optional.of("no input '" + e.toPort() + "'");
        var outType = from.definition().output(e.fromPort()).type();
        var inType = to.definition().input(e.toPort()).type();
        if (!DataTypes.canConnect(outType, inType))
            return Optional.of(outType.name() + " cannot go into " + inType.name());
        if (downstreamOf(e.toNode()).contains(e.fromNode())) return Optional.of("this wire would create a cycle");
        return Optional.empty();
    }

    // ---- mutation (used by the edit commands) ----------------------------------------------------------

    /** A fresh node id ("n1", "n2", ...). */
    public String newId() {
        while (nodes.containsKey("n" + nextId)) nextId++;
        return "n" + nextId++;
    }

    public NodeInstance addNode(NodeDefinition def, double x, double y) {
        return addNode(newId(), def, x, y);
    }

    public NodeInstance addNode(String id, NodeDefinition def, double x, double y) {
        if (nodes.containsKey(id)) throw new IllegalArgumentException("duplicate node id '" + id + "'");
        var n = new NodeInstance(id, def, x, y);
        nodes.put(id, n);
        for (var l : listeners) l.nodeAdded(n);
        return n;
    }

    /** Re-inserts a removed node object (undo): same id, parameters and revision. */
    public void restoreNode(NodeInstance n) {
        if (nodes.containsKey(n.id())) throw new IllegalArgumentException("duplicate node id '" + n.id() + "'");
        nodes.put(n.id(), n);
        for (var l : listeners) l.nodeAdded(n);
    }

    /** Removes a node and its wires; returns the wires that were removed. */
    public List<Edge> removeNode(String id) {
        var n = node(id);
        var touching = edgesTouching(id);
        for (var e : touching) removeEdge(e);
        nodes.remove(id);
        for (var l : listeners) l.nodeRemoved(n);
        return touching;
    }

    public void moveNode(String id, double x, double y) {
        var n = node(id);
        n.moveTo(x, y);
        for (var l : listeners) l.nodeMoved(n);
    }

    public Object setParam(String id, String key, Object value) {
        var n = node(id);
        Object old = n.setParam(key, value);
        for (var l : listeners) l.nodeChanged(n, key);
        return old;
    }

    public void setTitle(String id, String title) {
        var n = node(id);
        n.setTitle(title);
        for (var l : listeners) l.nodeChanged(n, null);
    }

    /** Adds a wire, replacing any wire already feeding the same input; returns the replaced wire. */
    public Optional<Edge> connect(Edge e) {
        var problem = checkEdge(e);
        if (problem.isPresent()) throw new IllegalArgumentException(problem.get());
        var replaced = edgeInto(e.toNode(), e.toPort());
        replaced.ifPresent(this::removeEdge);
        edges.add(e);
        for (var l : listeners) l.edgeAdded(e);
        return replaced;
    }

    public boolean removeEdge(Edge e) {
        boolean removed = edges.remove(e);
        if (removed) for (var l : listeners) l.edgeRemoved(e);
        return removed;
    }

    /** Removes everything. */
    public void clear() {
        for (var e : List.copyOf(edges)) removeEdge(e);
        for (var id : List.copyOf(nodes.keySet())) removeNode(id);
        nextId = 1;
    }

    // ---- snapshots for execution -------------------------------------------------------------------------

    /** An immutable copy of the structure and parameters, taken at the start of a run. */
    public Snapshot snapshot() {
        var ns = new LinkedHashMap<String, Snapshot.NodeState>();
        for (var n : nodes.values()) ns.put(n.id(), new Snapshot.NodeState(n.id(), n.definition(), n.params(), n.revision(), n.title()));
        return new Snapshot(ns, List.copyOf(edges));
    }

    /** What the engine runs: nodes (definition + parameters) and wires, frozen. */
    public record Snapshot(Map<String, NodeState> nodes, List<Edge> edges) {
        public record NodeState(String id, NodeDefinition definition, Map<String, Object> params, long revision, String title) {
        }

        public Optional<Edge> edgeInto(String node, String port) {
            return edges.stream().filter(e -> e.toNode().equals(node) && e.toPort().equals(port)).findFirst();
        }
    }
}
