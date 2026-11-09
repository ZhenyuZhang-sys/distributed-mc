package mcdonate.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mcdonate.common.TileFile;
import mcdonate.common.TileFile.ExportedChunk;
import mcdonate.common.TilePos;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;

/**
 * 把一个 tile 生成到 initialize_light，连同紧挨着的一圈导出成 {@link TileFile}。
 *
 * <p>initialize_light 时地形、表面、洞穴、地物都已做完，服务器只剩光照、生怪和 full（实测约占生成 CPU 的 2%）。
 * 停在这里是因为原版的票等级只能让 chunk 停在 initialize_light，再往上就直接做成 full 了。
 * tile 放地物时会写进紧挨着的一圈（这一圈停在 carvers），所以要一起导出。
 *
 * <p>全部在主线程上做：先把 tile 的 16 个 chunk 要到 initialize_light，然后立刻序列化。
 * 有成品 chunk（玩家附近、出生点）就拒绝：成品的生物存在别处，导出会丢。
 */
final class TileExporter {
    /** 导出被拒绝。 */
    static final class Refused extends RuntimeException {
        Refused(String message) {
            super(message);
        }
    }

    record Result(TilePos tile, Path file, int chunks, List<Map<String, Integer>> rings, long millis) {
        String json() {
            StringBuilder sb = new StringBuilder("{\"tile\":[").append(tile.x()).append(',').append(tile.z())
                    .append("],\"file\":\"").append(file.getFileName()).append("\",\"chunks\":").append(chunks)
                    .append(",\"ms\":").append(millis).append(",\"genMs\":").append(LAST_SPLIT[0])
                    .append(",\"serializeMs\":").append(LAST_SPLIT[1]).append(",\"quietMs\":").append(LAST_SPLIT[2])
                    .append(",\"rings\":[");
            for (int i = 0; i < rings.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('{');
                int j = 0;
                for (var e : rings.get(i).entrySet()) {
                    if (j++ > 0) {
                        sb.append(',');
                    }
                    sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
                }
                sb.append('}');
            }
            return sb.append("]}").toString();
        }
    }

    private TileExporter() {}

    static Path exchangeDir() {
        return Path.of(System.getProperty("mcdonate.exchange", "/exchange"));
    }

    /** 生成并写进交换目录（第 3 步的 /donor export）。 */
    static Result export(ServerLevel level, TilePos tile) throws IOException {
        return export(level, tile, false);
    }

    /** serial：按原来的办法一个一个同步要（对照用，/donor export ... serial）。 */
    static Result export(ServerLevel level, TilePos tile, boolean serial) throws IOException {
        long t0 = System.nanoTime();
        Generated g = generate(level, tile, serial);
        Path dir = exchangeDir();
        Files.createDirectories(dir);
        Path out = dir.resolve("tile_" + tile.x() + "_" + tile.z() + ".nbt");
        g.file().write(out);
        return new Result(tile, out, g.file().chunks().size(), g.rings(), (System.nanoTime() - t0) / 1_000_000);
    }

    record Generated(TileFile file, List<Map<String, Integer>> rings) {}

    /** 生成 tile 并序列化成内存里的 TileFile，不写文件（第 5 步的 result 用）。在主线程上调用。 */
    static Generated generate(ServerLevel level, TilePos tile) {
        return generate(level, tile, false);
    }

    /** tile 的 chunk 上自己的票：不过期，导出完由 release 去掉（11-09：几个 tile 同时在生成线程池里做）。 */
    static final net.minecraft.server.level.TicketType<net.minecraft.world.level.ChunkPos> TILE_TICKET =
            net.minecraft.server.level.TicketType.create("mcdonate_tile", java.util.Comparator.comparingLong(net.minecraft.world.level.ChunkPos::toLong));
    private static final int TILE_TICKET_LEVEL = net.minecraft.server.level.ChunkLevel.byStatus(ChunkStatus.INITIALIZE_LIGHT);
    /** 加了票、还没去掉的 tile（只在主线程上读写）。 */
    private static final java.util.Set<TilePos> PREFETCHED = new java.util.HashSet<>();
    /**
     * 排上过生成、生成还可能在跑的 tile → 它的 16 个 chunk 的 future（只在主线程上读写）。去票（任务被撤、导出完）不会让原版
     * 已经建好的生成任务停下，所以撤掉的 tile 也留在这里，直到 future 都完成（11-09 kexu 转弯 10cadc8 还撞：转弯时撤得多，
     * 被撤的相邻 tile 去了票、不在 PREFETCHED 里，导出前没等它，它的地物照样写进来）。
     */
    private static final java.util.Map<TilePos, List<java.util.concurrent.CompletableFuture<?>>> GENERATING = new java.util.HashMap<>();

