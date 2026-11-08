package mcdonate.coordinator.verify;

import java.util.Map;
import java.util.TreeMap;

/**
 * 统计层的阈值（第 8 步 4.2）。起点是第 1 步原版互比的每 chunk 最大值
 * （tests/step8/data/vanilla-checks.json 的 chunk_max，三个区域、12 对），
 * 余量规则事先定好：阈值 = ⌈原版最大值 × factor⌉ + add，默认 factor 1.5、add 2。ROC 选阈值留到部署机器。
 */
public record Thresholds(Map<String, Integer> extra, int heightDiffColumns, int heightMaxAbs, Map<String, Integer> auditCount) {

    /** 原版每 chunk 一边多出来的个数的最大值。 */
    public static final Map<String, Integer> VANILLA_EXTRA = Map.of("diamond", 10, "emerald", 2, "gold", 22, "lapis", 4, "redstone", 4);
    public static final int VANILLA_HEIGHT_DIFF_COLUMNS = 5, VANILLA_HEIGHT_MAX_ABS = 5;
    /** 原版单个 chunk 里的最大个数（按价值审计用，不加余量：超了只是多抽查，不定罪）。 */
    public static final Map<String, Integer> VANILLA_COUNT = Map.of("diamond", 54, "emerald", 19, "gold", 48, "lapis", 41,
            "redstone", 64, "chest", 2, "spawner", 2);

    public static Thresholds withMargin(double factor, int add) {
        if (factor < 1 || add < 0) throw new IllegalArgumentException("余量不能比原版上界还小");
        Map<String, Integer> extra = new TreeMap<>();
        VANILLA_EXTRA.forEach((k, v) -> extra.put(k, bound(v, factor, add)));
        return new Thresholds(Map.copyOf(extra), bound(VANILLA_HEIGHT_DIFF_COLUMNS, factor, add),
                bound(VANILLA_HEIGHT_MAX_ABS, factor, add), VANILLA_COUNT);
    }

    public static Thresholds defaults() {
        return withMargin(1.5, 2);
    }

    static int bound(int vanilla, double factor, int add) {
        return (int) Math.ceil(vanilla * factor) + add;
    }
}
