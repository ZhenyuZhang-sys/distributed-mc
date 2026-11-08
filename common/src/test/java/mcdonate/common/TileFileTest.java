package mcdonate.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import mcdonate.common.TileFile.ExportedChunk;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TileFileTest {
    private static final int DATA_VERSION = 3955;
    private static final long SEED = 424242L;

    @TempDir
    Path dir;

    /** 和 ChunkSerializer.write 一样带 xPos、zPos、Status 的最小 chunk 数据。 */
    private static ExportedChunk chunk(int x, int z, String status) {
        CompoundTag data = new CompoundTag();
        data.putInt("xPos", x);
        data.putInt("zPos", z);
        data.putString("Status", status);
        data.putLong("marker", x * 1000L + z);
        return new ExportedChunk(x, z, status, data);
    }

    /** 一个 tile 加两圈，共 64 个 chunk。 */
    private static TileFile fullTile(TilePos tile) {
        List<ExportedChunk> chunks = new ArrayList<>();
        for (int x = tile.minChunkX() - 2; x <= tile.maxChunkX() + 2; x++) {
            for (int z = tile.minChunkZ() - 2; z <= tile.maxChunkZ() + 2; z++) {
                chunks.add(chunk(x, z, tile.ring(x, z) == 0 ? "minecraft:spawn" : "minecraft:carvers"));
            }
        }
        return new TileFile(DATA_VERSION, SEED, tile, chunks);
    }

    @Test
    void tileOfChunkFloorsNegatives() {
        assertEquals(new TilePos(0, 0), TilePos.ofChunk(0, 3));
        assertEquals(new TilePos(1, 0), TilePos.ofChunk(4, 0));
        assertEquals(new TilePos(-1, -1), TilePos.ofChunk(-1, -4));
        assertEquals(new TilePos(-2, 0), TilePos.ofChunk(-5, 0));
        TilePos t = new TilePos(-2, 3);
        assertEquals(-8, t.minChunkX());
        assertEquals(-5, t.maxChunkX());
        assertEquals(12, t.minChunkZ());
        assertEquals(15, t.maxChunkZ());
    }

    @Test
    void ringIsDistanceFromTile() {
        TilePos t = new TilePos(0, 0); // chunk 0..3
        assertEquals(0, t.ring(0, 0));
        assertEquals(0, t.ring(3, 3));
        assertEquals(1, t.ring(-1, 2));
        assertEquals(1, t.ring(4, 4));
        assertEquals(2, t.ring(-2, -2));
        assertEquals(2, t.ring(5, 0));
        assertEquals(3, t.ring(6, 0));
    }

    @Test
    void writeThenReadIsSame() throws IOException {
        TileFile tf = fullTile(new TilePos(-3, 2));
        Path file = dir.resolve("t.nbt");
        tf.write(file);
        TileFile back = TileFile.read(file, DATA_VERSION, SEED);
        assertEquals(tf.tile(), back.tile());
        assertEquals(64, back.chunks().size());
        assertEquals(tf.chunks(), back.chunks());
    }

    @Test
    void writeLeavesNoTempFile() throws IOException {
        Path file = dir.resolve("t.nbt");
        fullTile(new TilePos(0, 0)).write(file);
        try (var files = Files.list(dir)) {
            assertEquals(List.of(file), files.toList());
        }
    }

    @Test
    void rejectsWrongDataVersion() throws IOException {
        Path file = dir.resolve("t.nbt");
        fullTile(new TilePos(0, 0)).write(file);
        assertThrows(TileFile.InvalidTileFile.class, () -> TileFile.read(file, DATA_VERSION + 1, SEED));
    }

    @Test
    void rejectsWrongSeed() throws IOException {
        Path file = dir.resolve("t.nbt");
        fullTile(new TilePos(0, 0)).write(file);
        assertThrows(TileFile.InvalidTileFile.class, () -> TileFile.read(file, DATA_VERSION, SEED + 1));
    }

    @Test
    void rejectsWrongFormat() throws IOException {
        Path file = dir.resolve("t.nbt");
        fullTile(new TilePos(0, 0)).write(file);
        CompoundTag tag = NbtIo.readCompressed(file, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        tag.putInt("format", 99);
        NbtIo.writeCompressed(tag, file);
        assertThrows(TileFile.InvalidTileFile.class, () -> TileFile.read(file, DATA_VERSION, SEED));
    }

    @Test
    void rejectsChunkOutsideTwoRings() {
        TilePos t = new TilePos(0, 0);
        List<ExportedChunk> chunks = new ArrayList<>(fullTile(t).chunks());
        chunks.add(chunk(6, 0, "minecraft:carvers"));
        assertThrows(TileFile.InvalidTileFile.class, () -> new TileFile(DATA_VERSION, SEED, t, chunks));
    }

    @Test
    void rejectsDuplicateChunk() {
        TilePos t = new TilePos(0, 0);
        List<ExportedChunk> chunks = new ArrayList<>(fullTile(t).chunks());
        chunks.add(chunk(0, 0, "minecraft:spawn"));
        assertThrows(TileFile.InvalidTileFile.class, () -> new TileFile(DATA_VERSION, SEED, t, chunks));
    }

    @Test
    void rejectsDataNotMatchingPosOrStatus() {
        TilePos t = new TilePos(0, 0);
        CompoundTag data = chunk(1, 1, "minecraft:spawn").data();
        assertThrows(TileFile.InvalidTileFile.class,
                () -> new TileFile(DATA_VERSION, SEED, t, List.of(new ExportedChunk(2, 2, "minecraft:spawn", data))));
        assertThrows(TileFile.InvalidTileFile.class,
                () -> new TileFile(DATA_VERSION, SEED, t, List.of(new ExportedChunk(1, 1, "minecraft:full", data))));
    }

    @Test
    void rejectsGarbageFile() throws IOException {
        Path file = dir.resolve("t.nbt");
        Files.writeString(file, "not nbt");
        assertThrows(TileFile.InvalidTileFile.class, () -> TileFile.read(file, DATA_VERSION, SEED));
    }

    @Test
    void readReturnsNothingPartial() throws IOException {
        // 有一个坏 chunk 时整个文件都不收，不会读进一半
        Path file = dir.resolve("t.nbt");
        fullTile(new TilePos(0, 0)).write(file);
        CompoundTag tag = NbtIo.readCompressed(file, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        tag.getList("chunks", 10).getCompound(63).putInt("x", 100);
        NbtIo.writeCompressed(tag, file);
        assertThrows(TileFile.InvalidTileFile.class, () -> TileFile.read(file, DATA_VERSION, SEED));
        assertFalse(Files.exists(dir.resolve("t.nbt.tmp")));
        assertTrue(Files.exists(file));
    }
}
