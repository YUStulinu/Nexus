package nexus.engines.system;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Reads the GPU by running nvidia-smi (the fallback when NVML cannot be loaded). */
public final class SmiProbe implements GpuProbe {
    private static final String QUERY = "name,memory.total,memory.used,memory.free,utilization.gpu,utilization.memory,temperature.gpu,"
                                        + "power.draw,power.limit,clocks.sm,clocks.mem,fan.speed";

    public SmiProbe() {
    }

    @Override
    public String source() {
        return "nvidia-smi";
    }

    @Override
    public GpuSample sample() {
        try {
            var p = new ProcessBuilder("nvidia-smi", "--query-gpu=" + QUERY, "--format=csv,noheader,nounits").redirectErrorStream(true).start();
            String line;
            try (var r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                line = r.readLine();
            }
            if (!p.waitFor(5, TimeUnit.SECONDS) || p.exitValue() != 0 || line == null) return GpuSample.NONE;
            return parse(line);
        } catch (Exception e) {
            return GpuSample.NONE;
        }
    }

    static GpuSample parse(String csvLine) {
        var f = csvLine.split(",\\s*");
        if (f.length < 12) return GpuSample.NONE;
        return new GpuSample("nvidia-smi", f[0].strip(), num(f[1]), num(f[2]), num(f[3]), (int) num(f[4]), (int) num(f[5]), (int) num(f[6]),
                             dbl(f[7]), dbl(f[8]), (int) num(f[9]), (int) num(f[10]), (int) num(f[11]), List.of());
    }

    private static long num(String s) {
        try {
            return Math.round(Double.parseDouble(s.strip()));
        } catch (NumberFormatException e) {
            return -1;       // "[N/A]", "[Not Supported]"
        }
    }

    private static double dbl(String s) {
        try {
            return Double.parseDouble(s.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
