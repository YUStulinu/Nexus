package nexus.engines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import nexus.engines.system.GpuSample;
import nexus.engines.system.VramBroker;
import nexus.engines.system.VramBroker.Preemption;
import nexus.engines.system.VramBroker.Priority;
import org.junit.jupiter.api.Test;

class VramBrokerTest {
    /** A 6 GiB card whose "measured" usage is whatever the test says other programs use plus what the fake holders hold. */
    static final class FakeGpu {
        final AtomicLong other = new AtomicLong(700), held = new AtomicLong();

        GpuSample sample() {
            long used = other.get() + held.get();
            return new GpuSample("test", "Fake GPU", 6144, used, 6144 - used, 0, 0, 40, 10, 100, 0, 0, 0, List.of());
        }
    }

    /** A process that "allocates" its lease when granted and frees it when preempted. */
    static final class FakeHolder implements VramBroker.Holder {
        final String name;
        final Preemption preemption;
        final FakeGpu gpu;
        volatile boolean busy;
        volatile Instant lastUsed;
        volatile VramBroker.Lease lease;
        volatile int preempted;

        FakeHolder(String name, Preemption preemption, FakeGpu gpu, Duration idle) {
            this.name = name;
            this.preemption = preemption;
            this.gpu = gpu;
            this.lastUsed = Instant.now().minus(idle);
        }

        VramBroker.Lease take(VramBroker b, int mib, Priority p, Duration timeout) throws Exception {
            lease = b.acquire(this, mib, p, timeout);
            gpu.held.addAndGet(mib);
            lease.markActive();
            return lease;
        }

        void free() {
            var l = lease;
            lease = null;
            if (l != null) {
                gpu.held.addAndGet(-l.mib());
                l.close();
            }
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Preemption preemption() {
            return preemption;
        }

        @Override
        public boolean preemptibleNow() {
            return !busy;
        }

        @Override
        public Instant lastUsed() {
            return lastUsed;
        }

        @Override
        public void preempt(String requester) {
            preempted++;
            free();
        }
    }

    static final Duration LONG_IDLE = Duration.ofMinutes(5);

    @Test
    void grantsWhatFitsAndCountsOtherPrograms() throws Exception {
        var gpu = new FakeGpu();
        var b = new VramBroker(gpu::sample, 300);
        // 6144 - 300 margin - 700 other = 5144 available.
        var a = new FakeHolder("a", Preemption.NEVER, gpu, Duration.ZERO);
        a.take(b, 3000, Priority.INTERACTIVE, Duration.ofSeconds(1));
        assertEquals(2144, b.state().available());
        // Another program grows by 1500 MiB: the broker sees it in the measured usage.
        gpu.other.addAndGet(1500);
        assertEquals(644, b.state().available());
        var c = new FakeHolder("c", Preemption.NEVER, gpu, Duration.ZERO);
        var e = assertThrows(TimeoutException.class, () -> c.take(b, 1000, Priority.INTERACTIVE, Duration.ofMillis(300)));
        assertTrue(e.getMessage().contains("3000 held by a"), e.getMessage());
        assertTrue(b.state().waiting().isEmpty(), "a timed-out request leaves the queue");
    }

    @Test
    void aLeaseNotYetLoadedStillCounts() throws Exception {
        var gpu = new FakeGpu();
        var b = new VramBroker(gpu::sample, 300);
        var a = new FakeHolder("a", Preemption.NEVER, gpu, Duration.ZERO);
        b.acquire(a, 4000, Priority.INTERACTIVE, Duration.ofSeconds(1));    // granted, process still loading: not in "used" yet
        assertEquals(1144, b.state().available());
        var c = new FakeHolder("c", Preemption.NEVER, gpu, Duration.ZERO);
        assertThrows(TimeoutException.class, () -> c.take(b, 2000, Priority.INTERACTIVE, Duration.ofMillis(200)));
    }

