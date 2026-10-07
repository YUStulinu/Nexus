package nexus.engines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import nexus.engines.system.GpuProbe;
import nexus.engines.system.NvmlProbe;
import nexus.engines.system.SmiProbe;
import nexus.engines.system.SystemMonitor;
import org.junit.jupiter.api.Test;

class GpuProbeTest {
    @Test
    void parsesNvidiaSmiOutput() throws Exception {
        var m = SmiProbe.class.getDeclaredMethod("parse", String.class);
        m.setAccessible(true);
        var s = (nexus.engines.system.GpuSample) m.invoke(null, "NVIDIA GeForce RTX 2060, 6144, 733, 5223, 6, 3, 47, 11.10, 80.00, 300, 405, [N/A]");
        assertEquals("NVIDIA GeForce RTX 2060", s.name());
        assertEquals(6144, s.totalMiB());
        assertEquals(733, s.usedMiB());
        assertEquals(47, s.temperatureC());
        assertEquals(11.1, s.powerW(), 1e-9);
        assertEquals(-1, s.fanPercent());
    }

    /** On a machine with an NVIDIA GPU, NVML (through FFM) and nvidia-smi must agree. */
    @Test
    void nvmlAgreesWithNvidiaSmi() {
        var smi = new SmiProbe().sample();
        assumeTrue(smi.present(), "no NVIDIA GPU");
        try (var nvml = new NvmlProbe()) {
            var n = nvml.sample();
            System.out.printf("NVML: %s, %d/%d MiB, %d%% util, %d C, %.1f W, SM %d MHz, %d processes%n", n.name(), n.usedMiB(), n.totalMiB(),
                              n.utilGpu(), n.temperatureC(), n.powerW(), n.smClockMHz(), n.processes().size());
            n.processes().forEach(p -> System.out.println("  pid " + p.pid() + " " + p.name() + " " + p.usedMiB() + " MiB"));
            assertEquals(smi.name(), n.name());
            assertEquals(smi.totalMiB(), n.totalMiB());
            assertTrue(Math.abs(smi.usedMiB() - n.usedMiB()) < 300, smi.usedMiB() + " vs " + n.usedMiB());
            assertTrue(n.temperatureC() > 0 && n.temperatureC() < 110);
            long t0 = System.nanoTime();
            for (int i = 0; i < 100; i++) nvml.sample();
            System.out.printf("NVML sample: %.3f ms%n", (System.nanoTime() - t0) / 1e6 / 100);
            t0 = System.nanoTime();
            for (int i = 0; i < 5; i++) new SmiProbe().sample();
            System.out.printf("nvidia-smi sample: %.1f ms%n", (System.nanoTime() - t0) / 1e6 / 5);
        }
    }

    @Test
    void monitorSamplesCpuAndMemory() {
        try (var m = new SystemMonitor(GpuProbe.detect(), java.time.Duration.ofMillis(50))) {
            var s = m.latest();
            assertTrue(s.ramTotalMiB() > 0);
            assertTrue(s.heapMaxMiB() > 0);
            assertFalse(s.gpu().source().isEmpty());
        }
    }
}
