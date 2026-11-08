package mcdonate.coordinator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
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
            file = TileFile.read(path, SharedConstants.getCurrentVersion().getDataVersion().getVersion(), level.getSeed());
        } catch (TileFile.InvalidTileFile | NoSuchFileException e) {
            throw new Refused("refused: " + e.getMessage());
        }

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
            written++;
        }
        return new Result(name, file.chunks().size(), written, loaded, exists);
    }

    /** 存档里这个 chunk 的状态，没有就是 "none"。不加载 chunk。 */
    static String peek(ServerLevel level, int x, int z) {
        return level.getChunkSource().chunkMap.read(new ChunkPos(x, z)).join()
                .map(tag -> tag.getString("Status"))
                .orElse("none");
    }

}
