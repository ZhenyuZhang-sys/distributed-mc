package mcdonate.coordinator;

import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import mcdonate.common.Config;
import net.minecraft.Util;
import mcdonate.common.TilePos;
import mcdonate.coordinator.RegionRules.ChunkInfo;
import mcdonate.coordinator.mixin.ChunkMapAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * 找需求（第 6 步 6.3）：每秒在主线程上看一次主世界的玩家，把周围符合条件的 tile 放进队列。
 *
 * <p>范围：离玩家 inner 圈以外、outer 圈以内的 tile。
 * <ul>
 *   <li>inner = 视距 + ChunkLevel.RADIUS_AROUND_FULL_CHUNK + 1：服务器为了把视距内的 chunk 做成成品，
 *       会把它们的依赖（邻居的各个中间阶段，最外层只到结构起点）一直占在内存里，约往外 11 圈。
 *       按接缝规则，区域碰到内存里的 chunk 就不能交给 donor（它之后会被服务器存盘、覆盖导入的数据）。
 *       再加 1 是 tile 的区域比 tile 多一圈。
 *   <li>outer = inner + 提前量（系统属性 mcdonate.demandLookahead，默认 8 圈）。
 * </ul>
 * 最早按"视距 + 12"写死，没考虑原版的依赖范围，tile 回来时区域里的 chunk 已经被服务器加载，全部被丢弃。
 *
 * <p>查存档不卡主线程：第一次查某个 chunk 时在存档读写线程上异步读，结果进缓存；还没查到的当"不确定"，
 * 这一轮不入队。服务器存盘（ChunkMapSaveMixin）和导入时更新缓存。缓存只在主线程上读写，
 * 别的线程来的变化先排进 EVENTS，扫描开始时并进来。
 */
public final class DemandScanner {
    static final int QUEUE_CAP = Config.getInt("demandQueueCap", 256);
    /** inner 在"视距 + 原版占内存的圈数"之外再留几圈。 */
    /** 已知含有服务器 chunk 的 tile（键是 tile 坐标打包成 long），只在主线程上用。 */
    private final LongOpenHashSet serverTiles = new LongOpenHashSet();
    /** 每次扫描最多发起几次读存档：刚开始时玩家周围几千个 chunk 都不知道状态，一次全发会让那一次扫描很慢。 */
    static final int READS_PER_SCAN = Config.getInt("demandReadsPerScan", 256);
    private int readsLeft;
    /** 每次扫描最多占主线程多久；没查完的 tile 下一秒再查（按离玩家远近，近的先查）。 */
    static final long BUDGET_NANOS = Config.getLong("demandScanBudgetMicros", 500) * 1000;
    private final LongArrayList toRead = new LongArrayList();
    static final int INNER_MARGIN = Config.getInt("demandInnerMargin", 1);
    private static final int PENDING = Integer.MIN_VALUE;
    private static final int ABSENT = -1;
    private static final int UNKNOWN = Integer.MIN_VALUE + 1;
    /** 事件里表示"忘掉这个 chunk，下次重新查" */
    private static final int FORGET = Integer.MIN_VALUE + 2;

    /**
     * 主世界里查过的 chunk：存档里的状态序号，ABSENT 不存在，PENDING 正在查。只在主线程上用（不装箱、不加锁）。
     * 读盘线程的结果、存盘和导入的变化都先进 EVENTS，扫描开始时在主线程上并进来。
     */
    private static final Long2IntOpenHashMap DISK = new Long2IntOpenHashMap();
    /** {chunk, 状态}；读盘结果只在当时还是 PENDING 时生效（第三个元素为 1）。 */
    private static final ConcurrentLinkedQueue<long[]> EVENTS = new ConcurrentLinkedQueue<>();

    static {
        DISK.defaultReturnValue(UNKNOWN);
    }

    private final MinecraftServer server;
    private final TaskQueue tasks;
    private final int lookahead = Config.getInt("demandLookahead", 8);
    long scans;
    long totalNanos;
    long maxNanos;
    long totalCpuNanos;
    private static final java.lang.management.ThreadMXBean THREADS = java.lang.management.ManagementFactory.getThreadMXBean();
    /** 最近 64 次扫描：[墙钟微秒, 主线程 CPU 微秒, 发起的读存档次数]，排查耗时尖峰用。 */
    private final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();

