package nexus.core.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import nexus.core.exec.NodeBehavior;

/**
 * A kind of node: its ports, its parameters and the code that runs it.
 *
 * @param id          stable identifier saved in graphs, e.g. "text.template"
 * @param title       shown on the node
 * @param category    palette group
 * @param description one or two sentences for the palette and the inspector
 * @param inputs      input ports
 * @param outputs     output ports
 * @param params      settings
 * @param behavior    creates the object that executes one run of the node
 * @param cacheable   whether an unchanged node may reuse its previous outputs (false for nodes that read
 *                    files or clocks, or are random)
 * @param view        which live view the UI shows inside the node ("text", "table", "chat", "chart", ...)
 * @param contentKey  for nodes that read outside state (files): a cheap key of that state computed from
 *                    the parameters at the start of a run (e.g. paths, sizes and modification times),
 *                    added to the node's cache fingerprint - so the node stays cacheable and re-runs
 *                    exactly when the files change. Null for pure nodes.
 */
public record NodeDefinition(String id, String title, String category, String description, List<PortSpec> inputs,
                             List<PortSpec> outputs, List<ParamSpec> params, Supplier<NodeBehavior> behavior,
                             boolean cacheable, String view, java.util.function.Function<java.util.Map<String, Object>, String> contentKey) {

    public NodeDefinition {
        Objects.requireNonNull(id);
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        params = List.copyOf(params);
        checkUnique(inputs.stream().map(PortSpec::key).toList(), id + " inputs");
        checkUnique(outputs.stream().map(PortSpec::key).toList(), id + " outputs");
        checkUnique(params.stream().map(ParamSpec::key).toList(), id + " params");
    }

    private static void checkUnique(List<String> keys, String what) {
        if (keys.stream().distinct().count() != keys.size()) throw new IllegalArgumentException("duplicate keys in " + what);
    }

    public PortSpec input(String key) {
        return inputs.stream().filter(p -> p.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(id + " has no input '" + key + "'"));
    }

    public PortSpec output(String key) {
        return outputs.stream().filter(p -> p.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(id + " has no output '" + key + "'"));
    }

    public ParamSpec param(String key) {
        return params.stream().filter(p -> p.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(id + " has no parameter '" + key + "'"));
    }

    public boolean hasInput(String key) {
        return inputs.stream().anyMatch(p -> p.key().equals(key));
    }

    public boolean hasOutput(String key) {
        return outputs.stream().anyMatch(p -> p.key().equals(key));
    }

    public static Builder builder(String id, String title, String category) {
        return new Builder(id, title, category);
    }

    /** Fluent construction for node libraries. */
    public static final class Builder {
        private final String id, title, category;
        private String description = "";
        private final List<PortSpec> inputs = new ArrayList<>();
        private final List<PortSpec> outputs = new ArrayList<>();
        private final List<ParamSpec> params = new ArrayList<>();
        private boolean cacheable = true;
        private String view = "text";
        private java.util.function.Function<java.util.Map<String, Object>, String> contentKey;

        private Builder(String id, String title, String category) {
            this.id = id;
            this.title = title;
            this.category = category;
        }

        public Builder description(String d) {
            description = d;
            return this;
        }

        public Builder input(PortSpec p) {
            inputs.add(p);
            return this;
        }

        public Builder output(PortSpec p) {
            outputs.add(p);
            return this;
        }

        public Builder param(ParamSpec p) {
            params.add(p);
            return this;
        }

        public Builder notCacheable() {
            cacheable = false;
            return this;
        }

        public Builder view(String v) {
            view = v;
            return this;
        }

        /** See {@link NodeDefinition#contentKey()}. */
        public Builder contentKey(java.util.function.Function<java.util.Map<String, Object>, String> f) {
            contentKey = f;
            return this;
        }

        public NodeDefinition behavior(Supplier<NodeBehavior> b) {
            return new NodeDefinition(id, title, category, description, inputs, outputs, params, b, cacheable, view, contentKey);
        }
    }
}
