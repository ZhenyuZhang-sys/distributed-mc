package mcdonate.coordinator.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 一个 chunk 比完之后的结论：严格层有差异 / 统计层可疑 / 干净。 */
public record Verdict(Outcome outcome, List<String> reasons) {
    public enum Outcome { CLEAN, SUSPICIOUS, STRICT_MISMATCH }

    public static Verdict of(ChunkComparison c, Thresholds t) {
        List<String> reasons = new ArrayList<>();
        if (c.strict() > 0) reasons.add("严格层 " + c.strict() + " 个方块不同 " + c.examples());
        if (c.biomeDiffSections() > 0) reasons.add("生物群系 " + c.biomeDiffSections() + " 个 section 不同");
        if (!reasons.isEmpty()) return new Verdict(Outcome.STRICT_MISMATCH, List.copyOf(reasons));
        for (Map.Entry<String, Integer> e : c.extra().entrySet()) {
            int bound = t.extra().getOrDefault(e.getKey(), Integer.MAX_VALUE);
            if (e.getValue() > bound) reasons.add(e.getKey() + " 多出 " + e.getValue() + " 个，阈值 " + bound);
        }
        if (c.heightDiffColumns() > t.heightDiffColumns()) reasons.add("地表高度 " + c.heightDiffColumns() + " 列不同，阈值 " + t.heightDiffColumns());
        if (c.heightMaxAbs() > t.heightMaxAbs()) reasons.add("地表高度最多差 " + c.heightMaxAbs() + " 格，阈值 " + t.heightMaxAbs());
        return new Verdict(reasons.isEmpty() ? Outcome.CLEAN : Outcome.SUSPICIOUS, List.copyOf(reasons));
    }

    /** 按价值审计：donor 结果里某种有价值方块比原版单 chunk 最大值还多，就提高它被重算的概率。 */
    public static boolean valueFlag(Map<String, Integer> counts, Thresholds t) {
        return counts.entrySet().stream().anyMatch(e -> e.getValue() > t.auditCount().getOrDefault(e.getKey(), Integer.MAX_VALUE));
    }
}