    DemandScanner(MinecraftServer server, TaskQueue tasks) {
        this.server = server;
        this.tasks = tasks;
        SERVER = server;
    }

    /** 主线程上的变化直接改 DISK，别的线程的排进 EVENTS（存盘每个 chunk 都会调，不能攒着）。 */
    private static volatile MinecraftServer SERVER;

    /** 服务器存盘或导入后，这个 chunk 在存档里的状态变了。 */
    static void diskChanged(long key, int status) {
        MinecraftServer srv = SERVER;
        if (srv != null && srv.isSameThread()) {
            DISK.put(key, status);
        } else {
            EVENTS.add(new long[] {key, status, 0});
        }
    }

    /** 服务器存盘时只知道变了，不知道变成什么：下次重新查。 */
    public static void diskUnknown(long key) {
        MinecraftServer srv = SERVER;
        if (srv != null && srv.isSameThread()) {
            DISK.remove(key);
        } else {
            EVENTS.add(new long[] {key, FORGET, 0});
        }
    }

    private static void drainEvents() {
        long[] e;
        while ((e = EVENTS.poll()) != null) {
            int v = (int) e[1];
            if (v == FORGET) {
                DISK.remove(e[0]);
            } else if (e[2] == 0) {
                DISK.put(e[0], v);
            } else if (DISK.get(e[0]) == PENDING) {
                DISK.put(e[0], v);
            }
        }
    }

    /** 在主线程上调用，每秒一次。 */
    void scan() {
        long t0 = System.nanoTime();
        long c0 = THREADS.getCurrentThreadCpuTime();
        ServerLevel level = server.overworld();
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        ChunkMapAccessor access = (ChunkMapAccessor) chunkMap;
        int inner = innerRadius(), outer = inner + lookahead;
        int queued = tasks.count(TaskQueue.State.QUEUED);
        readsLeft = READS_PER_SCAN;
        drainEvents();
        for (ServerPlayer p : level.players()) {
            ChunkPos at = p.chunkPosition();
            // 先按距离筛掉 inner 以内（服务器自己会碰到的范围，不交给 donor），再按离玩家远近排序。
            // 和 Demand.tilesAround / nearestFirst 同样的范围和顺序，只是不新建 TilePos、不装箱。
            TilePos a = TilePos.ofChunk(at.x - outer, at.z - outer), b = TilePos.ofChunk(at.x + outer, at.z + outer);
            int w = b.x() - a.x() + 1, n = w * (b.z() - a.z() + 1);
            int[] tx = new int[n], tz = new int[n], idx = new int[n];
            double[] d2 = new double[n];
            int m = 0;
            for (int x = a.x(); x <= b.x(); x++) {
                for (int z = a.z(); z <= b.z(); z++) {
                    int dx = Math.max(0, Math.max(x * TilePos.SIZE - at.x, at.x - (x * TilePos.SIZE + TilePos.SIZE - 1)));
                    int dz = Math.max(0, Math.max(z * TilePos.SIZE - at.z, at.z - (z * TilePos.SIZE + TilePos.SIZE - 1)));
                    if (Math.max(dx, dz) <= inner) {
                        continue;
                    }
                    double cx = x * TilePos.SIZE + (TilePos.SIZE - 1) / 2.0 - at.x, cz = z * TilePos.SIZE + (TilePos.SIZE - 1) / 2.0 - at.z;
                    tx[m] = x;
                    tz[m] = z;
                    d2[m] = cx * cx + cz * cz;
                    idx[m] = m;
                    m++;
                }
            }
            IntArrays.quickSort(idx, 0, m, (i, j) -> {
                int c = Double.compare(d2[i], d2[j]);
                if (c != 0) {
                    return c;
                }
                c = Integer.compare(tx[i], tx[j]);
                return c != 0 ? c : Integer.compare(tz[i], tz[j]);
            });
            for (int k = 0; k < m && queued < QUEUE_CAP; k++) {
                if (System.nanoTime() - t0 > BUDGET_NANOS) {
                    break; // 这次扫描的时间用完了，剩下的（离玩家较远的）下一秒再查
                }
                int x = tx[idx[k]], z = tz[idx[k]];
                long tk = ChunkPos.asLong(x, z);
                if (serverTiles.contains(tk) || tasks.find(x, z) >= 0) {
                    continue;
                }
                RegionRules.Enqueue e = decide(new TilePos(x, z), tk, chunkMap, access);
                if (e.enqueue() && tasks.enqueue(x, z, e.world())) {
                    queued++;
                }
            }
        }
        if (!toRead.isEmpty()) {
            long[] keys = toRead.toLongArray();
            toRead.clear();
            // 发起读存档放到后台线程：在主线程上调 chunkMap.read 每次要几十微秒（实测 256 次 1～14 ms）。
            // ChunkMap.read → IOWorker.loadAsync 只是往读盘线程的队列里提交任务，哪个线程调都安全。
            Util.ioPool().execute(() -> {
                for (long k : keys) {
                    chunkMap.read(new ChunkPos(k)).thenAccept(tag -> EVENTS.add(new long[] {k,
                            tag.map(t -> ChunkStatus.byName(t.getString("Status")).getIndex()).orElse(ABSENT), 1}));
                }
            });
        }
        long dt = System.nanoTime() - t0;
        scans++;
        totalNanos += dt;
        maxNanos = Math.max(maxNanos, dt);
        if (recent.size() == 64) {
            recent.removeFirst();
        }
        long cpu = THREADS.getCurrentThreadCpuTime() - c0;
        totalCpuNanos += cpu;
        recent.addLast("[" + dt / 1000 + "," + cpu / 1000 + "," + (READS_PER_SCAN - readsLeft) + "]");
    }

