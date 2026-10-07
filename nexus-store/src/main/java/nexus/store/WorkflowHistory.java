package nexus.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Versions of workflows and the history of their runs, kept in a {@link KeyValueStore}:
 *
 * <pre>
 *   wf/&lt;workflow&gt;/v/&lt;00000042&gt;      a saved version: time, note, the workflow file's JSON
 *   run/&lt;workflow&gt;/&lt;time&gt;            a finished run: duration, node counts, per-node timings
 * </pre>
 *
 * A new version is stored only when the workflow really changed (positions of nodes do not count
 * as a change), and two versions can be compared node by node: nodes added and removed, parameters
 * changed, wires added and removed.
 */
public final class WorkflowHistory implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Version(String workflow, int number, Instant time, String note, int nodes, int edges) {
    }

    public record RunRecord(String workflow, Instant time, double millis, int done, int cached, int errors, int skipped, Map<String, Double> nodeMillis) {
        public boolean success() {
            return errors == 0;
        }
    }

    private final KeyValueStore store;

    public WorkflowHistory(KeyValueStore store) {
        this.store = store;
    }

    public KeyValueStore store() {
        return store;
    }

    static String key(String workflow) {
        return workflow.strip().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}._-]+", "-");
    }

    // ---- versions --------------------------------------------------------------------------------------------

    /**
     * Saves {@code workflowJson} as a new version unless it equals the latest one (ignoring node
     * positions and the view); returns the version, new or latest.
     */
    public Version save(String workflow, String workflowJson, String note) throws IOException {
        var latest = latest(workflow);
        if (latest != null && sameContent(load(workflow, latest.number()), workflowJson)) return latest;
        int number = latest == null ? 1 : latest.number() + 1;
        var tree = JSON.readTree(workflowJson);
        ObjectNode rec = JSON.createObjectNode();
        rec.put("time", Instant.now().toString());
        rec.put("note", note == null ? "" : note);
        rec.put("nodes", tree.path("nodes").size());
        rec.put("edges", tree.path("edges").size());
        rec.put("json", workflowJson);
        store.put(versionKey(workflow, number), JSON.writeValueAsString(rec));
        return new Version(workflow, number, Instant.parse(rec.get("time").asText()), rec.get("note").asText(), tree.path("nodes").size(),
                           tree.path("edges").size());
    }

    static String versionKey(String workflow, int number) {
        return "wf/" + key(workflow) + "/v/" + String.format("%08d", number);
    }

    public List<Version> versions(String workflow) throws IOException {
        var out = new ArrayList<Version>();
        for (var e : store.scan("wf/" + key(workflow) + "/v/", 100_000)) {
            var n = JSON.readTree(e.getValue());
            int number = Integer.parseInt(e.getKey().substring(e.getKey().lastIndexOf('/') + 1));
            out.add(new Version(workflow, number, Instant.parse(n.get("time").asText()), n.path("note").asText(), n.path("nodes").asInt(),
                                n.path("edges").asInt()));
        }
        return out;
    }

    public Version latest(String workflow) throws IOException {
        var all = versions(workflow);
        return all.isEmpty() ? null : all.getLast();
    }

    /** The workflow file's JSON of a version. */
    public String load(String workflow, int number) throws IOException {
        String v = store.get(versionKey(workflow, number));
        if (v == null) throw new IOException("no version " + number + " of " + workflow);
        return JSON.readTree(v).get("json").asText();
    }

    /** Whether two workflow files describe the same graph (node positions and the view do not matter). */
    static boolean sameContent(String a, String b) throws IOException {
        return Objects.equals(normalise(JSON.readTree(a)), normalise(JSON.readTree(b)));
    }

    private static JsonNode normalise(JsonNode wf) {
        var copy = (ObjectNode) wf.deepCopy();
        copy.remove("view");
        copy.remove("name");
        for (var n : copy.path("nodes")) {
            ((ObjectNode) n).remove("x");
            ((ObjectNode) n).remove("y");
        }
        return copy;
    }

    // ---- differences -------------------------------------------------------------------------------------------

    /** What changed from {@code before} to {@code after}, one line per change. */
    public static List<String> diff(String before, String after) throws IOException {
        var a = JSON.readTree(before);
        var b = JSON.readTree(after);
        var out = new ArrayList<String>();
        Map<String, JsonNode> na = byId(a.path("nodes")), nb = byId(b.path("nodes"));
        for (var id : nb.keySet())
            if (!na.containsKey(id)) out.add("+ node " + label(nb.get(id)));
        for (var id : na.keySet())
            if (!nb.containsKey(id)) out.add("- node " + label(na.get(id)));
        for (var id : nb.keySet()) {
            if (!na.containsKey(id)) continue;
            var pa = na.get(id).path("params");
            var pb = nb.get(id).path("params");
            var keys = new java.util.TreeSet<String>();
            pa.fieldNames().forEachRemaining(keys::add);
            pb.fieldNames().forEachRemaining(keys::add);
            for (var k : keys) {
                if (Objects.equals(pa.get(k), pb.get(k))) continue;
                out.add("~ " + label(nb.get(id)) + ": " + k + " " + shorten(pa.get(k)) + " -> " + shorten(pb.get(k)));
            }
            String ta = na.get(id).path("title").asText(""), tb = nb.get(id).path("title").asText("");
            if (!ta.equals(tb)) out.add("~ " + id + " renamed \"" + ta + "\" -> \"" + tb + "\"");
        }
        var ea = edges(a);
        var eb = edges(b);
        for (var e : eb) if (!ea.contains(e)) out.add("+ wire " + e);
        for (var e : ea) if (!eb.contains(e)) out.add("- wire " + e);
        return out;
    }

    private static Map<String, JsonNode> byId(JsonNode nodes) {
        var m = new LinkedHashMap<String, JsonNode>();
        for (var n : nodes) m.put(n.get("id").asText(), n);
        return m;
    }

    private static String label(JsonNode n) {
        String title = n.path("title").asText("");
        return (title.isBlank() ? n.path("type").asText() : title) + " (" + n.get("id").asText() + ")";
    }

    private static String shorten(JsonNode v) {
        if (v == null) return "(unset)";
        String s = v.isTextual() ? "\"" + v.asText() + "\"" : v.toString();
        return s.length() > 40 ? s.substring(0, 37) + "...\"" : s;
    }

    private static List<String> edges(JsonNode wf) {
        var out = new ArrayList<String>();
        for (var e : wf.path("edges")) out.add(e.path("from").asText() + " -> " + e.path("to").asText());
        return out;
    }

    // ---- runs ------------------------------------------------------------------------------------------------

    public void recordRun(RunRecord r) throws IOException {
        ObjectNode rec = JSON.createObjectNode();
        rec.put("time", r.time().toString());
        rec.put("millis", r.millis());
        rec.put("done", r.done());
        rec.put("cached", r.cached());
        rec.put("errors", r.errors());
        rec.put("skipped", r.skipped());
        var nodes = rec.putObject("nodes");
        r.nodeMillis().forEach(nodes::put);
        store.put("run/" + key(r.workflow()) + "/" + String.format("%015d", r.time().toEpochMilli()), JSON.writeValueAsString(rec));
    }

    public List<RunRecord> runs(String workflow) throws IOException {
        var out = new ArrayList<RunRecord>();
        for (var e : store.scan("run/" + key(workflow) + "/", 100_000)) {
            var n = JSON.readTree(e.getValue());
            var nodes = new HashMap<String, Double>();
            n.path("nodes").properties().forEach(f -> nodes.put(f.getKey(), f.getValue().asDouble()));
            out.add(new RunRecord(workflow, Instant.parse(n.get("time").asText()), n.path("millis").asDouble(), n.path("done").asInt(),
                                  n.path("cached").asInt(), n.path("errors").asInt(), n.path("skipped").asInt(), nodes));
        }
        return out;
    }

    @Override
    public void close() throws IOException {
        store.close();
    }
}
