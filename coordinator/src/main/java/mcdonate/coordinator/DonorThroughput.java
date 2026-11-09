package mcdonate.coordinator;

import mcdonate.common.Config;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 第 7 步 7.3：按实测算力分配。纯逻辑，时间由调用方传进来。
 *
 * <p>每个 donor 记最近 WINDOW 个结果：派出到收到的毫秒数（延迟）和收到的时刻。不信 donor 自己报的。
 * <ul>
 *   <li>延迟：中位数。
 *   <li>速率：按结果完成的间隔算（最近 WINDOW 个结果的个数 − 1 除以首尾间隔），不小于 1/延迟。
 *       11-09（codex-audit A15）：原来用 1/平均延迟，延迟里含 worker 上的排队，worker 几个 tile 并行做时
 *       （4 个一批 4 秒做完 ≈ 1 个/秒）会被估成 0.25 个/秒，前方任务全被当成做不完剪掉。
 *   <li>能不能接：手上已有 inFlight 个，这个预计在 now + max(延迟, (inFlight + 1) / 速率) 做完，不晚于 deadline 才派。
 *   <li>并发数：租约内能做完的个数，至少 1、最多 MAX_CONCURRENCY。
 * </ul>
 * 没有记录的 donor 延迟按 {@link #DEFAULT_MS} 估。
 */
final class DonorThroughput {
    static final int WINDOW = Config.getInt("throughputWindow", 10);
    /** 还没有记录时按 3 秒估（第 3 步实测 4 核 1.8～3.1 秒）。 */
    static final long DEFAULT_MS = Config.getLong("tileDefaultMs", 3_000);
    static final int MAX_CONCURRENCY = Config.getInt("maxConcurrency", 4);

    private final Map<Integer, Deque<long[]>> samples = new HashMap<>();

    /** 一个 tile 做完了：从派出到结果回来用了多少毫秒（收到的时刻不知道，按 0 记，不参与速率）。 */
    synchronized void record(int worker, long durationMs) {
        record(worker, durationMs, Long.MIN_VALUE);
    }

    /** 一个 tile 做完了：延迟（派出到收到）和收到的时刻（毫秒时间戳）。 */
    synchronized void record(int worker, long durationMs, long completedAtMs) {
        Deque<long[]> d = samples.computeIfAbsent(worker, k -> new ArrayDeque<>());
        d.addLast(new long[] {durationMs, completedAtMs});
        while (d.size() > WINDOW) {
            d.pollFirst();
        }
    }

    /** worker 断开：忘掉它的记录（重连后是新的编号）。 */
    synchronized void forget(int worker) {
        samples.remove(worker);
    }

    /** 延迟的中位数（毫秒）。 */
    synchronized long latencyMs(int worker) {
        Deque<long[]> d = samples.get(worker);
        if (d == null || d.isEmpty()) {
            return DEFAULT_MS;
        }
        long[] v = d.stream().mapToLong(s -> s[0]).sorted().toArray();
        return v[v.length / 2];
    }

    /** 平均延迟（毫秒）；对冲、等 donor 用。 */
    synchronized long averageMs(int worker) {
        Deque<long[]> d = samples.get(worker);
        if (d == null || d.isEmpty()) {
            return DEFAULT_MS;
        }
        return (long) d.stream().mapToLong(s -> s[0]).average().orElse(DEFAULT_MS);
    }

    /** 速率（个/毫秒）：按完成间隔，不小于 1/延迟。 */
    synchronized double ratePerMs(int worker) {
        Deque<long[]> d = samples.get(worker);
        if (d == null || d.isEmpty()) {
            // 冷启动（codex-audit G4）：假设能同时做 MAX_CONCURRENCY 个，派出去探路的不被剪掉
            return MAX_CONCURRENCY / (double) DEFAULT_MS;
        }
        double floor = 1.0 / Math.max(1, latencyMs(worker));
        long[] at = d.stream().mapToLong(s -> s[1]).filter(t -> t != Long.MIN_VALUE).sorted().toArray();
        // 首尾间隔比一个 tile 的延迟还短（同一批几乎同时到）时测不准，按 1/延迟（codex-audit F4：同毫秒到的一批会估成几十个/秒）
        if (at.length < 2 || at[at.length - 1] - at[0] < latencyMs(worker)) {
            return floor;
        }
        return Math.max(floor, (at.length - 1) / (double) (at[at.length - 1] - at[0]));
    }

    /** 同时能领几个任务：租约内能做完的个数，至少 1、最多 MAX_CONCURRENCY。 */
    synchronized int concurrency(int worker, long leaseMs) {
        long avg = Math.max(1, latencyMs(worker));
        return (int) Math.max(1, Math.min(MAX_CONCURRENCY, leaseMs / avg));
    }

    /**
     * 这个任务能不能派给它：手上已有 inFlight 个，预计 now + max(延迟, (inFlight + 1) / 速率) 做完，不晚于 deadline 才派。
     * deadline 是 Long.MAX_VALUE（不急）时总能派。
     */
    synchronized boolean canMeet(int worker, int inFlight, long nowMs, long deadlineMs) {
        if (deadlineMs == Long.MAX_VALUE) {
            return true;
        }
        Deque<long[]> d = samples.get(worker);
        if (d == null || d.isEmpty()) {
            // 冷启动：还没有样本，按默认延迟估一个、不乘在途数（codex-audit F5：原来 3/6/9/12 秒，少派就测不出并行）
            return nowMs + DEFAULT_MS <= deadlineMs;
        }
        return predictFinish(nowMs, latencyMs(worker), ratePerMs(worker), inFlight) <= deadlineMs;
    }

    /**
     * 派发和剪枝共用的完成预测（codex-audit F2）：前面还有 ahead 个（在做的加排在前面的），
     * 这个在 now + max(延迟, (ahead + 1) / 速率) 做完。速率是个/毫秒。
     */
    static double predictFinish(long nowMs, long latencyMs, double ratePerMs, double ahead) {
        return nowMs + Math.max(latencyMs, (ahead + 1) / ratePerMs);
    }
}
