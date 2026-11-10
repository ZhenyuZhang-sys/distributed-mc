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
        record(worker, durationMs, completedAtMs, busyMs, loadMs, -1, false);
    }

    /** 同上，再带派出时这个 worker 手上一共几个（含这个，不知道是 -1）：往上探 lanes 用。 */
    synchronized void record(int worker, long durationMs, long completedAtMs, long busyMs, long loadMs, int inFlightAtLease) {
        record(worker, durationMs, completedAtMs, busyMs, loadMs, inFlightAtLease, false);
    }

    /**
     * 同上，background：这是加速模式的后台 tile。它照样算进实测并行度和速率（是真做了的活），但它的延迟不拿来往上探、回退、
     * 估"单独做一个要多久"和"马上开始的延迟"（11-10 kexu k4：后台 tile 离得远、单独做，比前方相邻的贵一倍，慢样本把 lanes 退到 1）。
     */
    synchronized void record(int worker, long durationMs, long completedAtMs, long busyMs, long loadMs, int inFlightAtLease,
            boolean background) {
        Deque<long[]> d = samples.computeIfAbsent(worker, k -> new ArrayDeque<>());
        d.addLast(new long[] {durationMs, completedAtMs, busyMs, loadMs, inFlightAtLease, background ? 1 : 0});
        while (d.size() > WINDOW) {
            d.pollFirst();
        }
        if (inFlightAtLease > 0 && !background) {
            probe(worker, durationMs, inFlightAtLease, completedAtMs);
        }
    }

    /**
     * 往上探（调度 11-09）：实测并行度只是"我们派了多少"，估低了只派 1～2 个，测出来也就 1～2，会自我强化。
     * 每个 worker 记一个探路的 lanes：在 lanes 个都占着时做完的，延迟不超过单独做时的 1.2 倍，连续 3 个就 +1（最多 MAX_CONCURRENCY）；
     * 超过 1.5 倍就退到"派出时手上的个数 − 1"（不比现在高），之后 PROBE_HOLD_MS（20 秒）不再往上探（一个一个做的 donor 不来回试）。
     * "明显慢"要最近 6 个一起做的里有 2 个才算（一个贵的 tile 不算，codex-audit 第十八节 X2：也不能被中间一个快的清零）。
     */
    static final double PROBE_OK = 1.2, PROBE_BAD = 1.5;
    static final int PROBE_GOOD = 3, PROBE_SLOW = 2, PROBE_WINDOW = 6;
    /**
     * 回退以后多久不再往上探（毫秒，按时间不按结果数：codex-audit 第十八节，延迟翻倍时 30 个结果要等几十秒）。
     * 一个一个做的 donor 不来回试。
     */
    static final long PROBE_HOLD_MS = Config.getLong("probeHoldMs", 20_000);

    /** worker → {lanes, 连续好的个数, 还要等几个结果才能再往上探}。 */
    private final Map<Integer, int[]> probes = new HashMap<>();

    private void probe(int worker, long durationMs, int k, long completedAtMs) {
        if (k == 1) {
            // 单独做的：更新"单独做一个要多久"（窗口里单独做的那些的中位数，留着给后面没有单独样本的时候用）
            long[] solo = samples.get(worker).stream().filter(s -> s[4] == 1 && s[5] == 0).mapToLong(s -> s[0]).sorted().toArray();
            soloMs.put(worker, Math.max(1, solo[solo.length / 2]));
        }
        // {lanes, 连续好的个数}
        int[] st = probes.computeIfAbsent(worker, w -> new int[] {(int) Math.max(1, Math.round(parallelism(w))), 0});
        long base = soloLatencyMs(worker);
        if (k >= 2) {
            // 和别的一起做的：最近 PROBE_WINDOW 个里有 PROBE_SLOW 个明显慢（> 1.5 倍单独做的）就退，退到派出时手上的个数 − 1（只往下退）。
            // codex-audit 第十六节：不管当时占没占满 lanes 都算（冷启动一次派 4 个、一个一个做的 donor）。
            // 11-10：一个贵的 tile 不算（要 2 个）。codex-audit 第十八节 X2：原来"连着两个"、中间夹一个快的就清零，
            // 真 2 并行的 donor 在 lanes 3 时慢快交替，永远退不下来、一半迟到；改成窗口里数
            Deque<Boolean> win = slowWindows.computeIfAbsent(worker, w -> new ArrayDeque<>());
            boolean slow = durationMs > PROBE_BAD * base;
            win.addLast(slow);
            while (win.size() > PROBE_WINDOW) {
                win.pollFirst();
            }
            if (slow && win.stream().filter(x -> x).count() >= PROBE_SLOW) {
                st[0] = Math.max(1, Math.min(st[0], k - 1));
                st[1] = 0;
                win.clear();
                holdUntil.put(worker, completedAtMs + PROBE_HOLD_MS);
                return;
            }
            if (slow) {
                st[1] = 0;
                return;
            }
        }
        if (k < st[0]) {
            return; // 没占满 lanes：看不出多一个行不行
        }
        if (durationMs <= PROBE_OK * base) {
            if (++st[1] >= PROBE_GOOD && completedAtMs >= holdUntil.getOrDefault(worker, Long.MIN_VALUE)) {
                st[0] = Math.min(ceilings.getOrDefault(worker, MAX_CONCURRENCY), st[0] + 1);
                st[1] = 0;
            }
        } else {
            st[1] = 0;
        }
    }

    /** worker → 最近几个"和别的一起做"的前方样本慢不慢。 */
    private final Map<Integer, Deque<Boolean>> slowWindows = new HashMap<>();
    /** worker → 回退以后到什么时候（毫秒时间戳）之前不再往上探。 */
    private final Map<Integer, Long> holdUntil = new HashMap<>();

    /** worker → lanes 往上探的上限（默认 MAX_CONCURRENCY；开了加速的按它的在途上限，11-09）。 */
    private final Map<Integer, Integer> ceilings = new HashMap<>();

    /** 设 lanes 往上探的上限（CoordinatorServer 派活时按加速模式的在途上限设）。 */
    synchronized void setCeiling(int worker, int ceiling) {
        ceilings.put(worker, Math.max(1, ceiling));
        int[] st = probes.get(worker);
        if (st != null && st[0] > ceiling) {
            st[0] = Math.max(1, ceiling); // 关了加速：上限变小，lanes 跟着压下来
        }
    }

    /** worker → 单独做一个要多久（最近一次有单独做的样本时的中位数）。 */
    private final Map<Integer, Long> soloMs = new HashMap<>();

    /**
     * 马上能开始的做完要多久：窗口里派出时手上不超过 lanes 个（没在 worker 上排队）的那些的延迟中位数；
     * 没有这样的（没记派出时手上几个）就用全部的中位数。往上探时排队的那几个延迟很长，不能拿来估空着的 donor（会把紧任务全拒掉）。
     */
    synchronized long laneLatencyMs(int worker) {
        Deque<long[]> d = samples.get(worker);
        if (d == null || d.isEmpty()) {
            return DEFAULT_MS;
        }
        if (!probes.containsKey(worker)) {
            // 没记派出时手上几个：按前方样本的中位数；只有后台的样本时按默认（X1 补充：不拿后台的补）
            long[] fg = d.stream().filter(s -> s[5] == 0).mapToLong(s -> s[0]).sorted().toArray();
            return fg.length == 0 ? DEFAULT_MS : fg[fg.length / 2];
        }
        // 派出时手上不超过 lanes 个、也不比单独做慢一半以上的（慢一半以上的是在 worker 上排了队，codex-audit 第十六节）
        int lanes = lanes(worker);
        long cap = (long) (PROBE_BAD * soloLatencyMs(worker));
        long[] v = d.stream().filter(s -> s[4] > 0 && s[4] <= lanes && s[0] <= cap && s[5] == 0).mapToLong(s -> s[0]).sorted().toArray();
        return v.length == 0 ? soloLatencyMs(worker) : v[v.length / 2];
    }

    /** 单独做一个要多久：最近一次有单独做的样本时的中位数；从来没有就取窗口里最短的。 */
    private long soloLatencyMs(int worker) {
        Long solo = soloMs.get(worker);
        if (solo != null) {
            return solo;
        }
        Deque<long[]> d = samples.get(worker);
        // 从来没有单独做的前方样本：取前方样本里最短的，没有前方样本按默认（X1 补充：不拿后台的补，100 ms 的缓存命中会让正常的都像慢了）
        return d == null ? DEFAULT_MS : Math.max(1, d.stream().filter(s -> s[5] == 0).mapToLong(s -> s[0]).min().orElse(DEFAULT_MS));
    }

    /** worker 断开：忘掉它的记录（重连后是新的编号）。 */
    synchronized void forget(int worker) {
        samples.remove(worker);
        probes.remove(worker);
        ceilings.remove(worker);
        soloMs.remove(worker);
        slowWindows.remove(worker);
        holdUntil.remove(worker);
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

    /**
     * 能同时做几个：前面不到这么多个时，新给的马上开始。有往上探的记录时按它（{@link #probe}），
     * 否则按实测并行度取整（至少 1）；冷启动按 MAX_CONCURRENCY。
     */
    synchronized int lanes(int worker) {
        Deque<long[]> d = samples.get(worker);
        if (d == null || d.isEmpty()) {
            return MAX_CONCURRENCY;
        }
        int[] st = probes.get(worker);
        if (st != null) {
            return st[0];
        }
        if (d.stream().allMatch(s -> s[5] == 1)) {
            // codex-audit 第十八节 X1：只做过后台（没有前方的样本）：照冷启动。按后台的低在途算会把 lanes 算成 1、后台半额变 0、再也不派
            return MAX_CONCURRENCY;
        }
        return (int) Math.max(1, Math.round(parallelism(worker)));
    }

    /**
     * 速率（个/毫秒）：实测并行度 ÷ 平均延迟（Little 定律：吞吐 = 平均在途 ÷ 平均停留时间；要用平均不用中位数，
     * 在 worker 上排队的那几个延迟长，中位数会把它们略掉、高估速率），不小于 1/延迟中位数。
     */
    synchronized double ratePerMs(int worker) {
        return rate(worker);
    }

    /**
     * 派活的在途上限：租约名额（slots）和 lanes 里小的（11-10 kexu mc8：lanes 退到 1 了，宽截止的任务照样派到 8 个在途，
     * 一个一个做的 donor 延迟涨到 6.8 秒，后来的紧任务都排在它们后面）。往上探照样能探：占满 lanes 时延迟没变长 lanes 就 +1。
     */
    synchronized int inFlightCap(int worker, int slots) {
        return Math.max(1, Math.min(slots, lanes(worker)));
    }

    private double rate(int worker) {
        Deque<long[]> d = samples.get(worker);
        if (d == null || d.isEmpty()) {
            // 冷启动（codex-audit G4）：假设能同时做 MAX_CONCURRENCY 个，派出去探路的不被剪掉
            return MAX_CONCURRENCY / (double) DEFAULT_MS;
        }
        return Math.max(1.0 / Math.max(1, latencyMs(worker)), parallelism(worker) / Math.max(1, averageMs(worker)));
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
        return predictFinish(nowMs, laneLatencyMs(worker), ratePerMs(worker), inFlight, lanes(worker)) <= deadlineMs;
    }

    /**
     * 派发和剪枝共用的完成预测（codex-audit F2）：前面还有 ahead 个（在做的加排在前面的），一个一个做时
     * 在 now + 延迟 + ahead / 速率 做完。速率是个/毫秒。
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
        // 要排队：等前面空出一个（按速率），再做一个延迟。原来 max(延迟, (前面 + 1) ÷ 速率) 把完成当成均匀的流，
        // lanes 个同时在做时，下一个其实要等一个做完再做满一个延迟（往上探的模拟里 2 个并行的 donor 第 3 个次次迟到）
        return nowMs + latencyMs + (ahead - lanes + 1) / ratePerMs;
    }
}
