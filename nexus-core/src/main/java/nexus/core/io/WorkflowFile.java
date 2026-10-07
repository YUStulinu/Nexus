package nexus.core.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import nexus.core.graph.Edge;
import nexus.core.graph.Graph;
import nexus.core.graph.NodeInstance;
import nexus.core.registry.NodeRegistry;

/**
 * The workflow file format (.nexus, JSON):
 * <pre>
 * { "format": "nexus-workflow", "version": 1, "name": "...",
 *   "nodes": [ {"id": "n1", "type": "text.input", "x": 40, "y": 80, "title": "...", "params": {...}} ],
 *   "edges": [ {"from": "n1.text", "to": "n2.a"} ],
 *   "view":  {"x": 0, "y": 0, "zoom": 1} }
 * </pre>
 * The same structure (without the view) is used on the clipboard for copy and paste.
 */
public final class WorkflowFile {
    public static final String FORMAT = "nexus-workflow";
    public static final int VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private WorkflowFile() {
    }

    /** Where the canvas was looking. */
    public record View(double x, double y, double zoom) {
        public static final View DEFAULT = new View(0, 0, 1);
    }

    /** A parsed workflow, not yet placed in a graph. */
    public record Content(String name, List<NodeEntry> nodes, List<Edge> edges, View view) {
    }

    public record NodeEntry(String id, String type, double x, double y, String title, Map<String, Object> params) {
    }

    // ---- writing ------------------------------------------------------------------------------------------

    public static String toJson(String name, Collection<NodeInstance> nodes, Collection<Edge> edges, View view) {
        ObjectNode root = JSON.createObjectNode();
        root.put("format", FORMAT);
        root.put("version", VERSION);
        root.put("name", name);
        ArrayNode ns = root.putArray("nodes");
        for (var n : nodes) {
            ObjectNode o = ns.addObject();
            o.put("id", n.id());
            o.put("type", n.type());
            o.put("x", Math.round(n.x() * 10) / 10.0);
            o.put("y", Math.round(n.y() * 10) / 10.0);
            if (!n.title().equals(n.definition().title())) o.put("title", n.title());
            o.set("params", JSON.valueToTree(n.params()));
        }
        ArrayNode es = root.putArray("edges");
        for (var e : edges) {
            ObjectNode o = es.addObject();
            o.put("from", e.fromNode() + "." + e.fromPort());
            o.put("to", e.toNode() + "." + e.toPort());
        }
        if (view != null) {
            ObjectNode v = root.putObject("view");
            v.put("x", view.x());
            v.put("y", view.y());
            v.put("zoom", view.zoom());
        }
        try {
            return JSON.writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void save(Path file, String name, Graph graph, View view) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, toJson(name, graph.nodes(), graph.edges(), view));
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    // ---- reading ------------------------------------------------------------------------------------------

    public static Content parse(String json) throws IOException {
        JsonNode root = JSON.readTree(json);
        if (!FORMAT.equals(root.path("format").asText())) throw new IOException("not a NEXUS workflow");
        int version = root.path("version").asInt();
        if (version > VERSION) throw new IOException("workflow format version " + version + " is newer than this NEXUS (" + VERSION + ")");
        var nodes = new ArrayList<NodeEntry>();
        for (JsonNode n : root.path("nodes")) {
            Map<String, Object> params = new LinkedHashMap<>();
            n.path("params").properties().forEach(e -> params.put(e.getKey(), toJava(e.getValue())));
            nodes.add(new NodeEntry(n.path("id").asText(), n.path("type").asText(), n.path("x").asDouble(), n.path("y").asDouble(),
                                    n.has("title") ? n.get("title").asText() : null, params));
        }
        var edges = new ArrayList<Edge>();
        for (JsonNode e : root.path("edges")) {
            String[] from = splitPort(e.path("from").asText()), to = splitPort(e.path("to").asText());
            edges.add(new Edge(from[0], from[1], to[0], to[1]));
        }
        JsonNode v = root.path("view");
        var view = v.isMissingNode() ? View.DEFAULT : new View(v.path("x").asDouble(), v.path("y").asDouble(), v.path("zoom").asDouble(1));
        return new Content(root.path("name").asText("untitled"), nodes, edges, view);
    }

    public static Content load(Path file) throws IOException {
        return parse(Files.readString(file));
    }

    private static String[] splitPort(String s) throws IOException {
        int dot = s.indexOf('.');
        if (dot <= 0 || dot == s.length() - 1) throw new IOException("bad port reference '" + s + "'");
        return new String[]{s.substring(0, dot), s.substring(dot + 1)};
    }

    private static Object toJava(JsonNode n) {
        if (n.isNumber()) return n.asDouble();
        if (n.isBoolean()) return n.asBoolean();
        if (n.isNull()) return null;
        return n.asText();
    }

    /** Node types the content uses that the registry does not know. */
    public static List<String> missingTypes(Content c, NodeRegistry registry) {
        var missing = new TreeSet<String>();
        for (var n : c.nodes()) if (registry.find(n.type()).isEmpty()) missing.add(n.type());
        return List.copyOf(missing);
    }

    /**
     * Replaces the graph's contents with the workflow. Fails (leaving the graph empty) if a node type
     * is unknown or a wire is invalid.
     */
    public static void loadInto(Content c, Graph graph, NodeRegistry registry) throws IOException {
        var missing = missingTypes(c, registry);
        if (!missing.isEmpty()) throw new IOException("unknown node types: " + String.join(", ", missing));
        graph.clear();
        for (var n : c.nodes()) {
            graph.addNode(n.id(), registry.get(n.type()), n.x(), n.y());
            if (n.title() != null) graph.setTitle(n.id(), n.title());
            var def = registry.get(n.type());
            for (var e : n.params().entrySet())
                if (def.params().stream().anyMatch(p -> p.key().equals(e.getKey()))) graph.setParam(n.id(), e.getKey(), e.getValue());
        }
        for (var e : c.edges()) {
            var problem = graph.checkEdge(e);
            if (problem.isPresent()) throw new IOException("invalid wire " + e + ": " + problem.get());
            graph.connect(e);
        }
    }
}
