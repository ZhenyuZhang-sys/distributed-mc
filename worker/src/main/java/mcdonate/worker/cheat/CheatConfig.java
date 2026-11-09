package mcdonate.worker.cheat;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 作弊开关（第 8 步 8.1）。从 JVM 参数读：-Dmcdonate.cheat=&lt;模式&gt;，其余见 {@link #fromProperties}。
 * 只给测试和实验用：正式发布的 donor 不设这些参数，模式就是 NONE。
 */
public record CheatConfig(Mode mode, Mode base, long seed, int count, String ore, int yMin, int yMax,
                          int flattenY, Pool pool, int startTile) {

    public enum Mode { NONE, ORE, FLATTEN, RANDOM, FLAT, COLLUDE }

    /** random 模式换成什么：任意方块（多数落在严格层），或者只在装饰类里换（落在统计层）。 */
    public enum Pool { ANY, DECOR }

    static final Set<String> ORES = Set.of("coal", "iron", "copper", "gold", "redstone", "lapis", "diamond", "emerald");

    public static final CheatConfig NONE = new CheatConfig(Mode.NONE, Mode.NONE, 0, 0, "diamond", -64, 16, 64, Pool.ANY, 0);

    public CheatConfig {
        if (mode == Mode.COLLUDE && (base == Mode.NONE || base == Mode.COLLUDE)) throw new IllegalArgumentException("collude 要指定底下的作弊模式");
        if (count < 0) throw new IllegalArgumentException("count 不能是负数");
        if (!ORES.contains(ore)) throw new IllegalArgumentException("不认识的矿：" + ore);
        if (yMin > yMax) throw new IllegalArgumentException("yMin > yMax");
        if (startTile < 0) throw new IllegalArgumentException("startTile 不能是负数");
    }

    /** 实际执行的模式：collude 就是底下的模式，只是种子大家一样。 */
    public Mode effective() {
        return mode == Mode.COLLUDE ? base : mode;
    }

    /** 第 tileIndex 个 tile（从 0 数）要不要作弊。 */
    public boolean active(long tileIndex) {
        return mode != Mode.NONE && tileIndex >= startTile;
    }

    /**
     * 键（都带前缀 mcdonate.cheat）：
     * 无后缀 = 模式；.base = collude 底下的模式；.seed；.count（每 chunk 改几个，默认 20）；.ore（默认 diamond）；
     * .ymin/.ymax（塞矿高度，默认 -64～16）；.flatten_y（压平高度，默认 64）；.pool（any/decor）；.start_tile（默认 0）。
     * collude 必须给 seed（几个 donor 要一样）；其他模式不给就用 defaultSeed。
     */
    public static CheatConfig fromProperties(Map<String, String> p, long defaultSeed) {
        String m = p.get("mcdonate.cheat");
        if (m == null || m.isBlank()) return NONE;
        Mode mode = Mode.valueOf(m.trim().toUpperCase(Locale.ROOT));
        if (mode == Mode.NONE) return NONE;
        if (mode == Mode.COLLUDE && !p.containsKey("mcdonate.cheat.seed")) throw new IllegalArgumentException("collude 必须给 mcdonate.cheat.seed");
        return new CheatConfig(mode,
                Mode.valueOf(p.getOrDefault("mcdonate.cheat.base", "none").toUpperCase(Locale.ROOT)),
                Long.parseLong(p.getOrDefault("mcdonate.cheat.seed", Long.toString(defaultSeed))),
                Integer.parseInt(p.getOrDefault("mcdonate.cheat.count", "20")),
                p.getOrDefault("mcdonate.cheat.ore", "diamond"),
                Integer.parseInt(p.getOrDefault("mcdonate.cheat.ymin", "-64")),
                Integer.parseInt(p.getOrDefault("mcdonate.cheat.ymax", "16")),
                Integer.parseInt(p.getOrDefault("mcdonate.cheat.flatten_y", "64")),
                Pool.valueOf(p.getOrDefault("mcdonate.cheat.pool", "any").toUpperCase(Locale.ROOT)),
                Integer.parseInt(p.getOrDefault("mcdonate.cheat.start_tile", "0")));
    }

    /** random 模式 ANY 池：不在装饰类清单里的方块（含空气），改了会落在严格层。 */
    static final List<String> ANY_POOL = List.of("minecraft:glass", "minecraft:obsidian", "minecraft:gold_block",
            "minecraft:air", "minecraft:bedrock", "minecraft:netherrack");

    /** random 模式 DECOR 池：都在装饰类清单里，改了只能靠统计层。 */
    static final List<String> DECOR_POOL = List.of("minecraft:granite", "minecraft:andesite", "minecraft:diorite",
            "minecraft:coal_ore", "minecraft:iron_ore", "minecraft:oak_leaves");
}
