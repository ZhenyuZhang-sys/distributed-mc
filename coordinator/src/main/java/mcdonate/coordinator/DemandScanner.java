package mcdonate.coordinator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mcdonate.common.TilePos;
import mcdonate.coordinator.RegionRules.ChunkInfo;
import mcdonate.coordinator.mixin.ChunkMapAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * 找需求（第 6 步 6.3）：每秒在主线程上看一次主世界的玩家，把周围符合条件的 tile 放进队列。
 *
 * <p>范围是"视距 + margin 圈"。margin 默认 12（系统属性 mcdonate.demandMargin）：服务器自己会加载视距
 * 以内、再往外 1～2 圈也会生成到半成品，按接缝规则这些地方不能交给 donor；只多看 4 圈几乎没有 tile 符合条件。
 *
 * <p>查存档不卡主线程：第一次查某个 chunk 时在存档读写线程上异步读，结果进缓存；还没查到的当"不确定"，
 * 这一轮不入队。服务器存盘（ChunkMapSaveMixin）和导入时更新缓存。
 */
public final class DemandScanner {
    static final int QUEUE_CAP = 256;
    private static final int PENDING = Integer.MIN_VALUE;
    private static final int ABSENT = -1;

    /** 主世界里查过的 chunk：存档里的状态序号，ABSENT 不存在，PENDING 正在查。 */
    private static final Map<Long, Integer> DISK = new ConcurrentHashMap<>();

    private final MinecraftServer server;
    private final TaskQueue tasks;
    private final int margin = Integer.getInteger("mcdonate.demandMargin", 12);
    long scans;
    long totalNanos;
    long maxNanos;

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
        ServerLevel level = server.overworld();
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        ChunkMapAccessor access = (ChunkMapAccessor) chunkMap;
        int radius = server.getPlayerList().getViewDistance() + margin;
        int queued = tasks.count(TaskQueue.State.QUEUED);
        for (ServerPlayer p : level.players()) {
            ChunkPos at = p.chunkPosition();
            for (TilePos tile : Demand.nearestFirst(Demand.tilesAround(at.x, at.z, radius), at.x, at.z)) {
                if (queued >= QUEUE_CAP) {
                    break;
                }
                if (tasks.find(tile.x(), tile.z()) >= 0) {
                    continue;
                }
                RegionRules.Enqueue e = Demand.decide(tile, key -> info(chunkMap, access, key));
                if (e.enqueue() && tasks.enqueue(tile.x(), tile.z(), e.world())) {
                    queued++;
                }
            }
        }
        long dt = System.nanoTime() - t0;
        scans++;
        totalNanos += dt;
        maxNanos = Math.max(maxNanos, dt);
    }

    private ChunkInfo info(ChunkMap chunkMap, ChunkMapAccessor access, long key) {
        if (access.mcdonate$getUpdatingChunkIfPresent(key) != null || access.mcdonate$pendingUnloads().containsKey(key)) {
            return ChunkInfo.loaded();
        }
        Integer s = DISK.get(key);
        if (s == null) {
            DISK.put(key, PENDING);
            chunkMap.read(new ChunkPos(key)).thenAccept(tag -> DISK.replace(key, PENDING,
                    tag.map(t -> ChunkStatus.byName(t.getString("Status")).getIndex()).orElse(ABSENT)));
            return ChunkInfo.loaded(); // 不确定：这一轮不入队
        }
        if (s == PENDING) {
            return ChunkInfo.loaded();
        }
        if (s == ABSENT) {
            return ChunkInfo.empty();
        }
        String owner = Ownership.owner(key);
        return owner == null ? ChunkInfo.server() : ChunkInfo.owned(owner, s);
    }

    String json() {
        long avgMicros = scans == 0 ? 0 : totalNanos / scans / 1000;
        return "{\"count\":" + scans + ",\"avgMicros\":" + avgMicros + ",\"maxMicros\":" + maxNanos / 1000
                + ",\"margin\":" + margin + "}";
    }
}
