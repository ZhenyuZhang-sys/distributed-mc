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
        // 跨过回退的单独任务（回退之前派出、之后交回）：它的延迟分不清是回退前还是后的，不进基线（codex-audit 第二十一节）
        Long backoff = lastBackoffAt.get(worker);
        boolean straddle = inFlightAtLease == 1 && backoff != null && completedAtMs - durationMs < backoff && completedAtMs >= backoff;
        // 预热（11-10 codex-pipe：新 donor 冷启动第一批 16 秒，延迟估成 16 秒、slots 1，120 秒只交回 1 个）：
        // 第一个结果回来之前派出的那批。照样算进并行度，但不进任何延迟估计、不拿来往上探或回退。只看带在途表读数的（生产路径都带）
        boolean warmup = false;
        if (busyMs >= 0 && completedAtMs != Long.MIN_VALUE) {
            Long first = firstDoneAt.putIfAbsent(worker, completedAtMs);
            warmup = completedAtMs - durationMs < (first == null ? completedAtMs : first);
        }
        long[] sample = {durationMs, completedAtMs, busyMs, loadMs, inFlightAtLease, background ? 1 : 0, straddle ? 1 : 0, warmup ? 1 : 0};
        d.addLast(sample);
        while (d.size() > WINDOW) {
            d.pollFirst();
        }
        if (!background && !warmup) {
            // 前方的另存一个窗口（codex-audit 第 162 项：唯一的前方样本被 10 个后台结果挤出窗口，前方延迟退回默认 3 秒）
            Deque<long[]> f = frontSamples.computeIfAbsent(worker, k -> new ArrayDeque<>());
            f.addLast(sample);
            while (f.size() > WINDOW) {
                f.pollFirst();
            }
        }
        if (inFlightAtLease > 0 && !background && !straddle) {
            probe(worker, durationMs, inFlightAtLease, completedAtMs, warmup);
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

    private void probe(int worker, long durationMs, int k, long completedAtMs, boolean warmup) {
        Long cal = calibrateUntil.get(worker);
        if (cal != null && completedAtMs > cal) {
            calibrateUntil.remove(worker); // 量了太久没有单独做的前方任务（只有后台、没人飞）：不量了
            calibrateFrom.remove(worker);
            calibrateStart.remove(worker);
            cal = null;
        }
        if (cal != null && k == 1 && completedAtMs - durationMs >= calibrateStart.getOrDefault(worker, Long.MAX_VALUE)) {
            // 退了以后新派出、单独做的这一个（codex-audit 第二十节 Z2：回退之前就派出去的不算）：和原来单独做的基线比
            long old = soloLatencyMs(worker);
            calibrateFrom.remove(worker);
            calibrateUntil.remove(worker);
            calibrateStart.remove(worker);
            if (durationMs > PROBE_BAD * old) {
                // 单独做也慢了：是整台机器变慢（用户开了游戏），不是并行多了。基线换成新的，旧的前方样本作废，lanes 恢复
                soloMs.put(worker, Math.max(1, durationMs));
                Deque<long[]> f = frontSamples.get(worker);
                if (f != null) {
                    long[] last = f.peekLast();
                    f.clear();
                    if (last != null) {
                        f.addLast(last);
                    }
                }
                // Z3：lanes 不直接恢复到退之前的（真的少了并行、同时也变慢时会误恢复），按新的基线从现在的 lanes 往上探
                int[] st = probes.get(worker);
                if (st != null) {
                    st[1] = 0;
                }
                Deque<Boolean> win = slowWindows.get(worker);
                if (win != null) {
                    win.clear();
                }
                holdUntil.remove(worker);
                levelLatencies.remove(worker); // 各档的旧延迟也作废
                return;
            }
            // 单独做照样快：慢是并行多了造成的，退得对；照常往下更新基线
        }
        if (k == 1 && !warmup) {
            // 单独做的：更新"单独做一个要多久"（窗口里单独做的那些的中位数，留着给后面没有单独样本的时候用）
            long[] solo = front(worker).stream().filter(s -> s[4] == 1 && s[6] == 0).mapToLong(s -> s[0]).sorted().toArray();
            if (solo.length > 0) {
                soloMs.put(worker, Math.max(1, solo[solo.length / 2]));
            }
        }
        // {lanes, 连续好的个数}
        int[] st = probes.computeIfAbsent(worker, w -> new int[] {(int) Math.max(1, Math.round(parallelism(w))), 0});
        // 预热那批（冷启动第一批，绝对延迟不可信）：只在这一批里互相比（同一批的 k=1 当基线、同一批的各档），
        // 一批都一样慢（JIT 没热）不会退，越多越慢（真一个一个做）照样退；不进正常的基线和各档延迟
        double[] warm = warmup ? warmLevels.computeIfAbsent(worker, w -> new double[MAX_LEVEL + 1]) : null;
        long base = !warmup ? soloLatencyMs(worker) : warm[1] > 0 ? (long) warm[1] : k == 1 ? durationMs : soloLatencyMs(worker);
        // 11-10：按"多这一条速率涨不涨"判断（调度：用户的 M5 一起做时每个约 0.8 秒、单独约 0.7 秒，速率在涨，不能退）。
        // 手上 k 个时速率约 k ÷ 延迟；和手上 k − 1 个时比：k 个的延迟超过 k/(k−1) × (k − 1 个时的延迟) 就是多这一条不多出活。
        // 不知道 k − 1 个时的延迟（还没在那个档做过）时照旧比单独做的
        double prev = !warmup ? levelLatency(worker, k - 1) : k >= 2 ? warm[Math.min(k - 1, MAX_LEVEL)] : 0;
        double ratio = k / (double) Math.max(1, k - 1);
        boolean slow = prev > 0 ? durationMs > ratio * prev * PROBE_MARGINAL_SLOW : durationMs > PROBE_BAD * base;
        boolean good = durationMs <= PROBE_OK * base || (prev > 0 && durationMs <= ratio * prev / PROBE_MARGINAL_GAIN);
        double[] lat = warmup ? warm : levelLatencies.computeIfAbsent(worker, w -> new double[MAX_LEVEL + 1]);
        int lv = Math.min(k, MAX_LEVEL);
        lat[lv] = lat[lv] == 0 ? durationMs : lat[lv] + LEVEL_ALPHA * (durationMs - lat[lv]);
        if (k >= 2) {
            // 和别的一起做的：最近 PROBE_WINDOW 个里有 PROBE_SLOW 个"多这一条不多出活"就退，退到派出时手上的个数 − 1（只往下退）。
            // codex-audit 第十六节：不管当时占没占满 lanes 都算（冷启动一次派 4 个、一个一个做的 donor）。
            // 11-10：一个贵的 tile 不算（要 2 个）。codex-audit 第十八节 X2：原来"连着两个"、中间夹一个快的就清零，
            // 真 2 并行的 donor 在 lanes 3 时慢快交替，永远退不下来、一半迟到；改成窗口里数
            Deque<Boolean> win = slowWindows.computeIfAbsent(worker, w -> new ArrayDeque<>());
            win.addLast(slow);
            while (win.size() > PROBE_WINDOW) {
                win.pollFirst();
            }
            if (slow && win.stream().filter(x -> x).count() >= PROBE_SLOW) {
                int from = st[0];
                st[0] = Math.max(1, Math.min(st[0], k - 1));
                lastBackoffAt.put(worker, completedAtMs);
                if (st[0] < from && !calibrateFrom.containsKey(worker)) {
                    // 11-10：退了先单独做一个量一下：整台机器变慢（并行没变）时基线是旧的，会被误当成并行多了变慢
                    calibrateFrom.put(worker, from);
                    calibrateUntil.put(worker, completedAtMs + CALIBRATE_MS);
                    calibrateStart.put(worker, completedAtMs);
                }
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
        if (good) {
            if (++st[1] >= PROBE_GOOD && completedAtMs >= holdUntil.getOrDefault(worker, Long.MIN_VALUE)) {
                st[0] = Math.min(ceilings.getOrDefault(worker, MAX_CONCURRENCY), st[0] + 1);
                st[1] = 0;
            }
        } else {
            st[1] = 0;
        }
    }

    /** 退了以后单独量一次最多等多久（毫秒）。 */
    static final long CALIBRATE_MS = Config.getLong("probeCalibrateMs", 10_000);
    /** worker → 退之前的 lanes（在单独量的时候）。 */
    private final Map<Integer, Integer> calibrateFrom = new HashMap<>();
    /** worker → 单独量到什么时候为止。 */
    private final Map<Integer, Long> calibrateUntil = new HashMap<>();

    /** worker → 从什么时候开始单独量（这之后派出的单独任务才算测量，Z2）。 */
    private final Map<Integer, Long> calibrateStart = new HashMap<>();

    /** 单独量到期了就结束（codex-audit 第二十节 Z1：原来只在有前方结果回来时检查，没有结果时一直只派 1 个）。派活时调。 */
    synchronized void expireCalibration(int worker, long nowMs) {
        Long until = calibrateUntil.get(worker);
        if (until != null && nowMs > until) {
            calibrateUntil.remove(worker);
            calibrateFrom.remove(worker);
            calibrateStart.remove(worker);
        }
    }

    /** 这个 worker 是不是在退了以后单独量一次（这时只派 1 个，也不派后台）。 */
    synchronized boolean calibrating(int worker) {
        return calibrateFrom.containsKey(worker);
    }

    /** 判断"多这一条不多出活"时留的余量：超过 k/(k−1) 倍的 0.95 就算（测量有噪声，宁可退）。 */
    static final double PROBE_MARGINAL_SLOW = 0.95;
    /** 往上探：多这一条速率至少涨一成才算还在涨。 */
    static final double PROBE_MARGINAL_GAIN = 1.1;
    static final int MAX_LEVEL = 16;
    static final double LEVEL_ALPHA = 0.3;
    /** worker → 派出时手上 k 个的前方任务的平均延迟（指数平均；下标 k，0 是没有）。 */
    private final Map<Integer, double[]> levelLatencies = new HashMap<>();

    /** 手上 k 个时的延迟：k = 1 优先用单独做的基线；没做过这一档是 0。 */
    private double levelLatency(int worker, int k) {
        if (k < 1) {
            return 0;
        }
        if (k == 1 && soloMs.containsKey(worker)) {
            return soloMs.get(worker);
        }
        double[] lat = levelLatencies.get(worker);
        return lat == null ? 0 : lat[Math.min(k, MAX_LEVEL)];
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

    /** worker → 最近 WINDOW 个前方结果（后台的不占位置）：估前方延迟、单独做的基线、冷启动判断用。 */
    private final Map<Integer, Deque<long[]>> frontSamples = new HashMap<>();

    private Deque<long[]> front(int worker) {
        return frontSamples.getOrDefault(worker, new ArrayDeque<>());
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
        Deque<long[]> fd = front(worker);
        if (!probes.containsKey(worker)) {
            // 没记派出时手上几个：按前方样本的中位数；只有后台的样本时按默认（X1 补充：不拿后台的补）
            long[] fg = fd.stream().filter(s -> s[6] == 0).mapToLong(s -> s[0]).sorted().toArray();
            return fg.length == 0 ? DEFAULT_MS : fg[fg.length / 2];
        }
        // 派出时手上不超过 lanes 个、也不比单独做慢一半以上的（慢一半以上的是在 worker 上排了队，codex-audit 第十六节）
        int lanes = lanes(worker);
        long cap = (long) (PROBE_BAD * soloLatencyMs(worker));
        long[] v = fd.stream().filter(s -> s[4] > 0 && s[4] <= lanes && s[0] <= cap && s[6] == 0).mapToLong(s -> s[0]).sorted().toArray();
        return v.length == 0 ? soloLatencyMs(worker) : v[v.length / 2];
    }

    /** 单独做一个要多久：最近一次有单独做的样本时的中位数；从来没有就取窗口里最短的。 */
    private long soloLatencyMs(int worker) {
        Long solo = soloMs.get(worker);
        if (solo != null) {
            return solo;
        }
        // 从来没有单独做的前方样本：取前方样本里最短的，没有前方样本按默认（X1 补充：不拿后台的补，100 ms 的缓存命中会让正常的都像慢了）
        return Math.max(1, front(worker).stream().filter(s -> s[6] == 0).mapToLong(s -> s[0]).min().orElse(DEFAULT_MS));
    }

    /** worker 断开：忘掉它的记录（重连后是新的编号）。 */
    synchronized void forget(int worker) {
        samples.remove(worker);
        probes.remove(worker);
        ceilings.remove(worker);
        soloMs.remove(worker);
        slowWindows.remove(worker);
        frontSamples.remove(worker);
        calibrateFrom.remove(worker);
        calibrateUntil.remove(worker);
        calibrateStart.remove(worker);
        holdUntil.remove(worker);
        levelLatencies.remove(worker);
        idleRejectSince.remove(worker);
        idleRejects.remove(worker);
        explorations.remove(worker);
        lastBackoffAt.remove(worker);
        firstDoneAt.remove(worker);
        warmLevels.remove(worker);
    }

    /** 延迟的中位数（毫秒）。 */
    synchronized long latencyMs(int worker) {
        Deque<long[]> d = samples.get(worker);
        if (d == null || d.isEmpty()) {
            return DEFAULT_MS;
        }
        long[] v = d.stream().filter(s -> s[7] == 0).mapToLong(s -> s[0]).sorted().toArray();
        return v.length == 0 ? DEFAULT_MS : v[v.length / 2]; // 只有预热那批时按默认
    }

    /** 平均延迟（毫秒）；对冲、等 donor 用。 */
    synchronized long averageMs(int worker) {
        Deque<long[]> d = samples.get(worker);
        if (d == null || d.isEmpty()) {
            return DEFAULT_MS;
        }
        return (long) d.stream().filter(s -> s[7] == 0).mapToLong(s -> s[0]).average().orElse(DEFAULT_MS);
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
        if (front(worker).isEmpty()) {
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
        if (calibrating(worker)) {
            return 1;
        }
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
        boolean ok = predictFinish(nowMs, laneLatencyMs(worker), ratePerMs(worker), inFlight, lanes(worker)) <= deadlineMs;
        if (ok || inFlight > 0) {
            idleRejectSince.remove(worker);
            return ok;
        }
        // 空着却拒掉（codex-audit 第二十一节：CPU 平分冷启动学到的延迟太长，收紧截止以后空着也全拒、学不到新的）：记数；
        // 一直拒了 EXPLORE_MS 就放一个探路（单独做，刷新样本），可能迟到一个
        idleRejects.merge(worker, 1L, Long::sum);
        Long since = idleRejectSince.putIfAbsent(worker, nowMs);
        if (since != null && nowMs - since >= EXPLORE_MS) {
            idleRejectSince.remove(worker);
            explorations.merge(worker, 1L, Long::sum);
            return true;
        }
        return false;
    }

    /** worker → 预热那批里各档的延迟（只在这一批里比较用）。 */
    private final Map<Integer, double[]> warmLevels = new HashMap<>();

    /** worker → 第一个结果回来的时刻（在这之前派出的算预热）。 */
    private final Map<Integer, Long> firstDoneAt = new HashMap<>();

    /** 空着却全拒了这么久就放一个探路（毫秒）。 */
    static final long EXPLORE_MS = Config.getLong("probeExploreMs", 5_000);
    private final Map<Integer, Long> idleRejectSince = new HashMap<>();
    private final Map<Integer, Long> idleRejects = new HashMap<>();
    private final Map<Integer, Long> explorations = new HashMap<>();
    /** worker → 最近一次回退的时刻。 */
    private final Map<Integer, Long> lastBackoffAt = new HashMap<>();

    /** 空着却拒掉的次数（/donor status；"已派的不迟到"不等于需求都满足了）。 */
    synchronized long idleRejects(int worker) {
        return idleRejects.getOrDefault(worker, 0L);
    }

    synchronized long explorations(int worker) {
        return explorations.getOrDefault(worker, 0L);
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
