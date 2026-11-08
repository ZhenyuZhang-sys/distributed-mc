package mcdonate.coordinator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import mcdonate.common.TileFile;
import mcdonate.common.TileFile.ExportedChunk;
import mcdonate.coordinator.mixin.ChunkMapAccessor;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * 把 worker 导出的 tile 文件写进服务器存档。在主线程上调用。
 *
 * <p>冲突规则（第 3 步先用最简单的）：
 * <ul>
 *   <li>内存里有这个 chunk（已加载、正在生成、等待卸载）：永远不写。
 *   <li>存档里已有：只有导入的状态更靠后才覆盖。
 * </ul>
 * 写入走原版的存档读写线程（ChunkMap.write），之后要加载这个 chunk 的读请求排在写后面，读到的就是新数据。
 */
final class TileImporter {
    /** 文件名只能是交换目录下的一个文件，不能带路径。 */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]+");

    /** 被拒绝（文件名、文件内容不合规）。 */
    static final class Refused extends RuntimeException {
        Refused(String message) {
            super(message);
        }
    }

    record Result(String file, int chunks, int written, int loaded, int exists) {
        String json() {
            return "{\"file\":\"" + file + "\",\"chunks\":" + chunks + ",\"written\":" + written
                    + ",\"skipped\":{\"loaded\":" + loaded + ",\"exists\":" + exists + "}}";
        }
    }

    private TileImporter() {}

    static Path exchangeDir() {
        return Path.of(System.getProperty("mcdonate.exchange", "/exchange"));
    }

    static Path resolve(String name) {
        if (!NAME.matcher(name).matches() || name.startsWith(".")) {
            throw new Refused("refused: bad file name " + name);
        }
        Path dir = exchangeDir().toAbsolutePath().normalize();
        Path file = dir.resolve(name).normalize();
        if (!file.getParent().equals(dir)) {
            throw new Refused("refused: bad file name " + name);
        }
        return file;
    }

    static Result importFile(ServerLevel level, String name) throws IOException {
        Path path = resolve(name);
        if (!Files.isRegularFile(path)) {
            throw new Refused("refused: no such file " + name);
        }
        TileFile file;
        try {
            file = TileFile.read(path, dataVersion(), level.getSeed());
        } catch (TileFile.InvalidTileFile | NoSuchFileException e) {
            throw new Refused("refused: " + e.getMessage());
        }
        return importTile(level, file, name);
    }

    static int dataVersion() {
        return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
    }

    /** 把内存里的 tile 写进存档（第 5 步的 result 走这里）。在主线程上调用。label 只用来写进结果。 */
    /** 第 3 步的旧规则（手动导入、以及测试里故意制造接缝时用）：一个个 chunk 看，存档里更完整的不覆盖。 */
    static Result importTile(ServerLevel level, TileFile file, String label) {
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        ChunkMapAccessor access = (ChunkMapAccessor) chunkMap;
        int written = 0, loaded = 0, exists = 0;
        for (ExportedChunk c : file.chunks()) {
            ChunkPos pos = new ChunkPos(c.x(), c.z());
            if (access.mcdonate$getUpdatingChunkIfPresent(pos.toLong()) != null
                    || access.mcdonate$pendingUnloads().containsKey(pos.toLong())) {
                loaded++;
                continue;
            }
            ChunkStatus incoming = ChunkStatus.byName(c.status());
            // 第 3 步先在主线程上等读取结果；只是调试命令，第 5 步改成异步
            Optional<CompoundTag> old = chunkMap.read(pos).join();
            if (old.isPresent() && !ChunkStatus.byName(old.get().getString("Status")).isBefore(incoming)) {
                exists++;
                continue;
            }
            chunkMap.write(pos, c.data());
            access.mcdonate$markPosition(pos, incoming.getChunkType());
            Ownership.record(pos.toLong(), file.worldId());
            written++;
        }
        return new Result(label, file.chunks().size(), written, loaded, exists);
    }

    /** 严格导入的结果：整个丢弃（带原因），或者写了几个、覆盖了几个、跳过了几个。 */
    record StrictResult(boolean accepted, String reason, int written, int overwritten, int skipped) {}

    /**
     * 第 6 步的严格导入（docs/step6-plan.md 0.3）：先查整个区域，碰到别的 donor 世界、服务器的、
     * 内存里的 chunk 就整个丢弃；否则空的写入，同一个世界的按状态覆盖或跳过。在主线程上调用。
     */
    static StrictResult importStrict(ServerLevel level, TileFile file) {
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        ChunkMapAccessor access = (ChunkMapAccessor) chunkMap;
        Map<Long, RegionRules.ChunkInfo> current = new HashMap<>();
        Map<Long, Integer> incoming = new HashMap<>();
        Map<Long, ExportedChunk> byPos = new HashMap<>();
        for (ExportedChunk c : file.chunks()) {
            long key = ChunkPos.asLong(c.x(), c.z());
            current.put(key, chunkInfo(chunkMap, access, key));
            incoming.put(key, ChunkStatus.byName(c.status()).getIndex());
            byPos.put(key, c);
        }
        RegionRules.ImportPlan<Long> plan = RegionRules.importPlan(current, file.worldId(), incoming);
        if (!plan.accepted()) {
            return new StrictResult(false, plan.reason(), 0, 0, 0);
        }
        int written = 0, overwritten = 0, skipped = 0;
        for (Map.Entry<Long, RegionRules.Action> e : plan.actions().entrySet()) {
            ExportedChunk c = byPos.get(e.getKey());
            ChunkPos pos = new ChunkPos(c.x(), c.z());
            switch (e.getValue()) {
                case SKIP -> {
                    skipped++;
                    continue;
                }
                case WRITE -> written++;
                case OVERWRITE -> overwritten++;
            }
            chunkMap.write(pos, c.data());
            access.mcdonate$markPosition(pos, ChunkStatus.byName(c.status()).getChunkType());
            Ownership.record(e.getKey(), file.worldId());
        }
        return new StrictResult(true, "", written, overwritten, skipped);
    }

    /** 一个 chunk 现在的情况：在内存里、空、归属某个 donor 世界、服务器的。在主线程上调用。 */
    static RegionRules.ChunkInfo chunkInfo(ChunkMap chunkMap, ChunkMapAccessor access, long key) {
        if (access.mcdonate$getUpdatingChunkIfPresent(key) != null || access.mcdonate$pendingUnloads().containsKey(key)) {
            return RegionRules.ChunkInfo.loaded();
        }
        // 主线程上等读取结果；第 6.3 步找需求时换成只看 region 文件头
        Optional<CompoundTag> old = chunkMap.read(new ChunkPos(key)).join();
        if (old.isEmpty()) {
            return RegionRules.ChunkInfo.empty();
        }
        String owner = Ownership.owner(key);
        if (owner == null) {
            return RegionRules.ChunkInfo.server();
        }
        return RegionRules.ChunkInfo.owned(owner, ChunkStatus.byName(old.get().getString("Status")).getIndex());
    }

    /** 存档里这个 chunk 的状态，没有就是 "none"。不加载 chunk。 */
    static String peek(ServerLevel level, int x, int z) {
        return level.getChunkSource().chunkMap.read(new ChunkPos(x, z)).join()
                .map(tag -> tag.getString("Status"))
                .orElse("none");
    }

}
