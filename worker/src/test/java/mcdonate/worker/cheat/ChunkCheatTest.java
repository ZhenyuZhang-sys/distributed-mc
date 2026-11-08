package mcdonate.worker.cheat;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mcdonate.worker.cheat.ChunkCheat.Change;
import mcdonate.worker.cheat.CheatConfig.Mode;
import mcdonate.worker.cheat.CheatConfig.Pool;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

/** 第 8 步 8.1：作弊开关。真实数据是第 1 步快照里的一个 mountain chunk（1328, 3704），有钻石矿、煤矿和草方块。 */
class ChunkCheatTest {

    static CompoundTag real() throws IOException {
        try (InputStream in = ChunkCheatTest.class.getResourceAsStream("/chunks/mountain-1328-3704.nbt")) {
            return NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }
    }

    /** 合成 chunk：y<0 深板岩，0～59 石头，60～62 泥土，63 草方块，以上空气；section -4～19。 */
    static CompoundTag synthetic(int cx, int cz) {
        CompoundTag chunk = new CompoundTag();
        chunk.putInt("xPos", cx);
        chunk.putInt("zPos", cz);
        ListTag sections = new ListTag();
        for (int sy = -4; sy < 20; sy++) {
            CompoundTag air = new CompoundTag();
            ListTag palette = new ListTag();
            palette.add(SectionBlocks.block("minecraft:air"));
            air.put("palette", palette);
            SectionBlocks s = SectionBlocks.decode(air);
            for (int y = 0; y < 16; y++) {
                int wy = sy * 16 + y;
                String n = wy < 0 ? "minecraft:deepslate" : wy < 60 ? "minecraft:stone" : wy < 63 ? "minecraft:dirt" : wy == 63 ? "minecraft:grass_block" : "minecraft:air";
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) s.set(x, y, z, SectionBlocks.block(n));
            }
            CompoundTag section = new CompoundTag();
            section.putByte("Y", (byte) sy);
            section.put("block_states", s.encode());
            sections.add(section);
        }
        chunk.put("sections", sections);
        chunk.put("block_entities", new ListTag());
        return chunk;
    }

    static CheatConfig cfg(Mode mode, long seed) {
        return new CheatConfig(mode, Mode.NONE, seed, 20, "diamond", -64, 16, 64, Pool.ANY, 0);
    }

    /** 每个位置的方块状态（名字 + 属性），世界坐标 "x,y,z"。 */
    static Map<String, String> cells(CompoundTag chunk) {
        ChunkBlocks c = new ChunkBlocks(chunk);
        Map<String, String> out = new HashMap<>();
        for (int y = c.minY(); y < c.maxY(); y++)
            if (c.has(y))
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) out.put((c.cx * 16 + x) + "," + y + "," + (c.cz * 16 + z), SectionBlocks.key(c.state(x, y, z)));
        return out;
    }

    static byte[] bytes(CompoundTag tag) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, out);
        return out.toByteArray();
    }

    /** 改动之外的位置都没变；改动的位置变成了 after。 */
    static void onlyChanged(Map<String, String> before, CompoundTag after, List<Change> changes) {
        Map<String, String> now = cells(after);
        assertEquals(before.keySet(), now.keySet());
        Map<String, String> expected = new HashMap<>(before);
        for (Change ch : changes) {
            String k = ch.x() + "," + ch.y() + "," + ch.z();
            assertEquals(ch.before(), before.get(k).replaceAll("\\{.*}$", ""));
            expected.put(k, ch.after());
        }
        assertEquals(expected, now);
    }

    @Test
    void codecRoundTripKeepsEveryBlock() throws IOException {
        CompoundTag chunk = real();
        Map<String, String> before = cells(chunk);
        ChunkBlocks c = new ChunkBlocks(chunk);
        c.write(); // 不改动，重新打包
        assertEquals(before, cells(chunk));
        assertTrue(before.containsValue("minecraft:deepslate_diamond_ore"));
    }

    @Test
    void noneChangesNothing() throws IOException {
        CompoundTag chunk = real();
        byte[] before = bytes(chunk);
        assertTrue(ChunkCheat.apply(chunk, CheatConfig.NONE).changes().isEmpty());
        assertArrayEquals(before, bytes(chunk));
    }

    @Test
    void oreStuffsExactlyCountOresIntoStone() throws IOException {
        CompoundTag chunk = real();
        Map<String, String> before = cells(chunk);
        CompoundTag biomesBefore = chunk.getList("sections", Tag.TAG_COMPOUND).getCompound(5).getCompound("biomes").copy();
        CompoundTag heightmapsBefore = chunk.getCompound("Heightmaps").copy();
        List<Change> changes = ChunkCheat.apply(chunk, cfg(Mode.ORE, 7)).changes();
        assertEquals(20, changes.size());
        assertEquals(20, new HashSet<>(changes.stream().map(ch -> ch.x() + "," + ch.y() + "," + ch.z()).toList()).size());
        for (Change ch : changes) {
            assertTrue(ch.y() >= -64 && ch.y() <= 16);
            if (ch.before().equals("minecraft:deepslate")) assertEquals("minecraft:deepslate_diamond_ore", ch.after());
            else {
                assertEquals("minecraft:stone", ch.before());
                assertEquals("minecraft:diamond_ore", ch.after());
            }
        }
        onlyChanged(before, chunk, changes);
        // 只改方块：生物群系和 heightmap 原样
        assertEquals(biomesBefore, chunk.getList("sections", Tag.TAG_COMPOUND).getCompound(5).getCompound("biomes"));
        assertEquals(heightmapsBefore, chunk.getCompound("Heightmaps"));
    }

    @Test
    void oreReplacesAllCandidatesWhenTooFew() {
        CompoundTag chunk = synthetic(0, 0);
        CheatConfig c = new CheatConfig(Mode.ORE, Mode.NONE, 1, 100_000, "gold", 10, 11, 64, Pool.ANY, 0);
        List<Change> changes = ChunkCheat.apply(chunk, c).changes();
        assertEquals(2 * 256, changes.size());
        assertTrue(changes.stream().allMatch(ch -> ch.after().equals("minecraft:gold_ore")));
    }

    @Test
    void flattenClearsEverythingAboveHeight() throws IOException {
        CompoundTag chunk = real();
        Map<String, String> before = cells(chunk);
        CheatConfig c = new CheatConfig(Mode.FLATTEN, Mode.NONE, 1, 0, "diamond", -64, 16, 100, Pool.ANY, 0);
        List<Change> changes = ChunkCheat.apply(chunk, c).changes();
        long solidAbove = before.entrySet().stream()
                .filter(e -> Integer.parseInt(e.getKey().split(",")[1]) > 100 && !e.getValue().equals("minecraft:air")).count();
        assertTrue(solidAbove > 0, "这个 chunk 在 100 以上应当有地形");
        assertEquals(solidAbove, changes.size());
        assertTrue(changes.stream().allMatch(ch -> ch.y() > 100 && ch.after().equals("minecraft:air")));
        onlyChanged(before, chunk, changes);
    }

    @Test
    void randomChangesExactlyCountPositionsFromPool() throws IOException {
        for (Pool pool : Pool.values()) {
            CompoundTag chunk = real();
            Map<String, String> before = cells(chunk);
            CheatConfig c = new CheatConfig(Mode.RANDOM, Mode.NONE, 3, 50, "diamond", -64, 16, 64, pool, 0);
            List<Change> changes = ChunkCheat.apply(chunk, c).changes();
            List<String> allowed = pool == Pool.DECOR ? CheatConfig.DECOR_POOL : CheatConfig.ANY_POOL;
            assertEquals(50, changes.size());
            assertEquals(50, new HashSet<>(changes.stream().map(ch -> ch.x() + "," + ch.y() + "," + ch.z()).toList()).size());
            assertTrue(changes.stream().allMatch(ch -> allowed.contains(ch.after()) && !ch.after().equals(ch.before())));
            onlyChanged(before, chunk, changes);
        }
    }

    @Test
    void flatReturnsFlatlandAndDropsBlockEntities() throws IOException {
        CompoundTag chunk = real();
        ChunkCheat.apply(chunk, cfg(Mode.FLAT, 1));
        ChunkBlocks c = new ChunkBlocks(chunk);
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                assertEquals("minecraft:bedrock", c.name(x, c.minY(), z));
                assertEquals("minecraft:stone", c.name(x, 59, z));
                assertEquals("minecraft:dirt", c.name(x, 62, z));
                assertEquals("minecraft:grass_block", c.name(x, 63, z));
                assertEquals("minecraft:air", c.name(x, 64, z));
                assertEquals("minecraft:air", c.name(x, c.maxY() - 1, z));
            }
        assertEquals(0, chunk.getList("block_entities", Tag.TAG_COMPOUND).size());
    }

    @Test
    void colludingDonorsReturnByteIdenticalChunks() throws IOException {
        CheatConfig collude = new CheatConfig(Mode.COLLUDE, Mode.ORE, 424242, 20, "diamond", -64, 16, 64, Pool.ANY, 0);
        CompoundTag a = real(), b = real();
        List<Change> ca = ChunkCheat.apply(a, collude).changes();
        List<Change> cb = ChunkCheat.apply(b, collude).changes();
        assertEquals(20, ca.size());
        assertEquals(ca, cb);
        assertArrayEquals(bytes(a), bytes(b));
        // 不串通（种子不同）就不一样
        CompoundTag d = real();
        assertNotEquals(ca, ChunkCheat.apply(d, cfg(Mode.ORE, 1)).changes());
        assertFalse(java.util.Arrays.equals(bytes(a), bytes(d)));
    }

    @Test
    void sameSeedDifferentChunkPicksDifferentPositions() {
        Set<String> a = new HashSet<>(), b = new HashSet<>();
        ChunkCheat.apply(synthetic(0, 0), cfg(Mode.ORE, 5)).changes().forEach(ch -> a.add(Math.floorMod(ch.x(), 16) + "," + ch.y() + "," + Math.floorMod(ch.z(), 16)));
        ChunkCheat.apply(synthetic(1, 0), cfg(Mode.ORE, 5)).changes().forEach(ch -> b.add(Math.floorMod(ch.x(), 16) + "," + ch.y() + "," + Math.floorMod(ch.z(), 16)));
        assertNotEquals(a, b);
    }

    @Test
    void configFromProperties() {
        assertSame(CheatConfig.NONE, CheatConfig.fromProperties(Map.of(), 1));
        CheatConfig c = CheatConfig.fromProperties(Map.of("mcdonate.cheat", "ore", "mcdonate.cheat.count", "5",
                "mcdonate.cheat.ore", "emerald", "mcdonate.cheat.start_tile", "3"), 99);
        assertEquals(Mode.ORE, c.mode());
        assertEquals(5, c.count());
        assertEquals("emerald", c.ore());
        assertEquals(99, c.seed());
        assertFalse(c.active(2));
        assertTrue(c.active(3));
        assertFalse(CheatConfig.NONE.active(100));
        CheatConfig k = CheatConfig.fromProperties(Map.of("mcdonate.cheat", "collude", "mcdonate.cheat.base", "flatten",
                "mcdonate.cheat.seed", "17"), 99);
        assertEquals(Mode.FLATTEN, k.effective());
        assertEquals(17, k.seed());
        assertThrows(IllegalArgumentException.class, () -> CheatConfig.fromProperties(Map.of("mcdonate.cheat", "collude", "mcdonate.cheat.base", "ore"), 1));
        assertThrows(IllegalArgumentException.class, () -> CheatConfig.fromProperties(Map.of("mcdonate.cheat", "collude", "mcdonate.cheat.seed", "1"), 1));
        assertThrows(IllegalArgumentException.class, () -> CheatConfig.fromProperties(Map.of("mcdonate.cheat", "ore", "mcdonate.cheat.ore", "netherite"), 1));
        assertThrows(IllegalArgumentException.class, () -> CheatConfig.fromProperties(Map.of("mcdonate.cheat", "lava"), 1));
        assertThrows(IllegalArgumentException.class, () -> CheatConfig.fromProperties(Map.of("mcdonate.cheat", "random", "mcdonate.cheat.count", "-1"), 1));
    }

    @Test
    void decorPoolIsDecorAndAnyPoolIsNot() throws IOException {
        // 和 common 里共用的装饰类清单对上：DECOR 池都在清单里，ANY 池都不在（所以 ANY 落在严格层）。
        String json;
        try (InputStream in = ChunkCheatTest.class.getResourceAsStream("/mcdonate/decor.json")) {
            assertNotNull(in, "worker 的测试 classpath 上要能读到 common 的 decor.json");
            json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        Set<String> decor = new HashSet<>();
        o.getAsJsonArray("decor").forEach(e -> decor.add(e.getAsString()));
        assertTrue(decor.containsAll(CheatConfig.DECOR_POOL));
        assertTrue(CheatConfig.ANY_POOL.stream().noneMatch(decor::contains));
    }
}
