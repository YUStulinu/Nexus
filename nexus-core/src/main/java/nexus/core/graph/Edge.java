package nexus.core.graph;

/** A wire from an output port of one node to an input port of another. */
public record Edge(String fromNode, String fromPort, String toNode, String toPort) {
    @Override
    public String toString() {
        return fromNode + "." + fromPort + " -> " + toNode + "." + toPort;
    }
}
