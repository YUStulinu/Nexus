package nexus.core.types;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Text that arrives piece by piece - the tokens of a language model as they are generated.
 *
 * A producer appends pieces and finally completes (or fails) the stream; any number of consumers
 * can subscribe at any time and receive everything written so far, then every new piece, in
 * order. A node can publish a stream on its output before it has finished, so the next node starts
 * working on the first tokens instead of waiting for the last one. Consumers that just want the
 * whole text call {@link #await()}.
 */
public final class TextStream {
    /** An open, empty stream. */
    public TextStream() {
    }

    private final StringBuilder text = new StringBuilder();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final CompletableFuture<String> done = new CompletableFuture<>();

    /** A stream that is already complete. */
    public static TextStream of(String complete) {
        var s = new TextStream();
        s.append(complete);
        s.complete();
        return s;
    }

    /** Appends a piece and delivers it to the subscribers. */
    public void append(String piece) {
        if (piece == null || piece.isEmpty()) return;
        List<Consumer<String>> targets;
        synchronized (this) {
            if (done.isDone()) throw new IllegalStateException("stream already completed");
            text.append(piece);
            targets = new ArrayList<>(listeners);
        }
        for (var l : targets) l.accept(piece);
    }

    public void complete() {
        String full;
        synchronized (this) {
            full = text.toString();
        }
        done.complete(full);
    }

    public void fail(Throwable error) {
        done.completeExceptionally(error);
    }

    /**
     * Subscribes: {@code onPiece} first receives the text so far (as one piece, if non-empty), then
     * every later piece. Returns the future of the complete text.
     */
    public CompletableFuture<String> subscribe(Consumer<String> onPiece) {
        String sofar;
        synchronized (this) {
            sofar = text.toString();
            listeners.add(onPiece);
        }
        if (!sofar.isEmpty()) onPiece.accept(sofar);
        return done;
    }

    /** Blocks until the stream is complete and returns the whole text. */
    public String await() {
        return done.join();
    }

    public boolean isComplete() {
        return done.isDone();
    }

    public synchronized String textSoFar() {
        return text.toString();
    }

    @Override
    public String toString() {
        return textSoFar();
    }
}
