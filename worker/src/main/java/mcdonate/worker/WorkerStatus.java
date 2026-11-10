package mcdonate.worker;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 控制通道 STATUS 的内容（第 9 步；11-09 按 kexu overlay 计划第 6 节加字段，客户端每秒取一次）。
 * 累计数：完成的 tile、导出的 chunk、发出的字节（所有帧）。负载：生成线程数、进程 CPU（0～1，不知道是 -1）、核数、堆。
 */
final class WorkerStatus {
    enum State { RUNNING, PAUSED, STOPPING }

    /** 进程的负载快照。cpuLoad 不知道（刚启动、平台不支持）时是 -1。 */
    record Load(int threads, double cpuLoad, int cpus, long heapUsedMb, long heapMaxMb) {
        Load {
            if (Double.isNaN(cpuLoad) || cpuLoad < 0) {
                cpuLoad = -1;
            }
        }

        static Load current() {
            int cpus = Runtime.getRuntime().availableProcessors();
            // 生成线程数：和原版 Util 的后台线程池一样（核数 − 1，至少 1；max.bg.threads 可以压低）
            int threads = Math.max(1, Math.min(cpus - 1, Integer.getInteger("max.bg.threads", 255)));
            double cpu = -1;
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                cpu = os.getProcessCpuLoad();
            }
            Runtime rt = Runtime.getRuntime();
            long mb = 1024 * 1024;
            return new Load(threads, cpu, cpus, (rt.totalMemory() - rt.freeMemory()) / mb, rt.maxMemory() / mb);
        }
    }

    private final AtomicLong tiles = new AtomicLong();
    private final AtomicLong chunks = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();

    /** 一个 tile 做完、结果发出：chunk 数和这个结果的字节数（已经由 sent 记过的传 0）。 */
    void tileDone(int chunkCount, long resultBytes) {
        tiles.incrementAndGet();
        chunks.addAndGet(chunkCount);
        bytes.addAndGet(resultBytes);
    }

    /** 发出了一帧。 */
    void sent(long frameBytes) {
        bytes.addAndGet(frameBytes);
    }

    String json(State state, long task, boolean connected, boolean needToken, Load l) {
        return "{\"paused\":" + (state == State.PAUSED) + ",\"task\":" + task + ",\"connected\":" + connected
                + ",\"needToken\":" + needToken + ",\"state\":\"" + state.name().toLowerCase(java.util.Locale.ROOT) + "\""
                + ",\"threads\":" + l.threads() + ",\"cpuLoad\":" + fmt(l.cpuLoad()) + ",\"cpus\":" + l.cpus()
                + ",\"heapUsedMb\":" + l.heapUsedMb() + ",\"heapMaxMb\":" + l.heapMaxMb()
                + ",\"tiles\":" + tiles.get() + ",\"chunks\":" + chunks.get() + ",\"bytes\":" + bytes.get() + "}";
    }

    private static String fmt(double v) {
        return v == -1 ? "-1" : Double.toString(Math.round(v * 1000) / 1000.0);
    }
}
