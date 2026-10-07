package nexus.engines.system;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the GPU through NVML, the NVIDIA Management Library that ships with the driver
 * (nvml.dll / libnvidia-ml.so.1), called directly with the Foreign Function & Memory API - no JNI,
 * no helper process. A sample takes microseconds, against ~100 ms for spawning nvidia-smi.
 */
@SuppressWarnings("restricted")      // native access is the point of this class (enabled for module nexus.engines)
public final class NvmlProbe implements GpuProbe {
    private static final int SUCCESS = 0, NOT_SUPPORTED = 3;
    private static final long NOT_AVAILABLE = -1L;         // 0xFFFFFFFFFFFFFFFF for unknown process memory
    private static final int CLOCK_SM = 1, CLOCK_MEM = 2, TEMPERATURE_GPU = 0;

    private final Arena arena = Arena.ofShared();
    private final MethodHandle getHandle, getName, getMemory, getUtilization, getTemperature, getPower, getPowerLimit, getClock, getFan,
            getProcesses, shutdown;
    private final MemorySegment device;
    private final String name;
    private final int processInfoSize;
    private volatile boolean closed;
    /** Executable names by pid (looking them up costs far more than the NVML calls). */
    private final Map<Long, String> names = new HashMap<>();

    public NvmlProbe() {
        var linker = Linker.nativeLinker();
        String lib = System.getProperty("os.name", "").toLowerCase().contains("win") ? "nvml.dll" : "libnvidia-ml.so.1";
        var lookup = SymbolLookup.libraryLookup(lib, arena);
        var init = handle(linker, lookup, "nvmlInit_v2", FunctionDescriptor.of(JAVA_INT));
        getHandle = handle(linker, lookup, "nvmlDeviceGetHandleByIndex_v2", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        getName = handle(linker, lookup, "nvmlDeviceGetName", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        getMemory = handle(linker, lookup, "nvmlDeviceGetMemoryInfo", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        getUtilization = handle(linker, lookup, "nvmlDeviceGetUtilizationRates", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        getTemperature = handle(linker, lookup, "nvmlDeviceGetTemperature", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        getPower = handle(linker, lookup, "nvmlDeviceGetPowerUsage", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        getPowerLimit = handle(linker, lookup, "nvmlDeviceGetEnforcedPowerLimit", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        getClock = handle(linker, lookup, "nvmlDeviceGetClockInfo", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        getFan = optionalHandle(linker, lookup, "nvmlDeviceGetFanSpeed", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)).orElse(null);
        // nvmlProcessInfo_t: v2/v3 {uint pid; ull usedGpuMemory; uint gpuInstanceId; uint computeInstanceId} = 24 bytes;
        // the original v1 {uint pid; ull usedGpuMemory} = 16 bytes.
        var v3 = optionalHandle(linker, lookup, "nvmlDeviceGetComputeRunningProcesses_v3", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        if (v3.isPresent()) {
            getProcesses = v3.get();
            processInfoSize = 24;
        } else {
            getProcesses = handle(linker, lookup, "nvmlDeviceGetComputeRunningProcesses", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            processInfoSize = 16;
        }
        shutdown = handle(linker, lookup, "nvmlShutdown", FunctionDescriptor.of(JAVA_INT));
        try {
            check("nvmlInit", (int) init.invokeExact());
            var h = arena.allocate(ADDRESS);
            check("nvmlDeviceGetHandleByIndex", (int) getHandle.invokeExact(0, h));
            device = h.get(ADDRESS, 0);
            var buf = arena.allocate(96);
            check("nvmlDeviceGetName", (int) getName.invokeExact(device, buf, 96));
            name = buf.getString(0);
        } catch (Throwable t) {
            throw new IllegalStateException("NVML: " + t.getMessage(), t);
        }
    }

    private static MethodHandle handle(Linker linker, SymbolLookup lookup, String fn, FunctionDescriptor d) {
        return optionalHandle(linker, lookup, fn, d).orElseThrow(() -> new IllegalStateException("NVML has no " + fn));
    }

    private static Optional<MethodHandle> optionalHandle(Linker linker, SymbolLookup lookup, String fn, FunctionDescriptor d) {
        return lookup.find(fn).map(addr -> linker.downcallHandle(addr, d));
    }

    private static void check(String what, int rc) {
        if (rc != SUCCESS) throw new IllegalStateException(what + " returned " + rc);
    }

    @Override
    public String source() {
        return "NVML";
    }

    @Override
    public synchronized GpuSample sample() {
        if (closed) return GpuSample.NONE;
        try (var a = Arena.ofConfined()) {
            var mem = a.allocate(24);
            check("nvmlDeviceGetMemoryInfo", (int) getMemory.invokeExact(device, mem));
            long total = mem.get(JAVA_LONG, 0) >> 20, free = mem.get(JAVA_LONG, 8) >> 20, used = mem.get(JAVA_LONG, 16) >> 20;
            var util = a.allocate(8);
            int ug = -1, um = -1;
            if ((int) getUtilization.invokeExact(device, util) == SUCCESS) {
                ug = util.get(JAVA_INT, 0);
                um = util.get(JAVA_INT, 4);
            }
            var u = a.allocate(JAVA_INT);
            int temp = (int) getTemperature.invokeExact(device, TEMPERATURE_GPU, u) == SUCCESS ? u.get(JAVA_INT, 0) : -1;
            double power = (int) getPower.invokeExact(device, u) == SUCCESS ? u.get(JAVA_INT, 0) / 1000.0 : -1;
            double limit = (int) getPowerLimit.invokeExact(device, u) == SUCCESS ? u.get(JAVA_INT, 0) / 1000.0 : -1;
            int sm = (int) getClock.invokeExact(device, CLOCK_SM, u) == SUCCESS ? u.get(JAVA_INT, 0) : -1;
            int memClock = (int) getClock.invokeExact(device, CLOCK_MEM, u) == SUCCESS ? u.get(JAVA_INT, 0) : -1;
            int fan = getFan != null && (int) getFan.invokeExact(device, u) == SUCCESS ? u.get(JAVA_INT, 0) : -1;
            return new GpuSample("NVML", name, total, used, free, ug, um, temp, power, limit, sm, memClock, fan, processes(a));
        } catch (Throwable t) {
            return GpuSample.NONE;
        }
    }

    private List<GpuSample.GpuProcess> processes(Arena a) throws Throwable {
        int max = 64;
        var count = a.allocate(JAVA_INT);
        count.set(JAVA_INT, 0, max);
        var infos = a.allocate((long) processInfoSize * max, 8);
        int rc = (int) getProcesses.invokeExact(device, count, infos);
        if (rc == NOT_SUPPORTED || rc != SUCCESS) return List.of();
        var byPid = new LinkedHashMap<Long, GpuSample.GpuProcess>();
        for (int i = 0; i < count.get(JAVA_INT, 0); i++) {
            long base = (long) i * processInfoSize;
            long pid = Integer.toUnsignedLong(infos.get(JAVA_INT, base));
            long bytes = infos.get(JAVA_LONG, base + 8);
            long mib = bytes == NOT_AVAILABLE ? -1 : bytes >> 20;
            String exe = names.computeIfAbsent(pid, x -> ProcessHandle.of(x).flatMap(ph -> ph.info().command())
                                                                   .map(c -> c.replaceAll(".*[\\\\/]", "")).orElse("pid " + x));
            byPid.putIfAbsent(pid, new GpuSample.GpuProcess(pid, mib, exe));
        }
        names.keySet().retainAll(byPid.keySet());
        return new ArrayList<>(byPid.values());
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            int rc = (int) shutdown.invokeExact();
            if (rc != SUCCESS) System.getLogger(NvmlProbe.class.getName()).log(System.Logger.Level.DEBUG, "nvmlShutdown returned " + rc);
        } catch (Throwable t) {
            // shutting down anyway
        }
        arena.close();
    }
}
