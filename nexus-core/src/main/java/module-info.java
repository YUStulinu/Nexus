/**
 * The NEXUS core: the graph of nodes and typed ports, the commands that edit it (with undo),
 * its JSON format, and the engine that executes it - concurrently, incrementally (unchanged
 * nodes are served from a cache) and with live progress for the UI. It knows nothing about
 * JavaFX; node libraries plug in through {@link nexus.core.registry.NodeLibrary}.
 */
module nexus.core {
    requires transitive com.fasterxml.jackson.databind;
    requires java.net.http;

    exports nexus.core.types;
    exports nexus.core.graph;
    exports nexus.core.edit;
    exports nexus.core.exec;
    exports nexus.core.registry;
    exports nexus.core.io;
    exports nexus.core.nodes;
    exports nexus.core.util;

    uses nexus.core.registry.NodeLibrary;
    provides nexus.core.registry.NodeLibrary with nexus.core.nodes.BasicNodes;
}