    /**
     * 把 tile 的 16 个 chunk 排上生成、加票留住，不等（11-09）。worker 收到任务就调（在主线程上，很快），
     * 前一个 tile 还在做的时候这个就已经在生成线程池里了；轮到它导出时多半已经做完。重复调没关系。
     */
    static void prefetch(ServerLevel level, TilePos tile) {
        if (!PREFETCHED.add(tile)) {
            return;
        }
        ServerChunkCache cache = level.getChunkSource();
        net.minecraft.server.level.DistanceManager dm = ((mcdonate.worker.mixin.ServerChunkCacheAccessor) cache).mcdonate$distanceManager();
        mcdonate.worker.mixin.ServerChunkCacheInvoker inv = (mcdonate.worker.mixin.ServerChunkCacheInvoker) cache;
        for (int x = tile.minChunkX(); x <= tile.maxChunkX(); x++) {
            for (int z = tile.minChunkZ(); z <= tile.maxChunkZ(); z++) {
                net.minecraft.world.level.ChunkPos pos = new net.minecraft.world.level.ChunkPos(x, z);
                dm.addTicket(TILE_TICKET, pos, TILE_TICKET_LEVEL, pos);
            }
        }
        List<java.util.concurrent.CompletableFuture<?>> fs = new ArrayList<>();
        for (int x = tile.minChunkX(); x <= tile.maxChunkX(); x++) {
            for (int z = tile.minChunkZ(); z <= tile.maxChunkZ(); z++) {
                fs.add(inv.mcdonate$schedule(x, z, ChunkStatus.INITIALIZE_LIGHT, true));
            }
        }
        GENERATING.put(tile, fs);
    }

    /** 去掉 prefetch 加的票（导出完、任务被撤）。在主线程上调。 */
    static void release(ServerLevel level, TilePos tile) {
        if (!PREFETCHED.remove(tile)) {
            return;
        }
        net.minecraft.server.level.DistanceManager dm =
                ((mcdonate.worker.mixin.ServerChunkCacheAccessor) level.getChunkSource()).mcdonate$distanceManager();
        for (int x = tile.minChunkX(); x <= tile.maxChunkX(); x++) {
            for (int z = tile.minChunkZ(); z <= tile.maxChunkZ(); z++) {
                net.minecraft.world.level.ChunkPos pos = new net.minecraft.world.level.ChunkPos(x, z);
                dm.removeTicket(TILE_TICKET, pos, TILE_TICKET_LEVEL, pos);
            }
        }
    }

    /** 已经排上生成的、和 tile 紧挨着（含对角）的 tile，它们的 16 个 chunk 都到了 initialize_light。在主线程上调。 */
    static void waitAdjacentDone(TilePos tile, ServerChunkCache cache, long timeoutMs) {
        // 排上过的（包括已经被撤、去了票的）都算：它们的生成任务原版不会因为去票就停（见 GENERATING）
        GENERATING.values().removeIf(fs -> fs.stream().allMatch(java.util.concurrent.CompletableFuture::isDone));
        List<java.util.concurrent.CompletableFuture<?>> futures = new ArrayList<>();
        for (var e : GENERATING.entrySet()) {
            TilePos o = e.getKey();
            if (o.equals(tile) || Math.max(Math.abs(o.x() - tile.x()), Math.abs(o.z() - tile.z())) > 1) {
                continue;
            }
            futures.addAll(e.getValue());
        }
        if (futures.isEmpty()) {
            return;
        }
        Waits.until(() -> futures.stream().allMatch(java.util.concurrent.CompletableFuture::isDone), cache::pollTask, timeoutMs,
                System::currentTimeMillis, "tile " + tile + " 旁边已经排上的 tile 做完",
                net.minecraft.server.MinecraftServer::throwIfFatalException);
    }

    /** 区域（tile ± 外圈）里每个 chunk 的生成引用数都是 0（没有生成任务在用它）。在主线程上调。 */
    static void waitNoGeneration(ServerLevel level, TilePos tile, ServerChunkCache cache, long timeoutMs) {
        mcdonate.worker.mixin.ChunkMapInvoker map = (mcdonate.worker.mixin.ChunkMapInvoker) cache.chunkMap;
        int r = TileFile.MAX_RING;
        Waits.until(() -> {
            for (int x = tile.minChunkX() - r; x <= tile.maxChunkX() + r; x++) {
                for (int z = tile.minChunkZ() - r; z <= tile.maxChunkZ() + r; z++) {
                    net.minecraft.server.level.ChunkHolder h = map.mcdonate$holder(net.minecraft.world.level.ChunkPos.asLong(x, z));
                    if (h != null && h.getGenerationRefCount() > 0) {
                        return false;
                    }
                }
            }
            return true;
        }, cache::pollTask, timeoutMs, System::currentTimeMillis, "tile " + tile + " 区域里的生成任务结束",
                net.minecraft.server.MinecraftServer::throwIfFatalException);
    }

