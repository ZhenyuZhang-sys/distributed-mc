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
                    .append(",\"serializeMs\":").append(LAST_SPLIT[1]).append(",\"rings\":[");
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
        for (int x = tile.minChunkX(); x <= tile.maxChunkX(); x++) {
            for (int z = tile.minChunkZ(); z <= tile.maxChunkZ(); z++) {
                inv.mcdonate$schedule(x, z, ChunkStatus.INITIALIZE_LIGHT, true);
            }
        }
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

    /** 等区域里的 chunk 都没有进行中的生成任务的最长时间；超过就让这个任务失败（重新排队），不冒险序列化。 */
    static final long QUIET_TIMEOUT_MS = 60_000;

    /** 区域（tile ± 外圈）里每个 chunk 的生成引用数都是 0（没有生成任务在用它）。在主线程上调。 */
    static void waitNoGeneration(ServerLevel level, TilePos tile, ServerChunkCache cache) {
        mcdonate.worker.mixin.ChunkMapInvoker map = (mcdonate.worker.mixin.ChunkMapInvoker) cache.chunkMap;
        int r = TileFile.MAX_RING;
        long deadline = System.currentTimeMillis() + QUIET_TIMEOUT_MS;
        while (true) {
            boolean busy = false;
            for (int x = tile.minChunkX() - r; x <= tile.maxChunkX() + r && !busy; x++) {
                for (int z = tile.minChunkZ() - r; z <= tile.maxChunkZ() + r && !busy; z++) {
                    net.minecraft.server.level.ChunkHolder h = map.mcdonate$holder(net.minecraft.world.level.ChunkPos.asLong(x, z));
                    busy = h != null && h.getGenerationRefCount() > 0;
                }
            }
            if (!busy) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("tile " + tile + " 的区域 " + QUIET_TIMEOUT_MS / 1000 + " 秒内一直有生成任务在用，不导出");
            }
            if (!cache.pollTask()) {
                java.util.concurrent.locks.LockSupport.parkNanos(200_000);
            }
        }
    }

    /** 上一次 generate 的两段（毫秒）：等生成、序列化（/donor export 的 JSON 里报，排查用）。只在主线程上读写。 */
    static final long[] LAST_SPLIT = new long[2];

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
            return generateNow(level, tile, cache, started);
        } finally {
            release(level, tile);
        }
    }

    private static Generated generateNow(ServerLevel level, TilePos tile, ServerChunkCache cache, long started) {
        // tile 的 16 个 chunk 要到 initialize_light（排上了的就是等它们做完）。已经更靠后的也会直接返回，下面统一检查
        for (int x = tile.minChunkX(); x <= tile.maxChunkX(); x++) {
            for (int z = tile.minChunkZ(); z <= tile.maxChunkZ(); z++) {
                cache.getChunk(x, z, ChunkStatus.INITIALIZE_LIGHT, true);
            }
        }

        // 11-09（用户真机，9008deb："Accessing PalettedContainer from multiple threads"）：几个 tile 同时生成时，
        // 相邻 tile 的生成任务可能还在写这个 tile 外圈的 chunk（地物写进邻居），主线程这时序列化就撞上。
        // 序列化之前等整个区域（tile ± 外圈）里每个 chunk 都没有进行中的生成任务；等的时候照常处理 chunk 的主线程任务，不会卡死。
        waitNoGeneration(level, tile, cache);
        long waited = System.nanoTime();
        int r = TileFile.MAX_RING;
        List<ExportedChunk> chunks = new ArrayList<>();
        List<Map<String, Integer>> rings = new ArrayList<>();
        for (int i = 0; i <= r; i++) {
            rings.add(new LinkedHashMap<>());
        }
        for (int x = tile.minChunkX() - r; x <= tile.maxChunkX() + r; x++) {
            for (int z = tile.minChunkZ() - r; z <= tile.maxChunkZ() + r; z++) {
                // 不加票、不触发生成，只拿现成的
                ChunkAccess chunk = cache.getChunk(x, z, ChunkStatus.EMPTY, false);
                if (chunk == null) {
                    throw new IllegalStateException("chunk (" + x + ", " + z + ") 不在内存里，生成依赖和预期不符");
                }
                ChunkStatus status = chunk.getPersistedStatus();
                if (status == ChunkStatus.FULL) {
                    throw new Refused("refused: chunk (" + x + ", " + z + ") is already full (player or spawn nearby)");
                }
                String name = BuiltInRegistries.CHUNK_STATUS.getKey(status).toString();
                chunks.add(new ExportedChunk(x, z, name, ChunkSerializer.write(level, chunk)));
                rings.get(tile.ring(x, z)).merge(name, 1, Integer::sum);
            }
        }

        LAST_SPLIT[0] = (waited - started) / 1_000_000;
        LAST_SPLIT[1] = (System.nanoTime() - waited) / 1_000_000;
        TileFile file = new TileFile(SharedConstants.getCurrentVersion().getDataVersion().getVersion(),
                level.getSeed(), WorldId.of(level.getServer()), tile, chunks);
        return new Generated(file, rings);
    }
}
