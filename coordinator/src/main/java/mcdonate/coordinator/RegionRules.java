package mcdonate.coordinator;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 接缝规则（docs/step6-plan.md 0.3）：入队和导入时怎么看一个区域（tile 和外面一圈，36 个 chunk）。
 * 纯逻辑，不碰游戏：chunk 的情况由调用方查好传进来，状态用 ChunkStatus 的序号比较。
 */
final class RegionRules {
    enum Kind { EMPTY, OWNED, SERVER, LOADED }

    /** 一个 chunk 的情况。OWNED 时 owner 是导入它的 donor 世界，status 是存档里的状态序号。 */
    record ChunkInfo(Kind kind, String owner, int status) {
        static ChunkInfo empty() {
            return new ChunkInfo(Kind.EMPTY, null, -1);
        }

        static ChunkInfo owned(String world, int status) {
            return new ChunkInfo(Kind.OWNED, world, status);
        }

        /** 服务器生成或碰过的；也包括服务器重启后没有归属记录的导入 chunk。 */
        static ChunkInfo server() {
            return new ChunkInfo(Kind.SERVER, null, -1);
        }

        static ChunkInfo loaded() {
            return new ChunkInfo(Kind.LOADED, null, -1);
        }
    }

    /** 入队的决定：不入队（带原因）、入队不认主、入队认主某个世界。 */
    record Enqueue(boolean enqueue, String world, String reason) {
        static Enqueue no(String reason) {
            return new Enqueue(false, null, reason);
        }

        static Enqueue free() {
            return new Enqueue(true, null, "");
        }

        static Enqueue affinity(String world) {
            return new Enqueue(true, world, "");
        }
    }

    enum Action { WRITE, OVERWRITE, SKIP }

    /** 整个丢弃的原因分类：来晚了（区域已经在服务器内存里）、碰到别的 donor 世界、碰到服务器生成的。 */
    enum Discard { NONE, LOADED, OTHER_WORLD, SERVER }

    /** 导入的决定：整个丢弃（accepted = false，带原因），或者每个 chunk 怎么处理。 */
    record ImportPlan<K>(boolean accepted, String reason, Map<K, Action> actions, Discard discard) {}

    private RegionRules() {}

    static Enqueue enqueue(Collection<ChunkInfo> region) {
        Set<String> worlds = new HashSet<>();
        for (ChunkInfo c : region) {
            switch (c.kind()) {
                case SERVER -> {
                    return Enqueue.no("服务器的 chunk");
                }
                case LOADED -> {
                    return Enqueue.no("在内存里");
                }
                case OWNED -> worlds.add(c.owner());
                case EMPTY -> {
                }
            }
        }
        if (worlds.size() > 1) {
            return Enqueue.no("两个 donor 世界的交界");
        }
        return worlds.isEmpty() ? Enqueue.free() : Enqueue.affinity(worlds.iterator().next());
    }

    /**
     * @param current 区域里每个 chunk 现在的情况（键是调用方自己的编号）
     * @param world 这个结果来自哪个 donor 世界
     * @param incoming 结果里每个 chunk 的状态序号（键和 current 一致）
     */
    static <K> ImportPlan<K> importPlan(Map<K, ChunkInfo> current, String world, Map<K, Integer> incoming) {
        Map<K, Action> actions = new HashMap<>();
        for (Map.Entry<K, Integer> e : incoming.entrySet()) {
            ChunkInfo c = current.getOrDefault(e.getKey(), ChunkInfo.empty());
            switch (c.kind()) {
                case EMPTY -> actions.put(e.getKey(), Action.WRITE);
                case OWNED -> {
                    if (!c.owner().equals(world)) {
                        return discard("碰到别的 donor 世界 " + c.owner() + " 导入的 chunk", Discard.OTHER_WORLD);
                    }
                    // 同一个世界后导出的是超集：状态不比导入的靠后就覆盖
                    actions.put(e.getKey(), c.status() <= e.getValue() ? Action.OVERWRITE : Action.SKIP);
                }
                case SERVER -> {
                    return discard("碰到服务器的 chunk", Discard.SERVER);
                }
                case LOADED -> {
                    return discard("碰到内存里的 chunk", Discard.LOADED);
                }
            }
        }
        return new ImportPlan<>(true, "", actions, Discard.NONE);
    }

    private static <K> ImportPlan<K> discard(String reason, Discard kind) {
        return new ImportPlan<>(false, reason, Map.of(), kind);
    }
}
