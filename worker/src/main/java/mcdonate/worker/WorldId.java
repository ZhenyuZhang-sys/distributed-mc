package mcdonate.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * donor 世界的编号（第 6 步）：第一次建世界时生成一个 UUID，存在世界目录的 mcdonate-world-id。
 * worker 重启、存档还在时编号不变；存档清掉重建就变。coordinator 靠它判断"同一个 donor 世界"。
 */
final class WorldId {
    static final String FILE = "mcdonate-world-id";
    private static volatile String cached;

    private WorldId() {}

    static String of(MinecraftServer server) {
        String id = cached;
        if (id == null) {
            try {
                id = loadOrCreate(server.getWorldPath(LevelResource.ROOT));
            } catch (IOException e) {
                throw new IllegalStateException("读写世界编号失败", e);
            }
            cached = id;
        }
        return id;
    }

    static String loadOrCreate(Path worldDir) throws IOException {
        Path f = worldDir.resolve(FILE);
        if (Files.isRegularFile(f)) {
            String s = Files.readString(f).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        String id = UUID.randomUUID().toString();
        Files.createDirectories(worldDir);
        Files.writeString(f, id + "\n");
        return id;
    }
}
