package nexus.core.edit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import nexus.core.graph.Edge;
import nexus.core.graph.Graph;
import nexus.core.graph.NodeDefinition;
import nexus.core.graph.NodeInstance;

/** The editing commands. Each one records what it needs to undo itself exactly. */
public final class Commands {
    private Commands() {
    }

    /** Places a new node. */
    public static final class AddNode implements Command {
        private final NodeDefinition def;
        private final double x, y;
        private final Map<String, Object> params;
        private String id;
        private NodeInstance created;

        public AddNode(NodeDefinition def, double x, double y) {
            this(def, x, y, Map.of(), null);
        }

        public AddNode(NodeDefinition def, double x, double y, Map<String, Object> params, String id) {
            this.def = def;
            this.x = x;
            this.y = y;
            this.params = params;
            this.id = id;
        }

        @Override
        public void apply(Graph g) {
            if (created != null) {
                g.restoreNode(created);
                return;
            }
            if (id == null) id = g.newId();
            created = g.addNode(id, def, x, y);
            for (var e : params.entrySet()) g.setParam(id, e.getKey(), e.getValue());
        }

        @Override
        public void revert(Graph g) {
            g.removeNode(id);
        }

        public String id() {
            return id;
        }

        @Override
        public String label() {
            return "add " + def.title();
        }
    }

    /** Deletes nodes together with their wires. */
    public static final class RemoveNodes implements Command {
        private final List<String> ids;
        private final List<NodeInstance> removed = new ArrayList<>();
        private final List<Edge> removedEdges = new ArrayList<>();

        public RemoveNodes(List<String> ids) {
            this.ids = List.copyOf(ids);
        }

        @Override
        public void apply(Graph g) {
            removed.clear();
            removedEdges.clear();
            for (var id : ids) {
                var n = g.find(id);
                if (n.isEmpty()) continue;
                removed.add(n.get());
                for (var e : g.removeNode(id)) if (!removedEdges.contains(e)) removedEdges.add(e);
            }
        }

        @Override
        public void revert(Graph g) {
            for (var n : removed) g.restoreNode(n);
            for (var e : removedEdges) g.connect(e);
        }

        @Override
        public String label() {
            return ids.size() == 1 ? "delete node" : "delete " + ids.size() + " nodes";
        }
    }

    /** Moves nodes; consecutive moves of the same nodes merge into one undo step. */
    public static final class MoveNodes implements Command {
        private final Map<String, double[]> from = new LinkedHashMap<>();
        private final Map<String, double[]> to = new LinkedHashMap<>();

        /** @param moves node id -> {fromX, fromY, toX, toY} */
        public MoveNodes(Map<String, double[]> moves) {
            for (var e : moves.entrySet()) {
                var v = e.getValue();
                from.put(e.getKey(), new double[]{v[0], v[1]});
                to.put(e.getKey(), new double[]{v[2], v[3]});
            }
        }

        @Override
        public void apply(Graph g) {
            for (var e : to.entrySet()) g.moveNode(e.getKey(), e.getValue()[0], e.getValue()[1]);
        }

        @Override
        public void revert(Graph g) {
            for (var e : from.entrySet()) g.moveNode(e.getKey(), e.getValue()[0], e.getValue()[1]);
        }

        @Override
        public boolean mergeWith(Command next) {
            if (!(next instanceof MoveNodes m) || !m.to.keySet().equals(to.keySet())) return false;
            to.putAll(m.to);
            return true;
        }

        @Override
        public String label() {
            return "move";
        }
    }

    /** Adds a wire (replacing whatever fed that input). */
    public static final class Connect implements Command {
        private final Edge edge;
        private Optional<Edge> replaced = Optional.empty();

        public Connect(Edge edge) {
            this.edge = Objects.requireNonNull(edge);
        }

        @Override
        public void apply(Graph g) {
            replaced = g.connect(edge);
        }

        @Override
        public void revert(Graph g) {
            g.removeEdge(edge);
            replaced.ifPresent(g::connect);
        }

        @Override
        public String label() {
            return "connect";
        }
    }

    /** Removes a wire. */
    public static final class Disconnect implements Command {
        private final Edge edge;

        public Disconnect(Edge edge) {
            this.edge = edge;
        }

        @Override
        public void apply(Graph g) {
            g.removeEdge(edge);
        }

        @Override
        public void revert(Graph g) {
            g.connect(edge);
        }

        @Override
        public String label() {
            return "disconnect";
        }
    }

    /** Changes a parameter; consecutive edits of the same parameter (typing) merge. */
    public static final class SetParam implements Command {
        private final String node, key;
        private final Object value;
        private Object old;
        private boolean hasOld;
        private Object last;

        public SetParam(String node, String key, Object value) {
            this.node = node;
            this.key = key;
            this.value = value;
            this.last = value;
        }

        @Override
        public void apply(Graph g) {
            Object prev = g.setParam(node, key, last);
            if (!hasOld) {
                old = prev;
                hasOld = true;
            }
        }

        @Override
        public void revert(Graph g) {
            g.setParam(node, key, old);
        }

        @Override
        public boolean mergeWith(Command next) {
            if (!(next instanceof SetParam s) || !s.node.equals(node) || !s.key.equals(key)) return false;
            last = s.value;
            return true;
        }

        @Override
        public String label() {
            return "edit " + key;
        }
    }

    /** Renames a node. */
    public static final class Rename implements Command {
        private final String node, title;
        private String old;

        public Rename(String node, String title) {
            this.node = node;
            this.title = title;
        }

        @Override
        public void apply(Graph g) {
            old = g.node(node).title();
            g.setTitle(node, title);
        }

        @Override
        public void revert(Graph g) {
            g.setTitle(node, old);
        }

        @Override
        public String label() {
            return "rename";
        }
    }

    /** Several commands as one undo step (e.g. paste = add nodes + add wires). */
    public static final class Batch implements Command {
        private final String label;
        private final List<Command> parts;

        public Batch(String label, List<Command> parts) {
            this.label = label;
            this.parts = List.copyOf(parts);
        }

        @Override
        public void apply(Graph g) {
            for (var c : parts) c.apply(g);
        }

        @Override
        public void revert(Graph g) {
            for (int i = parts.size() - 1; i >= 0; i--) parts.get(i).revert(g);
        }

        @Override
        public String label() {
            return label;
        }
    }
}
