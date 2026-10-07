package nexus.core.graph;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A node placed on the canvas: which definition it is, where it is, and its parameter values.
 * Mutated only through {@link Graph} (so listeners hear about every change); every parameter
 * change bumps {@link #revision()}, which is part of the cache key of the node's outputs.
 */
public final class NodeInstance {
    private final String id;
    private final NodeDefinition definition;
    private double x, y;
    private String title;
    private final Map<String, Object> params = new LinkedHashMap<>();
    private long revision;

    NodeInstance(String id, NodeDefinition definition, double x, double y) {
        this.id = Objects.requireNonNull(id);
        this.definition = Objects.requireNonNull(definition);
        this.x = x;
        this.y = y;
        this.title = definition.title();
        for (var p : definition.params()) params.put(p.key(), p.defaultValue());
    }

    public String id() {
        return id;
    }

    public NodeDefinition definition() {
        return definition;
    }

    public String type() {
        return definition.id();
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public String title() {
        return title;
    }

    public long revision() {
        return revision;
    }

    public Object param(String key) {
        if (!params.containsKey(key)) throw new IllegalArgumentException(type() + " has no parameter '" + key + "'");
        return params.get(key);
    }

    public Map<String, Object> params() {
        return Map.copyOf(params);
    }

    void moveTo(double nx, double ny) {
        x = nx;
        y = ny;
    }

    void setTitle(String t) {
        title = t == null || t.isBlank() ? definition.title() : t;
    }

    Object setParam(String key, Object value) {
        var spec = definition.param(key);
        Object old = params.put(key, spec.coerce(value));
        revision++;
        return old;
    }

    @Override
    public String toString() {
        return id + ":" + type();
    }
}
