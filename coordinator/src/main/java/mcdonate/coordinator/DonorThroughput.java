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
 *   <li>速率：最近 WINDOW 个结果里，第一个到最后一个完成之间完成了 n − 1 个，除以这一段里手上有活的时间
 *       （租期 [派出, 收到] 并起来、截到这一段），不小于 1/延迟。
 *       11-09（codex-audit A15）：原来用 1/平均延迟，延迟里含 worker 上的排队，worker 几个 tile 并行做时
 *       （4 个一批 4 秒做完 ≈ 1 个/秒）会被估成 0.25 个/秒，前方任务全被当成做不完剪掉。
 *       11-09（正式服 hopeless 398、用户 M5 inFlight 0 而队里 37～40 个）：后来按完成间隔（个数 − 1 除以首尾间隔），
 *       donor 等派活的空档也算进去，速率被低估 → 剪枝、canMeet 把能做完的判来不及 → 派得更少 → 空档更多。
 *       按忙碌时间算，空档不算。
 *   <li>能不能接：手上已有 inFlight 个、名额 slots 个：还有空名额的马上开始，延迟后做完；
 *       名额满了的按 now + max(延迟, (inFlight + 1) / 速率) 估，不晚于 deadline 才派。
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

    /** 速率（个/毫秒）：个数除以忙碌时间，不小于 1/延迟。 */
    synchronized double ratePerMs(int worker) {
        Deque<long[]> d = samples.get(worker);
        if (d == null || d.isEmpty()) {
            // 冷启动（codex-audit G4）：假设能同时做 MAX_CONCURRENCY 个，派出去探路的不被剪掉
            return MAX_CONCURRENCY / (double) DEFAULT_MS;
        }
        long lat = Math.max(1, latencyMs(worker));
        double floor = 1.0 / lat;
        long[] at = d.stream().mapToLong(s -> s[1]).filter(t -> t != Long.MIN_VALUE).sorted().toArray();
        if (at.length == 0) {
            return floor;
        }
        // 第一个到最后一个完成之间，手上有活的时间（租期并起来、截到这一段）：完成了 n − 1 个。空档不算。
        // 不截的话，流水线做（手上一直 4 个、每秒完成一个）会把第一个的整段租期也算进去，偏低
        long clipped = covered(worker, at[0], at[at.length - 1]);
        if (clipped >= lat) {
            return Math.max(floor, (at.length - 1) / (double) clipped);
        }
        // 这一段比一个租期还短（同一批几乎同时到，codex-audit F4）：按个数 ÷ 整个忙碌时间，不会估成几十个/秒
        long busy = busyMs(worker);
        return busy <= 0 ? floor : Math.max(floor, at.length / (double) busy);
    }

    /** 租期并起来以后落在 [from, to] 里的长度（毫秒）。 */
    private long covered(int worker, long from, long to) {
        long total = 0, start = Long.MIN_VALUE, end = Long.MIN_VALUE;
        for (long[] a : intervals(worker)) {
            long s0 = Math.max(a[0], from), e0 = Math.min(a[1], to);
            if (e0 <= s0) {
                continue;
            }
            if (s0 > end) {
                total += end - start;
                start = s0;
                end = e0;
            } else {
                end = Math.max(end, e0);
            }
        }
        return start == Long.MIN_VALUE ? 0 : total + end - start;
    }

    /** 最近 WINDOW 个结果的租期 [收到 − 延迟, 收到]，按开始排好。没有收到时刻的不算。 */
    private long[][] intervals(int worker) {
        Deque<long[]> d = samples.get(worker);
        if (d == null) {
            return new long[0][];
        }
        return d.stream().filter(s -> s[1] != Long.MIN_VALUE).map(s -> new long[] {s[1] - s[0], s[1]})
                .sorted(java.util.Comparator.comparingLong(a -> a[0])).toArray(long[][]::new);
    }

    /** 最近 WINDOW 个结果的租期 [收到 − 延迟, 收到] 并起来的长度（毫秒）：donor 手上有活的时间。没有收到时刻的不算。 */
    synchronized long busyMs(int worker) {
        return covered(worker, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    /** 能力估计（个/毫秒，/donor status 用）：实测速率和"名额都用上时"（名额 ÷ 延迟）里大的。 */
    synchronized double capacityPerMs(int worker, int slots) {
        return Math.max(ratePerMs(worker), slots / (double) Math.max(1, latencyMs(worker)));
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
        return canMeet(worker, inFlight, 1, nowMs, deadlineMs);
    }

    /** 同上，这个 worker 有 slots 个名额（同时能领几个）：还有空名额时马上开始。 */
    synchronized boolean canMeet(int worker, int inFlight, int slots, long nowMs, long deadlineMs) {
        if (deadlineMs == Long.MAX_VALUE) {
            return true;
        }
        Deque<long[]> d = samples.get(worker);
        if (d == null || d.isEmpty()) {
            // 冷启动：还没有样本，按默认延迟估一个、不乘在途数（codex-audit F5：原来 3/6/9/12 秒，少派就测不出并行）
            return nowMs + DEFAULT_MS <= deadlineMs;
        }
        return predictFinish(nowMs, latencyMs(worker), ratePerMs(worker), inFlight, slots) <= deadlineMs;
    }

    /**
     * 派发和剪枝共用的完成预测（codex-audit F2）：前面还有 ahead 个（在做的加排在前面的），
     * 这个在 now + max(延迟, (ahead + 1) / 速率) 做完。速率是个/毫秒。
     */
    static double predictFinish(long nowMs, long latencyMs, double ratePerMs, double ahead) {
        return predictFinish(nowMs, latencyMs, ratePerMs, ahead, 1);
    }

    /** 同上，有 slots 个名额：前面不到 slots 个（还有空名额）时马上开始，now + 延迟做完（11-09 正式服 hopeless 398）。 */
    static double predictFinish(long nowMs, long latencyMs, double ratePerMs, double ahead, int slots) {
        if (ahead < slots) {
            return nowMs + latencyMs;
        }
        return nowMs + Math.max(latencyMs, (ahead + 1) / ratePerMs);
    }
}
