package mcdonate.coordinator;

import mcdonate.common.Config;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 第 7 步 7.3：按实测算力分配。纯逻辑，时间由调用方传进来。
 *
 * <p>每个 donor 记最近 10 个 tile 的完成耗时（派出到结果回来），取平均；不信 donor 自己报的。
 * <ul>
 *   <li>并发数：一个 donor 同时能领几个任务 = 租约内能做完的个数，至少 1、最多 4。
 *   <li>能不能接：按它现在手上的任务排队算，这个任务预计完成时间不晚于 deadline 才派给它。
 *       慢 donor 因此只拿到 deadline 宽裕的任务。
 * </ul>
 * 没有记录的 donor 按 {@link #DEFAULT_MS} 估。
 */
final class DonorThroughput {
    static final int WINDOW = Config.getInt("throughputWindow", 10);
    /** 还没有记录时按 3 秒估（第 3 步实测 4 核 1.8～3.1 秒）。 */
    static final long DEFAULT_MS = Config.getLong("tileDefaultMs", 3_000);
    static final int MAX_CONCURRENCY = Config.getInt("maxConcurrency", 4);

    private final Map<Integer, Deque<Long>> samples = new HashMap<>();

    /** 一个 tile 做完了：从派出到结果回来用了多少毫秒。 */
    synchronized void record(int worker, long durationMs) {
        Deque<Long> d = samples.computeIfAbsent(worker, k -> new ArrayDeque<>());
        d.addLast(durationMs);
        while (d.size() > WINDOW) {
            d.pollFirst();
        }
    }

    /** worker 断开：忘掉它的记录（重连后是新的编号）。 */
    synchronized void forget(int worker) {
        samples.remove(worker);
    }

    /** 平均耗时（毫秒）。 */
    synchronized long averageMs(int worker) {
        Deque<Long> d = samples.get(worker);
        if (d == null || d.isEmpty()) {
            return DEFAULT_MS;
        }
        long sum = 0;
        for (long v : d) {
            sum += v;
        }
        return sum / d.size();
    }

    /** 同时能领几个任务：租约内能做完的个数，至少 1、最多 MAX_CONCURRENCY。 */
    synchronized int concurrency(int worker, long leaseMs) {
        long avg = Math.max(1, averageMs(worker));
        return (int) Math.max(1, Math.min(MAX_CONCURRENCY, leaseMs / avg));
    }

    /**
     * 这个任务能不能派给它：它手上已有 inFlight 个任务（一个接一个做），
     * 这个任务预计 now + 平均耗时 × (inFlight + 1) 做完，不晚于 deadline 才派。
     * deadline 是 Long.MAX_VALUE（不急）时总能派。
     */
    synchronized boolean canMeet(int worker, int inFlight, long nowMs, long deadlineMs) {
        if (deadlineMs == Long.MAX_VALUE) {
            return true;
        }
        long finish = nowMs + averageMs(worker) * (inFlight + 1L);
        return finish <= deadlineMs;
    }
}
