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
 *   <li>速率：实测并行度（这段忙碌时间里平均几个在途，按 LoadMeter 的读数）÷ 延迟，不小于 1/延迟。
 *       11-09（codex-audit A15）：原来用 1/平均延迟，延迟里含 worker 上的排队，worker 几个 tile 并行做时
 *       （4 个一批 4 秒做完 ≈ 1 个/秒）会被估成 0.25 个/秒，前方任务全被当成做不完剪掉。
 *       11-09（正式服 hopeless 398、用户 M5 inFlight 0 而队里 37～40 个）：后来按完成间隔（个数 − 1 除以首尾间隔），
 *       donor 等派活的空档也算进去，速率被低估 → 剪枝、canMeet 把能做完的判来不及 → 派得更少 → 空档更多。
 *       按忙碌时间算，空档不算。11-09（codex-audit 第十五节）：按忙碌时间的完成数还会在批次边界估偏，
 *       改成实测并行度 ÷ 延迟（Little 定律）。
 *   <li>能不能接：手上已有 inFlight 个、实测能同时做 lanes 个：不到 lanes 个的马上开始，延迟后做完；
 *       否则按 now + max(延迟, (inFlight + 1) / 速率) 估，不晚于 deadline 才派。
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

    /** 一个 tile 做完了：延迟（派出到收到）和收到的时刻（毫秒时间戳）。没有在途表读数。 */
    synchronized void record(int worker, long durationMs, long completedAtMs) {
        record(worker, durationMs, completedAtMs, -1, -1);
    }

    /**
     * 同上，带收到时在途表（{@link LoadMeter}）的读数：手上有活的累计时间、在途数 × 时间的累计。
     * 11-09（codex-audit 第十五节）：速率按这两个量算实测并行度，不按完成间隔（空档、批次边界都会估偏）。
     */
    synchronized void record(int worker, long durationMs, long completedAtMs, long busyMs, long loadMs) {
        Deque<long[]> d = samples.computeIfAbsent(worker, k -> new ArrayDeque<>());
        d.addLast(new long[] {durationMs, completedAtMs, busyMs, loadMs});
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

    /**
     * 实测并行度：最近 WINDOW 个结果里，第一个到最后一个收到之间，手上有活的时间里平均有几个在途（在途表两次读数之差相除）。
     * 这一段比一个延迟还短（同一批几乎同时到）时用连上以来的累计；没有在途表读数时按 1（一个一个做，最保守）。
     * 还没有样本时按 MAX_CONCURRENCY（冷启动探路，codex-audit F5/G4）。
     */
    synchronized double parallelism(int worker) {
        Deque<long[]> d = samples.get(worker);
        if (d == null || d.isEmpty()) {
            return MAX_CONCURRENCY;
        }
        long[] first = null, last = null;
        for (long[] s : d) {
            if (s[2] >= 0) {
                if (first == null) {
                    first = s;
                }
                last = s;
            }
        }
        if (last == null) {
            return 1;
        }
        long db = last[2] - first[2], dl = last[3] - first[3];
        if (db >= Math.max(1, latencyMs(worker)) && dl >= 0) {
            return Math.max(1, dl / (double) db);
        }
        return last[2] > 0 ? Math.max(1, last[3] / (double) last[2]) : 1;
    }

    /** 实测能同时做几个（并行度取整，至少 1）：前面不到这么多个时，新给的马上开始。 */
    synchronized int lanes(int worker) {
        Deque<long[]> d = samples.get(worker);
        if (d == null || d.isEmpty()) {
            return MAX_CONCURRENCY;
        }
        return (int) Math.max(1, Math.round(parallelism(worker)));
    }

    /** 速率（个/毫秒）：实测并行度 ÷ 延迟（Little 定律），不小于 1/延迟。 */
    synchronized double ratePerMs(int worker) {
        Deque<long[]> d = samples.get(worker);
        if (d == null || d.isEmpty()) {
            // 冷启动（codex-audit G4）：假设能同时做 MAX_CONCURRENCY 个，派出去探路的不被剪掉
            return MAX_CONCURRENCY / (double) DEFAULT_MS;
        }
        return parallelism(worker) / Math.max(1, latencyMs(worker));
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
        return predictFinish(nowMs, latencyMs(worker), ratePerMs(worker), inFlight, lanes(worker)) <= deadlineMs;
    }

    /**
     * 派发和剪枝共用的完成预测（codex-audit F2）：前面还有 ahead 个（在做的加排在前面的），
     * 这个在 now + max(延迟, (ahead + 1) / 速率) 做完。速率是个/毫秒。
     */
    static double predictFinish(long nowMs, long latencyMs, double ratePerMs, double ahead) {
        return predictFinish(nowMs, latencyMs, ratePerMs, ahead, 1);
    }

    /**
     * 同上，实测能同时做 lanes 个：前面不到 lanes 个时马上开始，now + 延迟做完（11-09 正式服 hopeless 398）。
     * lanes 是实测的并行度，不是租约名额（codex-audit 第十五节 V2：一个一个做的 donor 有 4 个空名额也做不完 4 个）。
     */
    static double predictFinish(long nowMs, long latencyMs, double ratePerMs, double ahead, int lanes) {
        if (ahead < lanes) {
            return nowMs + latencyMs;
        }
        return nowMs + Math.max(latencyMs, (ahead + 1) / ratePerMs);
    }
}
