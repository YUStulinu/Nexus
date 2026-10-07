package nexus.engines.system;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Shares the GPU's memory between the processes NEXUS runs on it: LLM servers (Ember, Kindling),
 * training jobs (Anvil, Gambit). Without it, starting a second model while a training run holds the
 * card ends in a CUDA out-of-memory error in whichever process allocates last.
 *
 * <p>Every process asks for a <b>lease</b> of the memory it will use before it starts, and gives it
 * back when it exits. A request is granted when it fits in what is <b>available</b>:
 *
 * <pre>
 *   available = total - safety margin - Σ leases - memory used by other programs
 *   other programs = measured used - Σ leases already in use (clamped at 0)
 * </pre>
 *
 * so both the budgets NEXUS handed out (even to processes still loading) and what the desktop, the
 * browser or a game really use are respected.
 *
 * <p>When a request does not fit, the broker makes room, cheapest first:
 * <ol>
 *   <li><b>evict</b> idle LLM servers, least recently used first (restarting one costs seconds) -
 *       for an interactive request after 10 s of idleness, for a background one after 60 s;</li>
 *   <li><b>pause</b> background jobs (training checkpoints and stops; it resumes when memory frees) -
 *       only for interactive requests: a chat answer beats a training step, never the reverse.</li>
 * </ol>
 * Requests wait in a queue ordered by priority, then arrival; only the head may claim memory, so a
 * stream of small background requests cannot starve a large interactive one.
 */
public final class VramBroker {
    public enum Priority { INTERACTIVE, BACKGROUND }

    /** How a holder can give its memory back. */
    public enum Preemption { NEVER, EVICT, PAUSE }

    /** Whoever holds a lease. */
    public interface Holder {
        String name();

        Preemption preemption();

        /** Whether it could release right now (an engine in the middle of an answer cannot). */
        default boolean preemptibleNow() {
            return true;
        }

        /** Last time it did useful work, for least-recently-used eviction. */
        default Instant lastUsed() {
            return Instant.EPOCH;
        }

        /**
         * Asked to give its memory back for {@code requester}: stop or pause, then close the lease.
         * Called on its own thread; may decline by simply not releasing.
         */
        void preempt(String requester);
    }

    public static final Duration IDLE_FOR_INTERACTIVE = Duration.ofSeconds(10), IDLE_FOR_BACKGROUND = Duration.ofSeconds(60);
    private final Duration idleForInteractive, idleForBackground;
    /** A lease not marked active counts as in use after this long anyway (its process has surely allocated). */
    static final Duration ACTIVE_AFTER = Duration.ofSeconds(45);
    /** A holder that has not released this long after being asked is considered to have declined. */
    static final Duration PREEMPT_GRACE = Duration.ofSeconds(30);

    /** Memory granted to one holder. Closing it gives the memory back. */
    public final class Lease implements AutoCloseable {
        final Holder holder;
        final int mib;
        final Priority priority;
        final Instant granted = Instant.now();
        volatile boolean active, released;
        volatile String preemptedFor;
        volatile Instant preemptedAt;

        Lease(Holder holder, int mib, Priority priority) {
            this.holder = holder;
            this.mib = mib;
            this.priority = priority;
        }

        public int mib() {
            return mib;
        }

        public Holder holder() {
            return holder;
        }

        /** The process has loaded and now really uses its memory (it shows in the measured usage). */
        public void markActive() {
            active = true;
            changed();
        }

        boolean countsAsActive() {
            return active || Duration.between(granted, Instant.now()).compareTo(ACTIVE_AFTER) > 0;
        }

        @Override
        public void close() {
            release(this);
        }
    }

    public record LeaseInfo(String holder, int mib, Priority priority, Instant granted, boolean active, String preemptedFor) {
    }

    public record Waiting(String holder, int mib, Priority priority, Instant since) {
    }

    public record Event(Instant time, String text) {
    }

    /** Everything the dashboard shows. Memory figures are in MiB; {@code total == 0} means no GPU. */
    public record State(long total, long measuredUsed, long other, long available, int margin, List<LeaseInfo> leases, List<Waiting> waiting) {
    }

