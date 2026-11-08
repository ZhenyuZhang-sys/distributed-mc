package mcdonate.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/**
 * 一个 tile 的导出文件：tile 本身和外面一圈的 chunk，压缩 NBT。
 *
 * <pre>{format: 1, dataVersion, seed, tile: [tx, tz], chunks: [{x, z, status, data}]}</pre>
 *
 * data 是原版 ChunkSerializer.write 的结果。构造和读取时都会检查，有任何问题整个文件都不收。
 */
public record TileFile(int dataVersion, long seed, TilePos tile, List<ExportedChunk> chunks) {
    public static final int FORMAT = 1;
    /** 最多导出到第几圈：tile 放地物时会写进紧挨着的一圈。 */
    public static final int MAX_RING = 1;
    /** 读文件时解压后最多 256 MiB，防止坏文件撑爆内存。 */
    private static final long READ_QUOTA = 256L * 1024 * 1024;

    public record ExportedChunk(int x, int z, String status, CompoundTag data) {}

    /** 文件不合规。 */
    public static final class InvalidTileFile extends RuntimeException {
        public InvalidTileFile(String message) {
            super(message);
        }

        public InvalidTileFile(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public TileFile {
        chunks = List.copyOf(chunks);
        Set<Long> seen = new HashSet<>();
        for (ExportedChunk c : chunks) {
            if (tile.ring(c.x(), c.z()) > MAX_RING) {
                throw new InvalidTileFile("chunk (" + c.x() + ", " + c.z() + ") 不在 tile " + tile + " 和外面一圈之内");
            }
            if (!seen.add(((long) c.x() << 32) ^ (c.z() & 0xffffffffL))) {
                throw new InvalidTileFile("chunk (" + c.x() + ", " + c.z() + ") 重复");
            }
            CompoundTag d = c.data();
            if (d.getInt("xPos") != c.x() || d.getInt("zPos") != c.z()) {
                throw new InvalidTileFile("chunk (" + c.x() + ", " + c.z() + ") 的数据坐标是 ("
                        + d.getInt("xPos") + ", " + d.getInt("zPos") + ")");
            }
            if (!d.getString("Status").equals(c.status())) {
                throw new InvalidTileFile("chunk (" + c.x() + ", " + c.z() + ") 的状态对不上："
                        + c.status() + " / " + d.getString("Status"));
            }
        }
    }

    /** 先写临时文件再改名，读的一方不会读到半个文件。 */
    public void write(Path file) throws IOException {
        CompoundTag root = new CompoundTag();
        root.putInt("format", FORMAT);
        root.putInt("dataVersion", dataVersion);
        root.putLong("seed", seed);
        root.putIntArray("tile", new int[] {tile.x(), tile.z()});
        ListTag list = new ListTag();
        for (ExportedChunk c : chunks) {
            CompoundTag t = new CompoundTag();
            t.putInt("x", c.x());
            t.putInt("z", c.z());
            t.putString("status", c.status());
            t.put("data", c.data());
            list.add(t);
        }
        root.put("chunks", list);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        NbtIo.writeCompressed(root, tmp);
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 读文件并检查。dataVersion 和 seed 要和本服务器一致。 */
    public static TileFile read(Path file, int expectedDataVersion, long expectedSeed) throws IOException {
        CompoundTag root;
        try {
            root = NbtIo.readCompressed(file, NbtAccounter.create(READ_QUOTA));
        } catch (IOException | RuntimeException e) {
            if (e instanceof java.nio.file.NoSuchFileException) {
                throw (IOException) e;
            }
            throw new InvalidTileFile("读不出 NBT：" + e.getMessage(), e);
        }
        if (root.getInt("format") != FORMAT) {
            throw new InvalidTileFile("格式版本是 " + root.getInt("format") + "，只认 " + FORMAT);
        }
        int dv = root.getInt("dataVersion");
        if (dv != expectedDataVersion) {
            throw new InvalidTileFile("数据版本是 " + dv + "，本服务器是 " + expectedDataVersion);
        }
        long seed = root.getLong("seed");
        if (seed != expectedSeed) {
            throw new InvalidTileFile("seed 是 " + seed + "，本服务器是 " + expectedSeed);
        }
        int[] t = root.getIntArray("tile");
        if (t.length != 2) {
            throw new InvalidTileFile("tile 坐标不对");
        }
        List<ExportedChunk> chunks = new ArrayList<>();
        for (Tag tag : root.getList("chunks", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) tag;
            chunks.add(new ExportedChunk(c.getInt("x"), c.getInt("z"), c.getString("status"), c.getCompound("data")));
        }
        return new TileFile(dv, seed, new TilePos(t[0], t[1]), chunks);
    }
}
