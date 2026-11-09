package mcdonate.coordinator;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import mcdonate.common.Config;
import mcdonate.common.TilePos;

/**
 * 7.6 等 donor（开关 waitdonor，设计见 docs/step7-waitdonor-notes.md）。
 *
 * <p>原版第一次要一个 chunk 时（ChunkMap.scheduleChunkLoad，先读存档、没有才从头生成），如果它在一个正租给
 * donor、预计 MAX_WAIT_MS 内回来的 tile 区域里，就先等这个任务的"闸门"，等完再走原版的读取：
 * donor 的结果已经写进存档，原版读到的就是它。闸门一个任务一个，区域里的 chunk 共用；最多等 MAX_WAIT_MS，
 * 超时照常走原版（从头生成）。
 *
 * <p>正在等的 chunk 记在 WAITING 里，导入时它们不算"在内存里"（holder 在，但还没读存档、没有 chunk 对象，
 * 写存档不会被覆盖）。
 */
public final class WaitDonor {
    /** 最多等多久（配置项 waitDonorMaxMs）。 */
    public static final long MAX_WAIT_MS = Config.getLong("waitDonorMaxMs", 2_000);

    static volatile WaitDonor instance;
    private static final Set<Long> WAITING = ConcurrentHashMap.newKeySet();
    /** 已经开始读存档的 chunk（scheduleChunkLoad 被调过、不是在闸门上等着的）：导入时算在内存里。存盘时去掉。 */
    private static final Set<Long> STARTED = ConcurrentHashMap.newKeySet();
    /**
     * 主线程正在同步要 chunk（嵌套层数）：这期间不建新闸门。主线程上改，但建闸门（scheduleChunkLoad）
     * 不一定在主线程上，所以用原子变量（7.6a 实测：普通 int 时同步等待期间照样建了 9 个闸门）。
     */
    private static final AtomicInteger SYNC_DEPTH = new AtomicInteger();

    private final Features features;
    private final TaskQueue tasks;
    private final DonorThroughput throughput;
    private final Map<Long, CompletableFuture<Void>> gates = new ConcurrentHashMap<>();
    final AtomicInteger waits = new AtomicInteger();
    final AtomicInteger hits = new AtomicInteger();
    final AtomicInteger timeouts = new AtomicInteger();
    /** 主线程同步要 chunk 时打开的闸门数（这些任务的结果会被丢弃）。 */
    final AtomicInteger abandoned = new AtomicInteger();
    /**
     * 有闸门的任务，结果在哪个队列上导入。true（正确做法）：chunk 缓存自己的队列（ChunkMap 的 mainThreadExecutor），
     * 主线程同步等 chunk（ServerChunkCache.getChunk 的 managedBlock）时也会执行它；false：server.execute，
     * 同步等的时候不执行，只能等到超时。false 只给测试用（证明测试真的覆盖了这个坑）。
     */
    volatile boolean importViaChunkCache = true;

    WaitDonor(Features features, TaskQueue tasks, DonorThroughput throughput) {
        this.features = features;
        this.tasks = tasks;
        this.throughput = throughput;
    }

    /** Mixin 调：这个 chunk 要不要等；不等返回 null。 */
    public static CompletableFuture<Void> gate(long chunk) {
        WaitDonor w = instance;
        return w == null ? null : w.gateFor(chunk, System.currentTimeMillis());
    }

    CompletableFuture<Void> gateFor(long chunk, long now) {
        if (!features.on(Features.WAITDONOR) || SYNC_DEPTH.get() > 0) {
            return null;
        }
        int cx = (int) chunk, cz = (int) (chunk >>> 32);
        TilePos home = TilePos.ofChunk(cx, cz);
        // 这个 chunk 可能在自己 tile 的区域里，也可能在相邻 tile 的外圈里
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                TilePos t = new TilePos(home.x() + dx, home.z() + dz);
                if (t.ring(cx, cz) > 1) {
                    continue;
                }
                long id = tasks.find(t.x(), t.z());
                if (id < 0) {
                    continue;
                }
                Optional<TaskQueue.Lease> l = tasks.lease(id);
                if (l.isEmpty()) {
                    continue;
                }
                long remaining = l.get().leasedAt() + throughput.averageMs(l.get().worker()) - now;
                if (remaining > MAX_WAIT_MS) {
                    continue;
                }
                WAITING.add(chunk);
                waits.incrementAndGet();
                return gates.computeIfAbsent(id, k -> new CompletableFuture<>());
            }
        }
        return null;
    }

    /** Mixin 调：不再等了（闸门开了或超时），接下来走原版读取。 */
    public static void leave(long chunk, boolean timedOut) {
        WAITING.remove(chunk);
        WaitDonor w = instance;
        if (w != null && timedOut) {
            w.timeouts.incrementAndGet();
        }
    }

    static boolean isWaiting(long chunk) {
        return WAITING.contains(chunk);
    }

    /** 开关开着没有（Mixin 用：开着才记 STARTED）。 */
    public static boolean on() {
        WaitDonor w = instance;
        return w != null && w.features.on(Features.WAITDONOR);
    }

    /** Mixin 调：这个 chunk 开始读存档了（不在闸门上等）。 */
    public static void started(long chunk) {
        STARTED.add(chunk);
    }

    /** 存盘时调：之后再要它会重新读存档。 */
    public static void forgetStarted(long chunk) {
        STARTED.remove(chunk);
    }

    /**
     * 导入时用（开关开着时）：这个 chunk 算不算在内存里。有 holder、但还没开始读存档的不算：
     * 之后它读存档时，读请求排在导入的写后面（IOWorker 一个队列、先进先出），读到的就是导入的数据。
     */
    static boolean loadStarted(long chunk) {
        return STARTED.contains(chunk) && !WAITING.contains(chunk);
    }

    /** 主线程开始同步等 chunk：打开所有闸门，期间不建新的。 */
    public static void enterSync() {
        SYNC_DEPTH.incrementAndGet();
        WaitDonor w = instance;
        if (w != null && !w.gates.isEmpty()) {
            for (Long id : java.util.List.copyOf(w.gates.keySet())) {
                w.abandoned.incrementAndGet();
                w.release(id);
            }
        }
    }

    public static void leaveSync() {
        SYNC_DEPTH.updateAndGet(d -> Math.max(0, d - 1));
    }

    boolean hasGate(long taskId) {
        return gates.containsKey(taskId);
    }

    /** 任务有结果了（导入完、丢弃、失败、撤回）：开闸。 */
    void release(long taskId) {
        CompletableFuture<Void> g = gates.remove(taskId);
        if (g != null) {
            g.complete(null);
        }
    }

    String json() {
        return "{\"waits\":" + waits.get() + ",\"hits\":" + hits.get() + ",\"timeouts\":" + timeouts.get()
                + ",\"abandoned\":" + abandoned.get()
                + ",\"waiting\":" + WAITING.size() + ",\"maxWaitMs\":" + MAX_WAIT_MS
                + ",\"importVia\":\"" + (importViaChunkCache ? "chunkcache" : "server") + "\"}";
    }
}