    private record Request(Holder holder, int mib, Priority priority, Instant since, long seq) {
    }

    private final Supplier<GpuSample> gpu;
    private final int margin;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final List<Lease> leases = new ArrayList<>();
    private final List<Request> queue = new ArrayList<>();
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private long seq;

    /**
     * @param gpu       a fresh reading of the GPU (NVML takes microseconds)
     * @param marginMiB kept free for the display, the driver and allocation slack
     */
    public VramBroker(Supplier<GpuSample> gpu, int marginMiB) {
        this(gpu, marginMiB, IDLE_FOR_INTERACTIVE, IDLE_FOR_BACKGROUND);
    }

    /** With custom idleness thresholds for eviction (tests use short ones). */
    public VramBroker(Supplier<GpuSample> gpu, int marginMiB, Duration idleForInteractive, Duration idleForBackground) {
        this.gpu = gpu;
        this.margin = marginMiB;
        this.idleForInteractive = idleForInteractive;
        this.idleForBackground = idleForBackground;
    }

    public void addListener(Runnable l) {
        listeners.add(l);
    }

    private void changed() {
        lock.lock();
        try {
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        for (var l : listeners) l.run();
    }

    private void event(String text) {
        synchronized (events) {
            events.addLast(new Event(Instant.now(), text));
            while (events.size() > 200) events.removeFirst();
        }
    }

    public List<Event> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    // ---- accounting --------------------------------------------------------------------------------------------

    private long leased() {
        long s = 0;
        for (var l : leases) s += l.mib;
        return s;
    }

    private long other(GpuSample g) {
        long active = 0;
        for (var l : leases) if (l.countsAsActive()) active += l.mib;
        return Math.max(0, g.usedMiB() - active);
    }

    private long available(GpuSample g) {
        return g.totalMiB() - margin - other(g) - leased();
    }

    public State state() {
        var g = gpu.get();
        lock.lock();
        try {
            var ls = leases.stream().map(l -> new LeaseInfo(l.holder.name(), l.mib, l.priority, l.granted, l.countsAsActive(), l.preemptedFor)).toList();
            var ws = queue.stream().map(r -> new Waiting(r.holder.name(), r.mib, r.priority, r.since)).toList();
            if (!g.present()) return new State(0, 0, 0, 0, margin, ls, ws);
            return new State(g.totalMiB(), g.usedMiB(), other(g), available(g), margin, ls, ws);
        } finally {
            lock.unlock();
        }
    }

    // ---- acquiring and releasing ---------------------------------------------------------------------------

    /**
     * Waits until {@code mib} MiB can be granted to {@code holder}, making room if the rules allow.
     *
     * @throws TimeoutException     if the memory could not be found in time (the message says who holds what)
     * @throws InterruptedException if the waiting thread is interrupted (the request is withdrawn)
     */
    public Lease acquire(Holder holder, int mib, Priority priority, Duration timeout) throws InterruptedException, TimeoutException {
        return acquire(holder, mib, priority, timeout, () -> false);
    }

    /**
     * Like {@link #acquire(Holder, int, Priority, Duration)}, withdrawing the request (with a
     * CancellationException) as soon as {@code cancelled} turns true - for background jobs that
     * wait as long as it takes.
     */
    public Lease acquire(Holder holder, int mib, Priority priority, Duration timeout, java.util.function.BooleanSupplier cancelled)
            throws InterruptedException, TimeoutException {
        var first = gpu.get();
        if (!first.present()) {           // nothing to coordinate (CPU-only machine): the process will find out itself
            var l = new Lease(holder, mib, priority);
            lock.lock();
            try {
                leases.add(l);
            } finally {
                lock.unlock();
            }
            changed();
            return l;
        }
        if (mib > first.totalMiB() - margin)
            throw new IllegalArgumentException(holder.name() + " needs " + mib + " MiB; the GPU has " + first.totalMiB() + " MiB in all");
        long deadline = System.nanoTime() + timeout.toNanos();
        Request req;
        lock.lock();
        try {
            req = new Request(holder, mib, priority, Instant.now(), seq++);
            queue.add(req);
            queue.sort(Comparator.comparing(Request::priority).thenComparingLong(Request::seq));
        } finally {
            lock.unlock();
        }
        changed();
        boolean granted = false;
        try {
            while (true) {
                var g = gpu.get();
                lock.lock();
                try {
                    if (queue.getFirst() == req) {
                        long avail = available(g);
                        if (avail >= mib) {
                            queue.remove(req);
                            var lease = new Lease(holder, mib, priority);
                            leases.add(lease);
                            granted = true;
                            event(holder.name() + " got " + mib + " MiB (" + (avail - mib) + " MiB left)");
                            return lease;
                        }
                        makeRoom(req, mib - avail);
                    }
                    if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException(holder.name() + " stopped waiting");
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        String why = describe(g, req);
                        event(holder.name() + " gave up waiting for " + mib + " MiB");
                        throw new TimeoutException(why);
                    }
                    changed.await(Math.min(left, TimeUnit.MILLISECONDS.toNanos(500)), TimeUnit.NANOSECONDS);
                } finally {
                    lock.unlock();
                }
            }
        } finally {
            if (!granted) {
                lock.lock();
                try {
                    queue.remove(req);
                } finally {
                    lock.unlock();
                }
            }
            changed();
        }
    }