    /**
     * holder 里已经做出来的、状态最靠后的 chunk；纯查找，不加票、不排任务。
     * 原版的 getLatestChunk 只看"开始做的状态"和它的上一层，开始做了但还没做完两层以上、或者从没开始（只有票）时返回 null，
     * 这里退回从 full 往下一层层找。
     */
    static ChunkAccess latest(net.minecraft.server.level.ChunkHolder holder) {
        if (holder == null) {
            return null;
        }
        ChunkAccess chunk = holder.getLatestChunk();
        if (chunk != null) {
            return chunk;
        }
        for (ChunkStatus s = ChunkStatus.FULL; ; s = s.getParent()) {
            chunk = holder.getChunkIfPresentUnchecked(s);
            if (chunk != null || s == ChunkStatus.EMPTY) {
                return chunk;
            }
        }
    }

    /** 测试用：下一个 tile 多等一个永远不会完成的 chunk（/donor stallnext）。 */
    static volatile boolean STALL_NEXT;

    /** 这个 worker 最近导出一个 tile 的平均毫秒数（指数平均；没有样本是 -1）。只在主线程上读写。 */
    static long avgMs = -1;

    private static void recordMs(long ms) {
        avgMs = avgMs < 0 ? ms : (avgMs * 4 + ms) / 5;
    }

    /** 上一次 generate 的两段（毫秒）：等生成、序列化（/donor export 的 JSON 里报，排查用）。只在主线程上读写。 */
    static final long[] LAST_SPLIT = new long[3];

    static Generated generate(ServerLevel level, TilePos tile, boolean serial) {
        long started = System.nanoTime();
        ServerChunkCache cache = level.getChunkSource();

        // 11-09：先把 16 个都排上（加票、排生成任务，不等），世界生成线程池同时做；原来一个一个同步要，
        // 主线程等一个做完才要下一个，worker 实际只用约 1 个核（用户真机：一次 1 个 943 ms，一次 4 个 3981 ms）。
        // 这里的票是原版 getChunk 用的那种（UNKNOWN，过一个 tick 失效）；下面等的时候还在这一次任务里，tick 不会走。
        if (!serial) {
            prefetch(level, tile); // 收到任务时多半已经排上了，这里补一次（重复调没关系）
        }
        try {
            return generateNow(level, tile, cache, started, serial);
        } finally {
            release(level, tile);
        }
    }

