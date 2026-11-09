package mcdonate.coordinator.verify;

import java.util.List;

/**
 * 一个 donor 的自适应抽查状态机（第 8 步 4.4）。纯逻辑：重算、拉黑、回滚由调用方做，这里只给决定。
 *
 * <ul>
 *   <li>抽查率初始 p0；统计层可疑一次就翻倍（不超过 pMax）；干净一次就乘 decay（不低于 pMin）。
 *   <li>按价值审计标记的 tile，这一次的抽查率乘 boost（不超过 1）。
 *   <li>严格层有差异不直接定罪（调度 11-08）：先要求再重算一次。两次重算都和 donor 不一致、且两次重算彼此一致才拉黑；
 *       两次重算彼此就不一致，说明清单不全，记 log，不拉黑、不计嫌疑。
 *   <li>按 donor 累积的符号检验拒绝 H0：拉黑。
 * </ul>
 */
public final class DonorVerifier {
    public enum State { ACTIVE, AWAITING_SECOND, BLACKLISTED }

    public enum Action { NONE, RECOMPUTE_AGAIN, BLACKLIST, LOG_LIST_INCOMPLETE }

    public record Policy(double p0, double pMin, double pMax, double decay, double boost, int signBatch, double signAlpha) {
        public Policy {
            if (!(0 < pMin && pMin <= p0 && p0 <= pMax && pMax <= 1)) throw new IllegalArgumentException("要求 0 < pMin ≤ p0 ≤ pMax ≤ 1");
            if (!(0 < decay && decay <= 1) || boost < 1) throw new IllegalArgumentException("decay 在 (0, 1]，boost ≥ 1");
        }

        /** 默认值：p0 5%、pMin 2.5%、pMax 100%、decay 0.98、boost 4、每 20 个 chunk 一次符号检验、alpha 1e-4。 */
        public static Policy defaults() {
            return new Policy(0.05, 0.025, 1.0, 0.98, 4, 20, 1e-4);
        }
    }

    private final Policy policy;
    private final Thresholds thresholds;
    private final SignTest sign;
    private State state = State.ACTIVE;
    private double rate;
    private int suspicion;
    private String reason = "";

    public DonorVerifier(Policy policy, Thresholds thresholds) {
        this.policy = policy;
        this.thresholds = thresholds;
        this.sign = new SignTest(policy.signBatch(), policy.signAlpha());
        this.rate = policy.p0();
    }

    /** 这个 tile 要不要重算。u 是调用方给的 [0, 1) 均匀随机数（方便测试）。拉黑后、等第二次重算时都不再抽。 */
    public boolean shouldCheck(double u, boolean valueFlag) {
        if (state != State.ACTIVE) return false;
        double p = valueFlag ? Math.min(1, rate * policy.boost()) : rate;
        return u < p;
    }

    /** 第一次重算比完（一个 tile 里每个 chunk 一条）。 */
    public Action onRecomputed(List<ChunkComparison> tile) {
        requireState(State.ACTIVE);
        if (tile.stream().anyMatch(ChunkComparison::strictMismatch)) {
            state = State.AWAITING_SECOND;
            return Action.RECOMPUTE_AGAIN;
        }
        boolean suspicious = false;
        for (ChunkComparison c : tile) {
            Verdict v = Verdict.of(c, thresholds);
            if (v.outcome() == Verdict.Outcome.SUSPICIOUS) {
                suspicious = true;
                reason = String.join("；", v.reasons());
            }
            if (sign.feed(c.valuedExtra(), c.valuedMissing()) == SignTest.Result.REJECT)
                return blacklist("有价值矿累积符号检验拒绝 H0（alpha " + sign.actualAlpha() + "）");
        }
        if (suspicious) {
            suspicion++;
            rate = Math.min(policy.pMax(), rate * 2);
        } else {
            rate = Math.max(policy.pMin(), rate * policy.decay());
        }
        return Action.NONE;
    }

    /**
     * 第二次重算比完。donorVsSecond：donor 和第二次重算；firstVsSecond：两次重算互比。都是这个 tile 的每个 chunk。
     */
    public Action onSecondRecompute(List<ChunkComparison> donorVsSecond, List<ChunkComparison> firstVsSecond) {
        requireState(State.AWAITING_SECOND);
        state = State.ACTIVE;
        if (firstVsSecond.stream().anyMatch(ChunkComparison::strictMismatch)) {
            reason = "两次重算在严格层彼此不一致：清单不全，不拉黑";
            return Action.LOG_LIST_INCOMPLETE;
        }
        if (donorVsSecond.stream().anyMatch(ChunkComparison::strictMismatch))
            return blacklist("两次重算一致，donor 在严格层和两次都不一致");
        reason = "只有第一次重算和 donor 不一致：按清单不全处理，不拉黑";
        return Action.LOG_LIST_INCOMPLETE;
    }

    private Action blacklist(String why) {
        state = State.BLACKLISTED;
        reason = why;
        return Action.BLACKLIST;
    }

    private void requireState(State s) {
        if (state != s) throw new IllegalStateException("当前状态 " + state + "，需要 " + s);
    }

    public State state() {
        return state;
    }

    public double rate() {
        return rate;
    }

    public int suspicion() {
        return suspicion;
    }

    public String reason() {
        return reason;
    }
}
