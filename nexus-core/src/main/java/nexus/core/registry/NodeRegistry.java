package nexus.core.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.TreeMap;
import nexus.core.graph.NodeDefinition;

/** All the node types the application knows, by id and by palette category. */
public final class NodeRegistry {
    private final Map<String, NodeDefinition> byId = new LinkedHashMap<>();
    private final List<String> libraries = new ArrayList<>();

    /** A registry with every library found on the module path. */
    public static NodeRegistry discover() {
        var r = new NodeRegistry();
        for (var lib : ServiceLoader.load(NodeLibrary.class)) r.add(lib);
        return r;
    }

    public NodeRegistry add(NodeLibrary lib) {
        libraries.add(lib.name());
        for (var d : lib.definitions()) add(d);
        return this;
    }

    public NodeRegistry add(NodeDefinition d) {
        if (byId.putIfAbsent(d.id(), d) != null) throw new IllegalArgumentException("duplicate node type '" + d.id() + "'");
        return this;
    }

    public Optional<NodeDefinition> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public NodeDefinition get(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("unknown node type '" + id + "'"));
    }

    public Collection<NodeDefinition> all() {
        return List.copyOf(byId.values());
    }

    public List<String> libraries() {
        return List.copyOf(libraries);
    }

    /** Category -> definitions, categories in alphabetical order, definitions in registration order. */
    public Map<String, List<NodeDefinition>> byCategory() {
        var map = new TreeMap<String, List<NodeDefinition>>();
        for (var d : byId.values()) map.computeIfAbsent(d.category(), k -> new ArrayList<>()).add(d);
        return map;
    }

    /** Fuzzy search over titles, ids, categories and descriptions (all words must match). */
    public List<NodeDefinition> search(String query) {
        var words = query.toLowerCase().trim().split("\\s+");
        return byId.values().stream().filter(d -> {
            String hay = (d.title() + " " + d.id() + " " + d.category() + " " + d.description()).toLowerCase();
            for (var w : words) if (!w.isEmpty() && !hay.contains(w)) return false;
            return true;
        }).sorted((a, b) -> {
            String q = query.toLowerCase().trim();
            int sa = a.title().toLowerCase().startsWith(q) ? 0 : 1, sb = b.title().toLowerCase().startsWith(q) ? 0 : 1;
            return sa != sb ? Integer.compare(sa, sb) : a.title().compareToIgnoreCase(b.title());
        }).toList();
    }
}
