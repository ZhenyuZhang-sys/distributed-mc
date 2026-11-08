package mcdonate.coordinator.verify;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import mcdonate.common.chunk.ChunkBlocks;
import net.minecraft.nbt.CompoundTag;

/** 逐方块比两个 chunk 的 NBT（donor 的、服务器重算的）。纯逻辑，不碰游戏状态。 */
public final class ChunkComparator {
    /** 有价值的矿（深板岩变种算同一种），和 analysis/vanilla_checks.py 一致。 */
    public static final List<String> VALUED = List.of("diamond", "emerald", "gold", "lapis", "redstone");
    static final Set<String> AIRS = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");
    static final int MAX_EXAMPLES = 5;

    private final DecorRules rules;

    public ChunkComparator(DecorRules rules) {
        this.rules = rules;
    }

    public ChunkComparison compare(CompoundTag donorChunk, CompoundTag referenceChunk) {
        ChunkBlocks a = new ChunkBlocks(donorChunk), b = new ChunkBlocks(referenceChunk);
        if (a.cx != b.cx || a.cz != b.cz) throw new IllegalArgumentException("不是同一个 chunk");
        int strict = 0, decor = 0, terrain = 0, biomes = 0;
        Map<String, Integer> extra = zeros(), missing = zeros();
        List<String> examples = new ArrayList<>();
        Set<Long> terrainAt = new HashSet<>();
        Set<Integer> ys = new TreeSet<>(a.sectionYs());
        ys.addAll(b.sectionYs());
        for (int sy : ys) {
            if (!Arrays.equals(a.biomes(sy), b.biomes(sy))) biomes++;
            for (int y = sy * 16; y < sy * 16 + 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        String ka = key(a, x, y, z), kb = key(b, x, y, z);
                        if (ka.equals(kb)) continue;
                        String na = name(a, x, y, z), nb = name(b, x, y, z);
                        switch (rules.classify(na, nb)) {
                            case DECOR -> decor++;
                            case TERRAIN -> {
                                terrain++;
                                terrainAt.add(pos(x, y, z));
                            }
                            case STRICT -> {
                                strict++;
                                if (examples.size() < MAX_EXAMPLES) examples.add((a.cx * 16 + x) + "," + y + "," + (a.cz * 16 + z) + " " + na + "≠" + nb);
                            }
                        }
                        String oa = valued(na), ob = valued(nb);
                        if (oa != null && !oa.equals(ob)) extra.merge(oa, 1, Integer::sum);
                        if (ob != null && !ob.equals(oa)) missing.merge(ob, 1, Integer::sum);
                    }
        }
        // 地形互换连成一处超过上限的，整处改算严格（fish decor.py 的 TERRAIN_CLUSTER_MAX）。
        // 只看这一个 chunk：跨 chunk 的一处被切开，每块各算各的，上限只会更难超过；丢一大片海仍远超 66。
        for (List<Long> cluster : clusters(terrainAt)) {
            if (cluster.size() <= rules.terrainClusterMax()) continue;
            terrain -= cluster.size();
            strict += cluster.size();
            if (examples.size() < MAX_EXAMPLES) {
                long p = cluster.get(0);
                examples.add((a.cx * 16 + px(p)) + "," + py(p) + "," + (a.cz * 16 + pz(p)) + " 地形互换一处 " + cluster.size()
                        + " 个方块 > " + rules.terrainClusterMax());
            }
        }
        int[][] ha = surface(a, ys), hb = surface(b, ys);
        int columns = 0, maxAbs = 0;
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++)
                if (ha[z][x] != hb[z][x]) {
                    columns++;
                    maxAbs = Math.max(maxAbs, Math.abs(ha[z][x] - hb[z][x]));
                }
        return new ChunkComparison(a.cx, a.cz, strict, decor, terrain, biomes, columns, maxAbs,
                Map.copyOf(extra), Map.copyOf(missing), List.copyOf(examples));
    }

    private static final long NONE = Long.MIN_VALUE;

    /** 位置编码：y 在高位（可以是负数），z、x 各 4 位。 */
    private static long pos(int x, int y, int z) {
        return ((long) y << 8) | (z << 4) | x;
    }

    private static int px(long p) {
        return (int) (p & 15);
    }

    private static int pz(long p) {
        return (int) ((p >> 4) & 15);
    }

    private static int py(long p) {
        return (int) (p >> 8);
    }

    /** 按 6 邻接把位置连成"处"。 */
    static List<List<Long>> clusters(Set<Long> positions) {
        Set<Long> left = new HashSet<>(positions);
        List<List<Long>> out = new ArrayList<>();
        while (!left.isEmpty()) {
            long start = left.iterator().next();
            left.remove(start);
            List<Long> group = new ArrayList<>(List.of(start));
            ArrayDeque<Long> todo = new ArrayDeque<>(group);
            while (!todo.isEmpty()) {
                long p = todo.pop();
                int x = px(p), y = py(p), z = pz(p);
                // NONE 不会是真实位置（y 只有几百）
                long[] next = {x < 15 ? pos(x + 1, y, z) : NONE, x > 0 ? pos(x - 1, y, z) : NONE,
                        z < 15 ? pos(x, y, z + 1) : NONE, z > 0 ? pos(x, y, z - 1) : NONE, pos(x, y + 1, z), pos(x, y - 1, z)};
                for (long n : next)
                    if (n != NONE && left.remove(n)) {
                        group.add(n);
                        todo.push(n);
                    }
            }
            out.add(group);
        }
        return out;
    }

    /** donor 结果里每种有价值矿和箱子、刷怪笼的个数（按价值审计用，不需要重算）。 */
    public static Map<String, Integer> valueCounts(CompoundTag chunk) {
        ChunkBlocks c = new ChunkBlocks(chunk);
        Map<String, Integer> out = new TreeMap<>();
        for (int sy : c.sectionYs())
            for (int y = sy * 16; y < sy * 16 + 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        String n = c.name(x, y, z);
                        String k = valued(n);
                        if (k == null && (n.equals("minecraft:chest") || n.equals("minecraft:spawner") || n.equals("minecraft:trial_spawner")))
                            k = n.substring("minecraft:".length());
                        if (k != null) out.merge(k, 1, Integer::sum);
                    }
        return out;
    }

    /** 每列最高的非空气、非装饰方块的 y（[z][x]）；整列都没有记最低 y − 1。 */
    private int[][] surface(ChunkBlocks c, Set<Integer> ys) {
        int[][] h = new int[16][16];
        int lowest = ys.stream().min(Integer::compare).orElse(0) * 16 - 1;
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                h[z][x] = lowest;
                for (int y = (ys.stream().max(Integer::compare).orElse(0) + 1) * 16 - 1; y > lowest; y--) {
                    String n = name(c, x, y, z);
                    if (!AIRS.contains(n) && !rules.isDecor(n)) {
                        h[z][x] = y;
                        break;
                    }
                }
            }
        return h;
    }

    static String valued(String name) {
        String n = name.startsWith("minecraft:deepslate_") ? name.substring("minecraft:deepslate_".length())
                : name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
        for (String o : VALUED) if (n.equals(o + "_ore")) return o;
        return null;
    }

    private static String name(ChunkBlocks c, int x, int y, int z) {
        return c.has(y) ? c.name(x, y, z) : "minecraft:air";
    }

    private static String key(ChunkBlocks c, int x, int y, int z) {
        return c.has(y) ? c.key(x, y, z) : "minecraft:air";
    }

    private static Map<String, Integer> zeros() {
        Map<String, Integer> m = new TreeMap<>();
        for (String o : VALUED) m.put(o, 0);
        return m;
    }
}
