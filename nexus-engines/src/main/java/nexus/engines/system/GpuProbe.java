package nexus.engines.system;

/** Reads the state of the GPU. */
public interface GpuProbe extends AutoCloseable {
    GpuSample sample();

    String source();

    @Override
    default void close() {
    }

    /** NVML through the Foreign Function & Memory API if the driver library loads, else nvidia-smi, else nothing. */
    static GpuProbe detect() {
        try {
            var nvml = new NvmlProbe();
            if (nvml.sample().present()) return nvml;
            nvml.close();
        } catch (Throwable ignored) {
            // no NVML (no NVIDIA driver, or native access denied): try the command-line tool
        }
        var smi = new SmiProbe();
        if (smi.sample().present()) return smi;
        return new GpuProbe() {
            @Override
            public GpuSample sample() {
                return GpuSample.NONE;
            }

            @Override
            public String source() {
                return "none";
            }
        };
    }
}
