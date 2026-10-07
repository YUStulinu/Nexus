package nexus.core.graph;

import nexus.core.types.DataType;

/**
 * An input or output of a node type.
 *
 * @param key      identifier, unique among the node's inputs (or outputs)
 * @param label    shown next to the port
 * @param type     what flows through it
 * @param optional for inputs: the node runs even when nothing is connected (the value is null)
 */
public record PortSpec(String key, String label, DataType type, boolean optional) {
    public static PortSpec in(String key, String label, DataType type) {
        return new PortSpec(key, label, type, false);
    }

    public static PortSpec optionalIn(String key, String label, DataType type) {
        return new PortSpec(key, label, type, true);
    }

    public static PortSpec out(String key, String label, DataType type) {
        return new PortSpec(key, label, type, false);
    }
}
