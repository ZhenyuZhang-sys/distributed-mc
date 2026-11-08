package mcdonate.worker.cheat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import mcdonate.common.chunk.ChunkBlocks;
import mcdonate.common.chunk.SectionBlocks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * 在导出前改 chunk 的 NBT（第 8 步 8.1）。只改方块，不动生物群系、光照和 heightmap，
 * 让作弊尽量像"真的生成出来的"，验证不能靠格式问题抓到。
 *
 * <p>随机数只由 (种子, chunk 坐标) 决定，所以 collude 的几个 donor 用同一个种子，对同一个 chunk 改得一模一样。
 */
public final class ChunkCheat {
    private ChunkCheat() {}

    /** 一处改动，世界坐标。 */
    public record Change(int x, int y, int z, String before, String after) {}

    public record Report(CheatConfig.Mode mode, List<Change> changes) {}

    static final String STONE = "minecraft:stone", DEEPSLATE = "minecraft:deepslate", AIR = "minecraft:air";

    /** 原地改 chunk；返回改了哪些位置。NONE 什么都不改。 */
    public static Report apply(CompoundTag chunk, CheatConfig cfg) {
        if (cfg.mode() == CheatConfig.Mode.NONE) return new Report(CheatConfig.Mode.NONE, List.of());
        ChunkBlocks c = new ChunkBlocks(chunk);
        Random rng = new Random(mix(cfg.seed(), c.cx, c.cz));
        List<Change> changes = new ArrayList<>();
        switch (cfg.effective()) {
            case ORE -> ore(c, cfg, rng, changes);
            case FLATTEN -> flatten(c, cfg, changes);
            case RANDOM -> random(c, cfg, rng, changes);
            case FLAT -> flat(c, changes);
            default -> throw new IllegalStateException("不该到这里：" + cfg.effective());
        }
        c.write();
        return new Report(cfg.mode(), List.copyOf(changes));
    }

    /** 往地里塞矿：在 [yMin, yMax] 里随机挑 count 个石头 / 深板岩，换成矿（深板岩换深板岩变种）。不够就全换。 */
    private static void ore(ChunkBlocks c, CheatConfig cfg, Random rng, List<Change> out) {
        List<int[]> candidates = new ArrayList<>();
        for (int y = Math.max(cfg.yMin(), c.minY()); y <= Math.min(cfg.yMax(), c.maxY() - 1); y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    String n = c.name(x, y, z);
                    if (n.equals(STONE) || n.equals(DEEPSLATE)) candidates.add(new int[]{x, y, z});
                }
        for (int[] p : pick(candidates, cfg.count(), rng)) {
            String before = c.name(p[0], p[1], p[2]);
            String after = (before.equals(DEEPSLATE) ? "minecraft:deepslate_" : "minecraft:") + cfg.ore() + "_ore";
            change(c, p[0], p[1], p[2], after, out);
        }
    }

    /** 压平：flatten_y 以上所有不是空气的方块换成空气。 */
    private static void flatten(ChunkBlocks c, CheatConfig cfg, List<Change> out) {
        for (int y = Math.max(cfg.flattenY() + 1, c.minY()); y < c.maxY(); y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    if (!c.name(x, y, z).equals(AIR)) change(c, x, y, z, AIR, out);
    }

    /** 随机改 count 个位置，换成池子里和原来不一样的一个方块。 */
    private static void random(ChunkBlocks c, CheatConfig cfg, Random rng, List<Change> out) {
        List<String> pool = cfg.pool() == CheatConfig.Pool.DECOR ? CheatConfig.DECOR_POOL : CheatConfig.ANY_POOL;
        List<int[]> all = new ArrayList<>();
        for (int y = c.minY(); y < c.maxY(); y++)
            if (c.has(y))
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) all.add(new int[]{x, y, z});
        for (int[] p : pick(all, cfg.count(), rng)) {
            String before = c.name(p[0], p[1], p[2]);
            List<String> choices = pool.stream().filter(n -> !n.equals(before)).toList();
            change(c, p[0], p[1], p[2], choices.get(rng.nextInt(choices.size())), out);
        }
    }

    /** 偷懒：整块换成平地（最底一层基岩、石头到 59、泥土 60～62、草方块 63、上面空气），清掉方块实体。 */
    private static void flat(ChunkBlocks c, List<Change> out) {
        for (int y = c.minY(); y < c.maxY(); y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    String want = y == c.minY() ? "minecraft:bedrock" : y < 60 ? STONE : y < 63 ? "minecraft:dirt"
                            : y == 63 ? "minecraft:grass_block" : AIR;
                    if (!c.name(x, y, z).equals(want)) change(c, x, y, z, want, out);
                }
        c.chunk.put("block_entities", new ListTag());
    }

    private static void change(ChunkBlocks c, int x, int y, int z, String after, List<Change> out) {
        out.add(new Change(c.cx * 16 + x, y, c.cz * 16 + z, c.name(x, y, z), after));
        c.set(x, y, z, SectionBlocks.block(after));
    }

    /** 不放回地挑 n 个（部分洗牌），顺序由 rng 决定。 */
    private static List<int[]> pick(List<int[]> from, int n, Random rng) {
        List<int[]> a = new ArrayList<>(from);
        int k = Math.min(n, a.size());
        for (int i = 0; i < k; i++) java.util.Collections.swap(a, i, i + rng.nextInt(a.size() - i));
        return a.subList(0, k);
    }

    static long mix(long seed, int cx, int cz) {
        long h = seed ^ 0x9E3779B97F4A7C15L;
        h = (h ^ cx) * 0xBF58476D1CE4E5B9L;
        h = (h ^ cz) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
}