    private String describe(GpuSample g, Request req) {
        var sb = new StringBuilder(req.holder.name() + " needs " + req.mib + " MiB of GPU memory but only " + Math.max(0, available(g))
                                   + " MiB are available (" + g.totalMiB() + " total, " + margin + " margin, " + other(g) + " used by other programs");
        for (var l : leases) sb.append(", ").append(l.mib).append(" held by ").append(l.holder.name());
        return sb.append(")").toString();
    }

    /** Asks holders to free {@code needed} MiB for {@code req}, cheapest first. Called with the lock held. */
    private void makeRoom(Request req, long needed) {
        var now = Instant.now();
        long freeing = 0;
        for (var l : leases) {
            if (l.preemptedFor == null) continue;
            if (Duration.between(l.preemptedAt, now).compareTo(PREEMPT_GRACE) > 0) {
                l.preemptedFor = null;      // it declined (or hangs): it may be asked again later
                continue;
            }
            freeing += l.mib;
        }
        if (freeing >= needed) return;
        var idleFor = req.priority == Priority.INTERACTIVE ? idleForInteractive : idleForBackground;
        var evict = new ArrayList<Lease>();
        var pause = new ArrayList<Lease>();
        for (var l : leases) {
            if (l.preemptedFor != null || l.holder == req.holder || !l.holder.preemptibleNow()) continue;
            switch (l.holder.preemption()) {
                case EVICT -> {
                    if (Duration.between(l.holder.lastUsed(), now).compareTo(idleFor) >= 0) evict.add(l);
                }
                case PAUSE -> {
                    if (req.priority == Priority.INTERACTIVE && l.priority == Priority.BACKGROUND) pause.add(l);
                }
                case NEVER -> {
                }
            }
        }
        evict.sort(Comparator.comparing(l -> l.holder.lastUsed()));
        pause.sort(Comparator.comparing((Lease l) -> l.granted).reversed());     // the newest job has lost the least work
        var order = new ArrayList<>(evict);
        order.addAll(pause);
        for (var l : order) {
            if (freeing >= needed) break;
            l.preemptedFor = req.holder.name();
            l.preemptedAt = now;
            freeing += l.mib;
            boolean isEvict = l.holder.preemption() == Preemption.EVICT;
            event((isEvict ? "stopping idle " : "pausing ") + l.holder.name() + " to free " + l.mib + " MiB for " + req.holder.name());
            Thread.ofVirtual().name("vram-preempt").start(() -> l.holder.preempt(req.holder.name()));
        }
    }

    private void release(Lease lease) {
        lock.lock();
        try {
            if (lease.released) return;
            lease.released = true;
            leases.remove(lease);
            event(lease.holder.name() + " released " + lease.mib + " MiB" + (lease.preemptedFor != null ? " for " + lease.preemptedFor : ""));
        } finally {
            lock.unlock();
        }
        changed();
    }
}
