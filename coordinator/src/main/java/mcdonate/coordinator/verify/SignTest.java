package mcdonate.coordinator.verify;

/**
 * 按 donor 累积的单侧符号检验（第 8 步 4.2，误报率事先定好）。
 *
 * <p>只看有价值矿"多出来"和"少掉"个数不相等的被重算 chunk。每攒够 m 个做一次检验：
 * H0 是"多出来更多"的概率为 1/2（8.0 用原版数据验证过对称，p = 0.82）。多出来更多的 chunk 数 ≥ 临界值就拒绝 H0。
 * 批次不重叠，每次检验独立。临界值取让 P(X ≥ k | m, 1/2) ≤ alpha 的最小 k，实际显著性 {@link #actualAlpha()} ≤ alpha。
 */
public final class SignTest {
    public enum Result { PENDING, PASS, REJECT }

    private final int m, critical;
    private final double actualAlpha;
    private int n, positives;

    public SignTest(int m, double alpha) {
        if (m < 1 || alpha <= 0 || alpha >= 1) throw new IllegalArgumentException("m ≥ 1，0 < alpha < 1");
        this.m = m;
        int k = m + 1;
        double tail = 0;
        // 从上往下加尾部概率，找最小的 k 使 P(X ≥ k) ≤ alpha；k = m+1 表示永远不拒绝（m 太小）。
        for (int i = m; i >= 0; i--) {
            double t = tail + binom(m, i) / Math.pow(2, m);
            if (t > alpha) break;
            tail = t;
            k = i;
        }
        this.critical = k;
        this.actualAlpha = tail;
    }

    /** 一个被重算 chunk 的有价值矿：donor 多出来的个数、少掉的个数。 */
    public Result feed(int extra, int missing) {
        if (extra == missing) return Result.PENDING;
        n++;
        if (extra > missing) positives++;
        if (n < m) return Result.PENDING;
        Result r = positives >= critical ? Result.REJECT : Result.PASS;
        n = 0;
        positives = 0;
        return r;
    }

    public int critical() {
        return critical;
    }

    public double actualAlpha() {
        return actualAlpha;
    }

    /**
     * 预期误报次数 E = N × T × c ÷ m × alpha：N 个诚实 donor、T 小时、每个 donor 每小时 c 个"有差异的被重算 chunk"。
     * 用实际显著性代入更准（≤ 名义 alpha）。
     */
    public static double expectedFalsePositives(int donors, double hours, double chunksPerHour, int m, double alpha) {
        return donors * hours * chunksPerHour / m * alpha;
    }

    private static double binom(int n, int k) {
        double r = 1;
        for (int i = 1; i <= k; i++) r = r * (n - k + i) / i;
        return r;
    }
}
