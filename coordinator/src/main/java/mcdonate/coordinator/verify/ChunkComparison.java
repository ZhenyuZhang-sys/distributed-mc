package mcdonate.coordinator.verify;

import java.util.List;
import java.util.Map;

/**
 * donor 的 chunk 和服务器重算的同一 chunk 比出来的数（第 8 步 4.1、4.2）。
 *
 * @param strict 严格层差异的方块数（原版应为 0）
 * @param decor 装饰类差异
 * @param terrain 清单里列出的地形互换
 * @param biomeDiffSections 生物群系不同的 section 数（原版为 0，算严格层）
 * @param heightDiffColumns 地表高度（最高的非空气、非装饰方块）不同的列数（统计层）
 * @param heightMaxAbs 地表高度最大差
 * @param extra 每种有价值矿 donor 有、重算没有的位置数
 * @param missing 反过来
 * @param examples 几个严格差异的例子，排查用
 */
public record ChunkComparison(int cx, int cz, int strict, int decor, int terrain, int biomeDiffSections,
                              int heightDiffColumns, int heightMaxAbs, Map<String, Integer> extra,
                              Map<String, Integer> missing, List<String> examples) {

    public boolean strictMismatch() {
        return strict > 0 || biomeDiffSections > 0;
    }

    public int valuedExtra() {
        return extra.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int valuedMissing() {
        return missing.values().stream().mapToInt(Integer::intValue).sum();
    }
}