    @Test
    void waitingRequestIsGrantedWhenMemoryIsReleased() throws Exception {
        var gpu = new FakeGpu();
        var b = new VramBroker(gpu::sample, 300);
        var a = new FakeHolder("a", Preemption.NEVER, gpu, Duration.ZERO);
        a.take(b, 4000, Priority.INTERACTIVE, Duration.ofSeconds(1));
        var c = new FakeHolder("c", Preemption.NEVER, gpu, Duration.ZERO);
        var got = CompletableFuture.supplyAsync(() -> {
            try {
                return c.take(b, 3000, Priority.INTERACTIVE, Duration.ofSeconds(5));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(200);
        assertFalse(got.isDone());
        assertEquals(1, b.state().waiting().size());
        a.free();
        assertEquals(3000, got.get(3, TimeUnit.SECONDS).mib());
    }

    @Test
    void interactiveRequestEvictsTheLeastRecentlyUsedIdleEngine() throws Exception {
        var gpu = new FakeGpu();
        var b = new VramBroker(gpu::sample, 300);
        var old = new FakeHolder("old", Preemption.EVICT, gpu, Duration.ofMinutes(10));
        var recent = new FakeHolder("recent", Preemption.EVICT, gpu, Duration.ofMinutes(1));
        old.take(b, 2000, Priority.INTERACTIVE, Duration.ofSeconds(1));
        recent.take(b, 2000, Priority.INTERACTIVE, Duration.ofSeconds(1));
        // 5144 - 4000 = 1144 available; 2500 needed: one eviction is enough, and it must be "old".
        var c = new FakeHolder("c", Preemption.NEVER, gpu, Duration.ZERO);
        c.take(b, 2500, Priority.INTERACTIVE, Duration.ofSeconds(3));
        assertEquals(1, old.preempted);
        assertEquals(0, recent.preempted);
        assertTrue(b.events().stream().anyMatch(e -> e.text().contains("stopping idle old")));
    }

    @Test
    void aBusyEngineIsNeverEvicted() throws Exception {
        var gpu = new FakeGpu();
        var b = new VramBroker(gpu::sample, 300);
        var busy = new FakeHolder("busy", Preemption.EVICT, gpu, LONG_IDLE);
        busy.take(b, 4000, Priority.INTERACTIVE, Duration.ofSeconds(1));
        busy.busy = true;
        var c = new FakeHolder("c", Preemption.NEVER, gpu, Duration.ZERO);
        assertThrows(TimeoutException.class, () -> c.take(b, 2000, Priority.INTERACTIVE, Duration.ofMillis(400)));
        assertEquals(0, busy.preempted);
    }

    @Test
    void onlyInteractiveWorkPausesTraining() throws Exception {
        var gpu = new FakeGpu();
        var b = new VramBroker(gpu::sample, 300);
        var training = new FakeHolder("training", Preemption.PAUSE, gpu, Duration.ZERO);
        training.take(b, 4000, Priority.BACKGROUND, Duration.ofSeconds(1));
        // Another background job must wait: it cannot pause training.
        var job = new FakeHolder("job", Preemption.PAUSE, gpu, Duration.ZERO);
        assertThrows(TimeoutException.class, () -> job.take(b, 2000, Priority.BACKGROUND, Duration.ofMillis(300)));
        assertEquals(0, training.preempted);
        // A chat model can.
        var llm = new FakeHolder("llm", Preemption.EVICT, gpu, Duration.ZERO);
        llm.take(b, 2000, Priority.INTERACTIVE, Duration.ofSeconds(3));
        assertEquals(1, training.preempted);
        assertTrue(b.events().stream().anyMatch(e -> e.text().contains("pausing training")));
    }

    @Test
    void backgroundWorkEvictsOnlyLongIdleEngines() throws Exception {
        var gpu = new FakeGpu();
        var b = new VramBroker(gpu::sample, 300);
        var llm = new FakeHolder("llm", Preemption.EVICT, gpu, Duration.ofSeconds(20));    // idle 20 s: < 60 s
        llm.take(b, 4000, Priority.INTERACTIVE, Duration.ofSeconds(1));
        var training = new FakeHolder("training", Preemption.PAUSE, gpu, Duration.ZERO);
        assertThrows(TimeoutException.class, () -> training.take(b, 2000, Priority.BACKGROUND, Duration.ofMillis(300)));
        assertEquals(0, llm.preempted);
        llm.lastUsed = Instant.now().minus(LONG_IDLE);
        training.take(b, 2000, Priority.BACKGROUND, Duration.ofSeconds(3));
        assertEquals(1, llm.preempted);
    }

    @Test
    void interactiveRequestsGoAheadOfEarlierBackgroundOnes() throws Exception {
        var gpu = new FakeGpu();
        var b = new VramBroker(gpu::sample, 300);
        var a = new FakeHolder("a", Preemption.NEVER, gpu, Duration.ZERO);
        a.take(b, 4000, Priority.INTERACTIVE, Duration.ofSeconds(1));
        var bg = new FakeHolder("bg", Preemption.NEVER, gpu, Duration.ZERO);
        var fg = new FakeHolder("fg", Preemption.NEVER, gpu, Duration.ZERO);
        var bgDone = CompletableFuture.runAsync(() -> {
            try {
                bg.take(b, 3000, Priority.BACKGROUND, Duration.ofSeconds(5));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(150);
        var fgDone = CompletableFuture.runAsync(() -> {
            try {
                fg.take(b, 3000, Priority.INTERACTIVE, Duration.ofSeconds(5));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(150);
        assertEquals(List.of("fg", "bg"), b.state().waiting().stream().map(VramBroker.Waiting::holder).toList());
        a.free();
        fgDone.get(3, TimeUnit.SECONDS);
        Thread.sleep(300);
        assertFalse(bgDone.isDone(), "only one of the two fits");
        fg.free();
        bgDone.get(3, TimeUnit.SECONDS);
    }

    @Test
    void withoutAGpuEverythingIsGranted() throws Exception {
        var b = new VramBroker(() -> GpuSample.NONE, 300);
        var a = new FakeHolder("a", Preemption.NEVER, new FakeGpu(), Duration.ZERO);
        assertEquals(99999, b.acquire(a, 99999, Priority.INTERACTIVE, Duration.ofMillis(10)).mib());
        assertEquals(0, b.state().total());
    }

    @Test
    void refusesRequestsLargerThanTheCard() {
        var gpu = new FakeGpu();
        var b = new VramBroker(gpu::sample, 300);
        var a = new FakeHolder("a", Preemption.NEVER, gpu, Duration.ZERO);
        assertThrows(IllegalArgumentException.class, () -> b.acquire(a, 6000, Priority.INTERACTIVE, Duration.ofSeconds(1)));
    }
}
