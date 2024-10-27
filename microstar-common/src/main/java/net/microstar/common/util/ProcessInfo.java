package net.microstar.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import oshi.SystemInfo;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OSProcess;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;

/** Collect some process info (mostly memory related) to determine what the footprint
  * of the current service is.<p>
  *
  * Also see: https://stackoverflow.com/questions/53451103/java-using-much-more-memory-than-heap-size-or-size-correctly-docker-memory-limi
  */
public class ProcessInfo {
    public final LocalDateTime timestamp;
    public final int      pid;
    public final ByteSize virtualMemorySize;
    public final ByteSize residentMemorySize;
    public final ByteSize heapSize;
    public final ByteSize heapUsed;
    public final int      heapUsedPercent;
    public final ByteSize minHeapUsed;
    public final ByteSize metaSpace;
    public final Duration uptime;
    public final ByteSize sysMem;
    public final ByteSize sysMemAvailable;
    public final int      sysMemAvailablePercent;

    private static final Duration UPDATE_INTERVAL = Duration.ofSeconds(10);
    private static final AtomicReference<ProcessInfo> latest = new AtomicReference<>();
    private static final long[] minHeapUse = new long[Math.max(1, (int)(300 / UPDATE_INTERVAL.toSeconds()))];
    private static final AtomicInteger minHeapUseIndex = new AtomicInteger(0);

    static {
        update();
        TimedRunner.runPeriodicallyAtFixedDelay("ProcessInfo", UPDATE_INTERVAL, UPDATE_INTERVAL, ProcessInfo::update);
    }

    public static void update() {
        latest.set(new ProcessInfo());
        minHeapUse[minHeapUseIndex.getAndIncrement()%minHeapUse.length] = latest.get().heapUsed.getBytesLong();
    }

    public static ProcessInfo getLatest() {
        return latest.get();
    }

    private ProcessInfo() {
        timestamp = LocalDateTime.now();

        // First call takes 1s to initialize SystemInfo library, after that it is 0.05s (roughly)
        final SystemInfo sys   = new SystemInfo();

        final OSProcess proc   = sys.getOperatingSystem().getCurrentProcess();
        pid                    = proc.getProcessID();
        virtualMemorySize      = ByteSize.ofBytes(proc.getVirtualSize());
        residentMemorySize     = ByteSize.ofBytes(proc.getResidentSetSize());
        uptime                 = Duration.ofMillis(new SystemInfo().getOperatingSystem().getCurrentProcess().getUpTime());

        final Runtime runtime  = Runtime.getRuntime();
        heapSize               = ByteSize.ofBytes(runtime.totalMemory());
        heapUsed               = ByteSize.ofBytes(runtime.totalMemory() - runtime.freeMemory());
        heapUsedPercent        = (int)((100 * heapUsed.getBytesLong()) / heapSize.getBytesLong());
        minHeapUsed            = ByteSize.ofBytes(getMinHeapUse());
        metaSpace              = ByteSize.ofBytes(ManagementFactory.getMemoryPoolMXBeans().stream().filter(mb->"Metaspace".equals(mb.getName())).map(mb->mb.getUsage().getUsed()).findFirst().orElse(0L));

        // Update minHeapUse so minUse is never higher than current heap use
        final int mhuIndex = minHeapUseIndex.get() % minHeapUse.length;
        minHeapUse[mhuIndex] = Math.min(heapUsed.getBytesLong(), minHeapUse[mhuIndex]); // NOSONAR intentional static update

        final GlobalMemory mem = sys.getHardware().getMemory();
        sysMem                 = ByteSize.ofBytes(mem.getTotal());
        sysMemAvailable        = ByteSize.ofBytes(mem.getAvailable());
        sysMemAvailablePercent = (int)((100 * sysMemAvailable.getBytesLong()) / sysMem.getBytesLong());
    }

    private static long getMinHeapUse() {
        return LongStream.of(minHeapUse).filter(use -> use > 0).min().orElse(0);
    }

    public static Map<String,Object> getSystemInfo() {
        final HardwareAbstractionLayer hal = new SystemInfo().getHardware();
        try {
            final ObjectMapper mapper = new ObjectMapper();
            final Map<String,Object> info = Map.of(
                "computerSystem", hal.getComputerSystem(),
                "memory",         hal.getMemory(),
                "network",        hal.getNetworkIFs());
            final String json = mapper.writeValueAsString(info);
            return mapper.readValue(json, new TypeReference<>() {});
        } catch(final Exception e) {
            return new HashMap<>();
        }
    }
}