    /**
     * 和 {@link Demand#decide} 规则一样（接缝规则 0.3），只是查到服务器的或在内存里的 chunk 就停，不再查剩下的。
     * 存档里状态还不知道的 chunk 发起异步读（每次扫描最多 READS_PER_SCAN 次）、这一轮不入队；
     * 同一个区域里其他不知道的也一起发起，下一轮就齐了。
     * 含有服务器 chunk 的 tile 记进 serverTiles，以后不再查：服务器的 chunk 不会变回空的或 donor 的。
     */
    private RegionRules.Enqueue decide(TilePos tile, long tileKey, ChunkMap chunkMap, ChunkMapAccessor access) {
        List<ChunkInfo> region = new ArrayList<>(36);
        boolean unknown = false;
        for (int x = tile.minChunkX() - 1; x <= tile.maxChunkX() + 1; x++) {
            for (int z = tile.minChunkZ() - 1; z <= tile.maxChunkZ() + 1; z++) {
                long key = ChunkPos.asLong(x, z);
                if (access.mcdonate$getUpdatingChunkIfPresent(key) != null || access.mcdonate$pendingUnloads().containsKey(key)) {
                    return RegionRules.Enqueue.no("在内存里");
                }
                int s = DISK.get(key);
                if (s == UNKNOWN || s == PENDING) {
                    if (s == UNKNOWN && readsLeft > 0) {
                        readsLeft--;
                        DISK.put(key, PENDING);
                        toRead.add(key);
                    }
                    unknown = true;
                    if (readsLeft == 0) {
                        // 这次扫描的读存档名额用完了：这个 tile 这次反正不入队，剩下的不用再查
                        return RegionRules.Enqueue.no("存档状态还在读");
                    }
                } else if (s == ABSENT) {
                    region.add(ChunkInfo.empty());
                } else {
                    String owner = Ownership.owner(key);
                    if (owner == null) {
                        serverTiles.add(tileKey);
                        return RegionRules.Enqueue.no("服务器的 chunk");
                    }
                    region.add(ChunkInfo.owned(owner, s));
                }
            }
        }
        return unknown ? RegionRules.Enqueue.no("存档状态还在读") : RegionRules.enqueue(region);
    }

    /** 离玩家这么多圈以内的 tile 不交给 donor（见类注释）。 */
    int innerRadius() {
        return server.getPlayerList().getViewDistance() + ChunkLevel.RADIUS_AROUND_FULL_CHUNK + INNER_MARGIN;
    }

    String json() {
        long avgMicros = scans == 0 ? 0 : totalNanos / scans / 1000;
        int inner = innerRadius();
        return "{\"count\":" + scans + ",\"avgMicros\":" + avgMicros + ",\"maxMicros\":" + maxNanos / 1000
                + ",\"avgCpuMicros\":" + (scans == 0 ? 0 : totalCpuNanos / scans / 1000)
                + ",\"recent\":[" + String.join(",", recent) + "]"
                + ",\"inner\":" + inner + ",\"outer\":" + (inner + lookahead) + ",\"lookahead\":" + lookahead + "}";
    }
}
