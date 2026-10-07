package nexus.engines.system;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Samples the machine once a second - GPU (through a {@link GpuProbe}), CPU load of the system and
 * of NEXUS, physical memory, the Java heap - and keeps the last five minutes for the dashboard's
 * charts. Starts on first use, runs on a daemon thread.
 */
public final class SystemMonitor implements AutoCloseable {
    public static final int HISTORY = 300;

    /** One reading of the whole machine. */
    public record Sample(long timeMillis, GpuSample gpu, double cpuSystem, double cpuProcess, long ramTotalMiB, long ramFreeMiB,
                         long heapUsedMiB, long heapMaxMiB, int threads) {
    }

    private final GpuProbe probe;
    private final Duration period;
    private final ArrayDeque<Sample> history = new ArrayDeque<>();
    private final List<Consumer<Sample>> listeners = new CopyOnWriteArrayList<>();
    private volatile Sample latest;
    private Thread thread;

    public SystemMonitor(GpuProbe probe, Duration period) {
        this.probe = probe;
        this.period = period;
    }

    public GpuProbe probe() {
        return probe;
    }

    public synchronized void start() {
        if (thread != null) return;
        latest = read();
        thread = Thread.ofPlatform().daemon().name("nexus-system-monitor").start(this::loop);
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            var s = read();
            latest = s;
            synchronized (history) {
                history.addLast(s);
                while (history.size() > HISTORY) history.removeFirst();
            }
            for (var l : listeners) {
                try {
                    l.accept(s);
                } catch (RuntimeException ignored) {
                    // a listener's problem must not stop the sampling
                }
            }
            try {
                Thread.sleep(period);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** The most recent sample (taking one now if the monitor has not started). */
    public Sample latest() {
        var s = latest;
        return s != null ? s : (latest = read());
    }

    public List<Sample> history() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    public void addListener(Consumer<Sample> l) {
        listeners.add(l);
    }

    public void removeListener(Consumer<Sample> l) {
        listeners.remove(l);
    }

    /** A reading taken now (not recorded in the history). */
    public Sample sampleNow() {
        return read();
    }

    Sample read() {
        var gpu = probe.sample();
        double cpuSys = -1, cpuProc = -1;
        long ramTotal = -1, ramFree = -1;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            cpuSys = os.getCpuLoad();
            cpuProc = os.getProcessCpuLoad();
            ramTotal = os.getTotalMemorySize() >> 20;
            ramFree = os.getFreeMemorySize() >> 20;
        }
        var rt = Runtime.getRuntime();
        return new Sample(System.currentTimeMillis(), gpu, cpuSys, cpuProc, ramTotal, ramFree, (rt.totalMemory() - rt.freeMemory()) >> 20,
                          rt.maxMemory() >> 20, Thread.activeCount());
    }

    @Override
    public synchronized void close() {
        if (thread != null) thread.interrupt();
        probe.close();
    }
}
