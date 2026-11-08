package mcdonate.coordinator;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.Util;

import java.util.List;

import java.util.ArrayList;

import mcdonate.common.Config;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * 这一轮不入队。服务器存盘（ChunkMapSaveMixin）和导入时更新缓存。
 */
public final class DemandScanner {
    static final int QUEUE_CAP = Config.getInt("demandQueueCap", 256);
    /** inner 在"视距 + 原版占内存的圈数"之外再留几圈。 */
    /** 已知含有服务器 chunk 的 tile（键是 tile 坐标打包成 long），只在主线程上用。 */
    private final LongOpenHashSet serverTiles = new LongOpenHashSet();
    /** 每次扫描最多发起几次读存档：刚开始时玩家周围几千个 chunk 都不知道状态，一次全发会让那一次扫描很慢。 */
    static final int READS_PER_SCAN = Config.getInt("demandReadsPerScan", 256);
    private int readsLeft;
    private final LongArrayList toRead = new LongArrayList();
    static final int INNER_MARGIN = Config.getInt("demandInnerMargin", 1);
    private static final int PENDING = Integer.MIN_VALUE;
    private static final int ABSENT = -1;

    /** 主世界里查过的 chunk：存档里的状态序号，ABSENT 不存在，PENDING 正在查。 */
    private static final Map<Long, Integer> DISK = new ConcurrentHashMap<>();

    private final MinecraftServer server;
    private final TaskQueue tasks;
    private final int lookahead = Config.getInt("demandLookahead", 8);
    long scans;
    long totalNanos;
    long maxNanos;
    long totalCpuNanos;
    private static final java.lang.management.ThreadMXBean THREADS = java.lang.management.ManagementFactory.getThreadMXBean();
    /** 最近 32 次扫描：[墙钟微秒, 主线程 CPU 微秒, 发起的读存档次数]，排查耗时尖峰用。 */
    private final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();

    DemandScanner(MinecraftServer server, TaskQueue tasks) {
        this.server = server;
        this.tasks = tasks;
    }

    /** 服务器存盘或导入后，这个 chunk 在存档里的状态变了。 */
    static void diskChanged(long key, int status) {
        DISK.put(key, status);
    }

    /** 服务器存盘时只知道变了，不知道变成什么：下次重新查。 */
    public static void diskUnknown(long key) {
        DISK.remove(key);
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
        for (ServerPlayer p : level.players()) {
            ChunkPos at = p.chunkPosition();
            // 先按距离筛掉 inner 以内（服务器自己会碰到的范围，不交给 donor），再排序
            List<TilePos> candidates = new ArrayList<>();
            for (TilePos tile : Demand.tilesAround(at.x, at.z, outer)) {
                if (Demand.minChunkDistance(tile, at.x, at.z) > inner) {
                    candidates.add(tile);
                }
            }
            for (TilePos tile : Demand.nearestFirst(candidates, at.x, at.z)) {
                if (queued >= QUEUE_CAP) {
                    break;
                }
                long tk = ChunkPos.asLong(tile.x(), tile.z());
                if (serverTiles.contains(tk) || tasks.find(tile.x(), tile.z()) >= 0) {
                    continue;
                }
                RegionRules.Enqueue e = decide(tile, tk, chunkMap, access);
                if (e.enqueue() && tasks.enqueue(tile.x(), tile.z(), e.world())) {
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
                    chunkMap.read(new ChunkPos(k)).thenAccept(tag -> DISK.replace(k, PENDING,
                            tag.map(t -> ChunkStatus.byName(t.getString("Status")).getIndex()).orElse(ABSENT)));
                }
            });
        }
        long dt = System.nanoTime() - t0;
        scans++;
        totalNanos += dt;
        maxNanos = Math.max(maxNanos, dt);
        if (recent.size() == 32) {
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
                Integer s = DISK.get(key);
                if (s == null || s == PENDING) {
                    if (s == null && readsLeft > 0) {
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