    private static Generated generateNow(ServerLevel level, TilePos tile, ServerChunkCache cache, long started, boolean serial) {
        // tile 的 16 个 chunk 要到 initialize_light（排上了的就是等它们做完）。已经更靠后的也会直接返回，下面统一检查。
        // 11-09：不用原版的 getChunk(..., true) 等（它没有超时，生成被异常打断时主线程永远等下去）：自己一边处理主线程任务一边等，超时抛异常
        long timeout = Waits.tileTimeoutMs(avgMs);
        if (serial) {
            // 对照（/donor export ... serial）：按原来的办法一个一个同步要（codex-audit A6：原来这里也一起排上了，对照失效）
            for (int x = tile.minChunkX(); x <= tile.maxChunkX(); x++) {
                for (int z = tile.minChunkZ(); z <= tile.maxChunkZ(); z++) {
                    cache.getChunk(x, z, ChunkStatus.INITIALIZE_LIGHT, true);
                }
            }
        }
        mcdonate.worker.mixin.ServerChunkCacheInvoker inv = (mcdonate.worker.mixin.ServerChunkCacheInvoker) cache;
        List<java.util.concurrent.CompletableFuture<net.minecraft.server.level.ChunkResult<ChunkAccess>>> futures = new ArrayList<>();
        for (int x = tile.minChunkX(); x <= tile.maxChunkX(); x++) {
            for (int z = tile.minChunkZ(); z <= tile.maxChunkZ(); z++) {
                futures.add(inv.mcdonate$schedule(x, z, ChunkStatus.INITIALIZE_LIGHT, true));
            }
        }
        // 11-09（kexu k2 完整 step10，任务 47）：这个 tile 以前做过、存了盘时，16 个 chunk 直接从盘里读，原版不碰邻居，
        // 外圈只有 holder、没加载，下面的纯查找拿不到。外圈也要到 empty（在内存里就马上完成；不在就从盘里读，盘里也没有是空的）
        int ring = TileFile.MAX_RING;
        for (int x = tile.minChunkX() - ring; x <= tile.maxChunkX() + ring; x++) {
            for (int z = tile.minChunkZ() - ring; z <= tile.maxChunkZ() + ring; z++) {
                if (tile.ring(x, z) > 0) {
                    futures.add(inv.mcdonate$schedule(x, z, ChunkStatus.EMPTY, true));
                }
            }
        }
        if (STALL_NEXT) {
            // 测试钩子（/donor stallnext）：多等一个永远不会完成的 chunk，模拟生成被异常打断（用户真机 11-09）
            STALL_NEXT = false;
            futures.add(new java.util.concurrent.CompletableFuture<>());
        }
        // 两段等待共用一个截止时间（A5：原来各拿一整份，慢 donor 可能等到 400 秒），每轮查原版的致命错误
        long deadline = System.currentTimeMillis() + timeout;
        Waits.until(() -> futures.stream().allMatch(java.util.concurrent.CompletableFuture::isDone), cache::pollTask, timeout,
                System::currentTimeMillis, "tile " + tile + " 的 16 个 chunk", net.minecraft.server.MinecraftServer::throwIfFatalException);
        for (var f : futures) {
            net.minecraft.server.level.ChunkResult<ChunkAccess> res = f.getNow(null);
            if (res == null || !res.isSuccess()) {
                throw new IllegalStateException("tile " + tile + " 的 chunk 没生成出来：" + (res == null ? "?" : res.getError()));
            }
        }

        // 11-09（用户真机，9008deb："Accessing PalettedContainer from multiple threads"）：几个 tile 同时生成时，
        // 相邻 tile 的生成任务可能还在写这个 tile 外圈的 chunk（地物写进邻居），主线程这时序列化就撞上。
        // 序列化之前等整个区域（tile ± 外圈）里每个 chunk 都没有进行中的生成任务；等的时候照常处理 chunk 的主线程任务，不会卡死。
        long quietFrom = System.nanoTime();
        // 11-09（kexu k2 转弯，3132230 又出 PalettedContainer 多线程访问，异常在生成线程上）：原版的生成任务是一层层推进、
        // 一层层占邻居的（不一定在主线程上），"引用数为 0"检查完以后，相邻 tile 的地物还可能开始往这个区域里写。
        // 能写进这个区域（tile ± 1 圈）的只有紧挨着的 tile（地物只写进相邻一圈）：先等已经排上的相邻 tile 全部做完，再看引用数
        waitAdjacentDone(tile, cache, Math.max(1, deadline - System.currentTimeMillis()));
        waitNoGeneration(level, tile, cache, Math.max(1, deadline - System.currentTimeMillis()));
        long waited = System.nanoTime();
        int r = TileFile.MAX_RING;
        List<ExportedChunk> chunks = new ArrayList<>();
        List<Map<String, Integer>> rings = new ArrayList<>();
        for (int i = 0; i <= r; i++) {
            rings.add(new LinkedHashMap<>());
        }
        for (int x = tile.minChunkX() - r; x <= tile.maxChunkX() + r; x++) {
            for (int z = tile.minChunkZ() - r; z <= tile.maxChunkZ() + r; z++) {
                // 纯查找（holder 里最新的 chunk）：不加票、不触发生成，也不会在主线程上 poll 别的任务（调度 11-09：
                // getChunk 的 managedBlock 万一 poll 到排着的任务、建了新的生成任务，序列化时就会撞上）
                net.minecraft.server.level.ChunkHolder holder =
                        ((mcdonate.worker.mixin.ChunkMapInvoker) cache.chunkMap).mcdonate$holder(net.minecraft.world.level.ChunkPos.asLong(x, z));
                ChunkAccess chunk = latest(holder);
                if (chunk == null) {
                    throw new IllegalStateException("chunk (" + x + ", " + z + ") 不在内存里，生成依赖和预期不符");
                }
                ChunkStatus status = chunk.getPersistedStatus();
                if (status == ChunkStatus.FULL) {
                    throw new Refused("refused: chunk (" + x + ", " + z + ") is already full (player or spawn nearby)");
                }
                String name = BuiltInRegistries.CHUNK_STATUS.getKey(status).toString();
                // 深拷贝（codex-audit 第九节 P1）：ChunkSerializer.write 的高度图、光照数组和活 chunk 共用，
                // 结果在发送线程上编码时相邻 tile 可能已经接着生成、改了它们
                chunks.add(new ExportedChunk(x, z, name, NbtSnapshot.of(ChunkSerializer.write(level, chunk))));
                rings.get(tile.ring(x, z)).merge(name, 1, Integer::sum);
            }
        }

        LAST_SPLIT[0] = (quietFrom - started) / 1_000_000;
        LAST_SPLIT[2] = (waited - quietFrom) / 1_000_000;
        recordMs((System.nanoTime() - started) / 1_000_000);
        LAST_SPLIT[1] = (System.nanoTime() - waited) / 1_000_000;
        TileFile file = new TileFile(SharedConstants.getCurrentVersion().getDataVersion().getVersion(),
                level.getSeed(), WorldId.of(level.getServer()), tile, chunks);
        return new Generated(file, rings);
    }
}
