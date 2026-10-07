package nexus.core.registry;

import java.util.List;
import nexus.core.graph.NodeDefinition;

/**
 * A set of node types contributed by a module. Modules declare their libraries with
 * {@code provides nexus.core.registry.NodeLibrary with ...} and the registry finds them with
 * {@link java.util.ServiceLoader}.
 */
public interface NodeLibrary {
    /** Shown in the palette's tooltip and the about box. */
    String name();

    List<NodeDefinition> definitions();
}
