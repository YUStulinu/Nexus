package nexus.core.exec;

/** Where a node is in a run. */
public enum NodeStatus {
    IDLE,
    /** Waiting for its inputs. */
    QUEUED,
    RUNNING,
    DONE,
    /** Unchanged since last time: outputs served from the cache. */
    CACHED,
    ERROR,
    /** Not run because something upstream failed. */
    SKIPPED,
    CANCELLED;

    public boolean isFinished() {
        return this == DONE || this == CACHED || this == ERROR || this == SKIPPED || this == CANCELLED;
    }

    public boolean isSuccess() {
        return this == DONE || this == CACHED;
    }
}
