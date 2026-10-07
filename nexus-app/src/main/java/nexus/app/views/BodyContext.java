package nexus.app.views;

import java.util.Map;
import nexus.core.exec.Services;

/** The node a body belongs to, and the application services it may use. */
public interface BodyContext {
    String nodeId();

    Services services();

    /** The node's current parameter value. */
    Object param(String key);

    /** The node's outputs from its last run (empty if it has not run). */
    Map<String, Object> lastOutputs();

    /** Changes a parameter through the undo stack. */
    void setParam(String key, Object value);
}
