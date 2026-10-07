package nexus.engines;

/** The lifecycle of a supervised engine. */
public enum EngineState {
    STOPPED,
    STARTING,
    /** Healthy and serving. */
    READY,
    /** Ready, but the process was not started by NEXUS (it was already running on the port). */
    ATTACHED,
    STOPPING,
    /** Crashed or failed to start; see the engine's last error and log. */
    FAILED;

    public boolean isUp() {
        return this == READY || this == ATTACHED;
    }
}
