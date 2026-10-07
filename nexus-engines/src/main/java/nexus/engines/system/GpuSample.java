package nexus.engines.system;

import java.util.List;

/**
 * One reading of a GPU. Values that the driver does not report are -1.
 *
 * @param source "NVML", "nvidia-smi" or "none"
 */
public record GpuSample(String source, String name, long totalMiB, long usedMiB, long freeMiB, int utilGpu, int utilMemory,
                        int temperatureC, double powerW, double powerLimitW, int smClockMHz, int memClockMHz, int fanPercent,
                        List<GpuProcess> processes) {

    /** A process using the GPU; {@code usedMiB} is -1 where the driver does not say (Windows WDDM). */
    public record GpuProcess(long pid, long usedMiB, String name) {
    }

    public static final GpuSample NONE = new GpuSample("none", "no NVIDIA GPU", 0, 0, 0, -1, -1, -1, -1, -1, -1, -1, -1, List.of());

    public boolean present() {
        return totalMiB > 0;
    }
}
