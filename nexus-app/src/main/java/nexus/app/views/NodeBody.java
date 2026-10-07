package nexus.app.views;

import java.util.Map;
import javafx.scene.layout.Region;
import nexus.core.exec.NodeStatus;

/**
 * The live area inside a node, below its ports: shows what the node produced (text, a table, a
 * list...) and, while it runs, what it is producing. One kind per {@code NodeDefinition.view()}.
 */
public abstract class NodeBody extends Region {
    /** Width the body is laid out at (the node's width minus padding). */
    public abstract double preferredHeight();

    /** A run started: clear live state. */
    public void reset() {
    }

    public void status(NodeStatus status, String message) {
    }

    /** Coalesced live data (text pieces are already concatenated). */
    public void emit(String channel, Object payload) {
    }

    /** The node's outputs after it finished (or was served from the cache). */
    public void outputs(Map<String, Object> outputs) {
    }

    /** Whether mouse presses inside should reach the body's controls instead of starting a node drag. */
    public boolean interactive() {
        return false;
    }
}
