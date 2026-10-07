package nexus.core.exec;

import java.util.concurrent.CancellationException;

/** What a running node can see and do: read its inputs and parameters, publish outputs, report progress. */
public interface NodeContext {
    String nodeId();

    String nodeTitle();

    /** The value arriving on an input, converted to the port's declared type; null for an unconnected optional input. */
    Object input(String key);

    default <T> T input(String key, Class<T> type) {
        return type.cast(input(key));
    }

    default String inputText(String key) {
        Object v = input(key);
        return v == null ? null : v.toString();
    }

    boolean isConnected(String key);

    Object param(String key);

    default String paramText(String key) {
        Object v = param(key);
        return v == null ? "" : v.toString();
    }

    default double paramNumber(String key) {
        return ((Number) param(key)).doubleValue();
    }

    default int paramInt(String key) {
        return (int) Math.round(paramNumber(key));
    }

    default boolean paramBool(String key) {
        return (Boolean) param(key);
    }

    /**
     * Publishes an output. Downstream nodes can start as soon as all their inputs are published, so a
     * node may publish early (e.g. a {@link nexus.core.types.TextStream} it keeps writing to).
     */
    void output(String key, Object value);

    /** Progress in [0, 1] (or negative for "busy, unknown"), with a short message. */
    void progress(double fraction, String message);

    void log(String message);

    /** Live data for the node's view in the UI: tokens as they are generated, chart points, a board... */
    void emit(String channel, Object payload);

    boolean isCancelled();

    default void checkCancelled() {
        if (isCancelled() || Thread.currentThread().isInterrupted()) throw new CancellationException("cancelled");
    }

    /** Application services (engines, resource broker, storage) that nodes may use. */
    Services services();
}
