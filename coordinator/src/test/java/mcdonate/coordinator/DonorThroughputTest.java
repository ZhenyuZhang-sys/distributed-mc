package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 第 7 步 7.3：按实测算力分配。 */
class DonorThroughputTest {
    @Test
    void unknownDonorUsesDefault() {
        assertEquals(DonorThroughput.DEFAULT_MS, new DonorThroughput().averageMs(1));
    }

    @Test
    void averageOfLastTen() {
        DonorThroughput t = new DonorThroughput();
        for (int i = 0; i < 5; i++) {
            t.record(1, 10_000); // 早先很慢
        }
        for (int i = 0; i < 10; i++) {
            t.record(1, 2_000); // 最近 10 个都是 2 秒
        }
        assertEquals(2_000, t.averageMs(1));
    }

    @Test
    void concurrencyFromLease() {
        DonorThroughput t = new DonorThroughput();
        t.record(1, 2_000);
        assertEquals(4, t.concurrency(1, 30_000), "快的封顶 4");
        t.record(2, 20_000);
        assertEquals(1, t.concurrency(2, 30_000));
        t.record(3, 60_000);
        assertEquals(1, t.concurrency(3, 30_000), "至少 1");
        t.record(4, 10_000);
        assertEquals(3, t.concurrency(4, 30_000));
    }

    @Test
    void slowDonorOnlyGetsLooseDeadlines() {
        DonorThroughput t = new DonorThroughput();
        t.record(1, 2_000);   // 快
        t.record(2, 12_000);  // 慢（相当于 0.5 核）
        long now = 100_000;
        long tight = now + 5_000, loose = now + 60_000;
        assertTrue(t.canMeet(1, 0, now, tight));
        assertFalse(t.canMeet(2, 0, now, tight), "慢 donor 赶不上紧的");
        assertTrue(t.canMeet(2, 0, now, loose));
        assertFalse(t.canMeet(1, 2, now, tight), "快 donor 手上排了 2 个也赶不上");
        assertTrue(t.canMeet(2, 3, now, Long.MAX_VALUE), "不急的总能派");
    }

    @Test
    void forgetResets() {
        DonorThroughput t = new DonorThroughput();
        t.record(1, 9_000);
        t.forget(1);
        assertEquals(DonorThroughput.DEFAULT_MS, t.averageMs(1));
    }

    // ---- 11-09 codex-audit A15：并行后吞吐被低估（派出到收到含排队，canMeet 又乘在途数）----

    @Test
    void parallelDonorRateFromCompletions() {
        // 4 个一批：每个派出到收到 4 秒、每 100 ms 错开一个，一批接一批 → 手上一直约 4 个，约 1 个/秒，不是 0.25。
        // 11-09（codex-audit 第十五节 V1）：原来按完成间隔估成 1.6（窗口只看到半批，偏高），这里锁的是偏高的值；
        // 改成实测并行度（在途数对忙碌时间的平均）÷ 延迟
        DonorThroughput t = new DonorThroughput();
        List<long[]> iv = new ArrayList<>();
        for (int batch = 0; batch < 2; batch++) {
            for (int i = 0; i < 4; i++) {
                iv.add(new long[] {batch * 4_000 + i * 100L, 4_000 + batch * 4_000 + i * 100L});
            }
        }
        feed(t, iv);
        assertEquals(4_000, t.latencyMs(1), "延迟取中位数");
        double rate = t.ratePerMs(1) * 1000;
        assertTrue(rate > 0.85 && rate < 1.05, "约 1 个/秒，实际 " + rate);
        long now = 100_000;
        assertTrue(t.canMeet(1, 3, now, now + 6_000), "手上 3 个、实测能并行 3～4 个：4 秒左右做完");
    }

    @Test
    void singleSampleStillConservative() {
        // 只有一个样本：按 1/延迟估速率（和原来一样保守）
        DonorThroughput t = new DonorThroughput();
        t.record(1, 2_000, 50_000);
        assertEquals(0.5, t.ratePerMs(1) * 1000, 1e-9);
        assertFalse(t.canMeet(1, 2, 100_000, 105_000), "手上 2 个、一个一个做：6 秒");
    }

    @Test
    void coldStartAssumesParallel() {
        // codex-audit F5：还没有样本时按默认延迟估一个，但不乘在途数（原来 3/6/9/12 秒，少派就永远测不出并行）
        DonorThroughput t = new DonorThroughput();
        long now = 100_000;
        assertTrue(t.canMeet(1, 3, now, now + DonorThroughput.DEFAULT_MS));
        assertFalse(t.canMeet(1, 0, now, now + DonorThroughput.DEFAULT_MS - 1));
    }

    @Test
    void sameMillisecondBatchDoesNotExplodeRate() {
        // codex-audit F4：同一批几乎同时到（间隔 0～几毫秒），速率不能估成几十个/秒：没有在途记录时按 1/延迟（保守）
        DonorThroughput t = new DonorThroughput();
        for (int i = 0; i < 4; i++) {
            t.record(1, 4_000, 50_000 + i);
        }
        assertEquals(0.25, t.ratePerMs(1) * 1000, 1e-9);
        // 有在途记录：4 个一起做了 4 秒，1 个/秒
        DonorThroughput m = new DonorThroughput();
        feed(m, List.of(new long[] {46_000, 50_000}, new long[] {46_001, 50_001}, new long[] {46_002, 50_002}, new long[] {46_003, 50_003}));
        assertEquals(1.0, m.ratePerMs(1) * 1000, 0.01);
    }

    @Test
    void coldStartRateIsOptimisticForPruning() {
        // codex-audit G4：冷启动只改了 canMeet，剪枝还按 1/默认延迟（串行）算速率，派出去探路的 4 个里剪掉 3 个
        assertEquals(DonorThroughput.MAX_CONCURRENCY / (double) DonorThroughput.DEFAULT_MS, new DonorThroughput().ratePerMs(7), 1e-12);
    }

    // ---- 11-09 正式服（f19d087、用户 M5）：inFlight 0、队里 37～40 个，启动以来 hopeless 398 ----
    // 速率按最近 10 个的完成间隔算，donor 等派活的空档也算进去，速率被低估；剪枝和 canMeet 按低估的速率
    // 把能做完的判来不及 → 派得更少 → 空档更多 → 速率更低（distributed-mc-75 读代码发现的循环）。
    // codex-audit 第十五节审 0dfd6c9：V1 忙碌窗口和完成数不配（跨批次高估），V2 把租约名额当成计算并行。
    // 改成：在途数随时间记（LoadMeter），速率 = 窗口里的平均在途数（只算手上有活的时间）÷ 延迟。

    /** 按真实的租期喂：在途表按开始、结束事件走，每个完成时记下在途表的读数。 */
    static void feed(DonorThroughput t, List<long[]> intervals) {
        feed(t, intervals, Long.MAX_VALUE);
    }

    /** 同上，只喂到 until 为止（之后的开始、结束都还没发生：那时还在做的照样算在途）。 */
    static void feed(DonorThroughput t, List<long[]> intervals, long until) {
        LoadMeter meter = new LoadMeter();
        List<long[]> ev = new ArrayList<>();
        for (int i = 0; i < intervals.size(); i++) {
            ev.add(new long[] {intervals.get(i)[0], 1, i});
            ev.add(new long[] {intervals.get(i)[1], 0, i});   // 同一时刻先结束再开始
        }
        ev.sort((x, y) -> x[0] != y[0] ? Long.compare(x[0], y[0]) : Long.compare(x[1], y[1]));
        for (long[] e : ev) {
            if (e[0] > until) {
                break;
            }
            if (e[1] == 1) {
                meter.start(e[0]);
            } else {
                long[] r = meter.read(e[0]);
                long[] in = intervals.get((int) e[2]);
                t.record(1, in[1] - in[0], in[1], r[0], r[1]);
                meter.end(e[0]);
            }
        }
    }

    @Test
    void idleGapsDoNotLowerRate() {
        // 每批 2 个并行（700 ms，错开 50 ms），批与批之间 donor 空等 1.2 秒（等下一秒的派活）：忙的时候约 2.7 个/秒
        DonorThroughput t = new DonorThroughput();
        List<long[]> iv = new ArrayList<>();
        for (int k = 0; k < 5; k++) {
            long s0 = 10_000 + k * 1_900L;
            iv.add(new long[] {s0, s0 + 700});
            iv.add(new long[] {s0 + 50, s0 + 750});
        }
        feed(t, iv);
        double rate = t.ratePerMs(1) * 1000;
        assertTrue(rate > 2.4 && rate < 2.95, "空档不算进去：约 2.7 个/秒，实际 " + rate);
    }

    @Test
    void twoExactBatchesNotOverestimated() {
        // V1：第一批 4 个 [10000,14000] 一起交回，空等后第二批 4 个 [100000,104000]：1 个/秒（0dfd6c9 估成 1.75）
        DonorThroughput t = new DonorThroughput();
        List<long[]> iv = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            iv.add(new long[] {10_000, 14_000});
        }
        for (int i = 0; i < 4; i++) {
            iv.add(new long[] {100_000, 104_000});
        }
        feed(t, iv);
        assertEquals(1.0, t.ratePerMs(1) * 1000, 0.05);
    }

    @Test
    void fullRollingWindowNotOverestimated() {
        // V1：再来第三批，窗口 10 个截掉第一批的 2 个：还是 1 个/秒（0dfd6c9 估成 1.125）
        DonorThroughput t = new DonorThroughput();
        List<long[]> iv = new ArrayList<>();
        for (long s0 : new long[] {10_000, 100_000, 200_000}) {
            for (int i = 0; i < 4; i++) {
                iv.add(new long[] {s0, s0 + 4_000});
            }
        }
        feed(t, iv);
        assertEquals(1.0, t.ratePerMs(1) * 1000, 0.05);
    }

    @Test
    void steadyPipelineIsOnePerSecond() {
        // 手上一直 4 个、每秒交回一个、每个 4 秒：1 个/秒（窗口左边的预热不能把它压低）。看到第 20 个交回为止，后面的还在做
        DonorThroughput t = new DonorThroughput();
        List<long[]> iv = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            iv.add(new long[] {10_000 + i * 1_000L, 14_000 + i * 1_000L});
        }
        feed(t, iv, 33_000);
        assertEquals(1.0, t.ratePerMs(1) * 1000, 0.05);
        assertEquals(4, t.lanes(1));
    }

    @Test
    void measuredLanesStartNow() {
        // 实测能并行 4 个（延迟 700 ms）：手上 2 个时再给一个，马上开始，0.7 秒做完；手上 4 个才排队（约 0.9 秒）
        DonorThroughput t = new DonorThroughput();
        List<long[]> iv = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            for (int i = 0; i < 4; i++) {
                iv.add(new long[] {10_000 + k * 700L, 10_700 + k * 700L});
            }
        }
        feed(t, iv);
        assertEquals(4, t.lanes(1));
        long now = 100_000;
        assertTrue(t.canMeet(1, 2, now, now + 800));
        assertFalse(t.canMeet(1, 4, now, now + 800), "实测的并行都占满了才排队");
    }

    @Test
    void serialDonorWithSpareLeaseSlotsRejectsImpossibleDeadline() {
        // V2：一个一个做、每个 700 ms 的 donor（租约名额有 4 个，但那不是计算并行）：手上 2 个时第 3 个约 2.1 秒才回来
        DonorThroughput t = new DonorThroughput();
        List<long[]> iv = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            iv.add(new long[] {10_000 + i * 700L, 10_700 + i * 700L});
        }
        feed(t, iv);
        assertEquals(1.0 / 0.7, t.ratePerMs(1) * 1000, 0.05);
        assertTrue(t.lanes(1) <= 2, "实测 1 个，最多往上探 1 个：" + t.lanes(1));
        long now = 100_000;
        assertFalse(t.canMeet(1, 2, now, now + 1_000), "1 秒截止赶不上");
        assertTrue(t.canMeet(1, 0, now, now + 1_000), "空着的照样能接");
    }

    @Test
    void singleSampleIsOneLane() {
        // 只有一个样本：不按最大并发推断并行度（codex-audit V2）
        DonorThroughput t = new DonorThroughput();
        feed(t, List.of(new long[] {49_300, 50_000}));
        assertEquals(1, t.lanes(1));
        assertEquals(DonorThroughput.MAX_CONCURRENCY, new DonorThroughput().lanes(9), "冷启动：按最大并发探路（F5/G4）");
    }

    // ---- 往上探（调度 11-09）：实测并行度是"我们派了多少"，估低了只派 1～2 个，测出来也就 1～2，又是自我强化的循环 ----

    /**
     * 模拟 coordinator 和一个能同时做 real 个、每个 serviceMs 的 donor：队里全是紧任务（截止 = 现在 + deadlineMs），
     * 每次有结果就补派到 canMeet 不让为止（最多 maxConcurrency 个在途）。开始前先有 10 个一个一个做的历史（估成 1 个并行）。
     * 返回 {最后的 lanes, 迟到的个数, 总个数}。
     */
    static int[] simulate(int real, long serviceMs, long deadlineMs, long seconds) {
        return simulate(real, serviceMs, deadlineMs, seconds, true, 0, deadlineMs);
    }

    /**
     * 同上。history：开始前先有 10 个一个一个做的历史；false 是冷启动（没有样本，按 maxConcurrency 探路）。
     * 前 looseMs 毫秒截止用 looseDeadlineMs（冷启动按默认 3 秒估，太紧的派不出去），之后用 deadlineMs；迟到只数之后的。
     * 返回 {最后的 lanes, 迟到的个数, 总个数, 最后的 laneLatencyMs}。
     */
    static int[] simulate(int real, long serviceMs, long deadlineMs, long seconds, boolean history, long looseMs, long looseDeadlineMs) {
        return simulate(real, serviceMs, deadlineMs, seconds, history, looseMs, looseDeadlineMs, DonorThroughput.MAX_CONCURRENCY);
    }

    /** 同上，slots：租约名额（coordinator 按租约长度给的上限，maxConcurrency 8 时是 8）；返回值第 5 个是最长的延迟。 */
    static int[] simulate(int real, long serviceMs, long deadlineMs, long seconds, boolean history, long looseMs, long looseDeadlineMs,
            int slots) {
        return simulate(real, serviceMs, deadlineMs, seconds, history, looseMs, looseDeadlineMs, slots, real, Long.MAX_VALUE, null);
    }

    /**
     * 同上，跑到 changeAfterMs 以后 donor 真能同时做的个数变成 realAfter（用户开了游戏抢 CPU）。
     * lateAfter 不为 null 时，lateAfter[0] 记变化之后的迟到个数，lateAfter[1] 记变化之后的总个数。
     */
    static int[] simulate(int real, long serviceMs, long deadlineMs, long seconds, boolean history, long looseMs, long looseDeadlineMs,
            int slots, int realAfter, long changeAfterMs, int[] lateAfter) {
        DonorThroughput t = new DonorThroughput();
        LoadMeter meter = new LoadMeter();
        long now = 0;
        for (int i = 0; history && i < 10; i++) {     // 历史：一个一个做
            meter.start(now);
            now += serviceMs;
            long[] r = meter.read(now);
            t.record(1, serviceMs, now, r[0], r[1], 1);
            meter.end(now);
        }
        // 在途：{派出时刻, 开始做的时刻（排队的是 -1）, 截止, 派出时手上几个}
        List<long[]> work = new ArrayList<>();
        int late = 0, total = 0;
        long maxLatency = 0;
        long start = now, end = now + seconds * 1_000;
        while (now < end) {
            long dl = now - start < looseMs ? looseDeadlineMs : deadlineMs;
            while (work.size() < t.inFlightCap(1, slots) && t.canMeet(1, work.size(), now, now + dl)) {
                meter.start(now);
                work.add(new long[] {now, -1, now + dl, work.size() + 1});
            }
            int running = 0;
            for (long[] w : work) {
                if (w[1] >= 0) {
                    running++;
                }
            }
            for (long[] w : work) {
                if (w[1] < 0 && running < (now - start >= changeAfterMs ? realAfter : real)) {
                    w[1] = now;
                    running++;
                }
            }
            long next = Long.MAX_VALUE;
            for (long[] w : work) {
                if (w[1] >= 0) {
                    next = Math.min(next, w[1] + serviceMs);
                }
            }
            if (next == Long.MAX_VALUE) {
                now += 100;   // 一个都没派（不该发生）：时间照走
                continue;
            }
            now = next;
            for (var it = work.iterator(); it.hasNext(); ) {
                long[] w = it.next();
                if (w[1] >= 0 && w[1] + serviceMs == now) {
                    long[] r = meter.read(now);
                    t.record(1, now - w[0], now, r[0], r[1], (int) w[3]);
                    meter.end(now);
                    total++;
                    maxLatency = Math.max(maxLatency, now - w[0]);
                    if (now > w[2] && w[0] - start >= looseMs) {
                        late++;
                    }
                    if (lateAfter != null && w[0] - start >= changeAfterMs) {
                        lateAfter[1]++;
                        if (now > w[2]) {
                            lateAfter[0]++;
                        }
                    }
                    it.remove();
                }
            }
        }
        return new int[] {t.lanes(1), late, total, (int) t.laneLatencyMs(1), (int) maxLatency};
    }

    @Test
    void lanesClimbToRealParallelism() {
        // 正式服的情形：M5 真能同时做 4 个、每个 0.7 秒，开始只估成 1 个；队里全是 1 秒截止的紧任务。几十秒内 lanes 要爬到 4
        int[] r = simulate(4, 700, 1_000, 60);
        System.out.println("4 并行：lanes=" + r[0] + " 迟到 " + r[1] + "/" + r[2]);
        assertEquals(4, r[0], "lanes 爬到真实的并行度");
        assertTrue(r[1] == 0, "真能并行时往上探不会迟到：" + r[1] + "/" + r[2]);
    }

    @Test
    void probingSerialDonorStaysLowAndRarelyLate() {
        // 真的一个一个做：往上探一次延迟就翻倍，退回来、过一阵才再探。迟到的只是偶尔探路的那几个
        int[] r = simulate(1, 700, 1_000, 60);
        System.out.println("1 并行：lanes=" + r[0] + " 迟到 " + r[1] + "/" + r[2]);
        assertTrue(r[0] <= 2, "lanes " + r[0]);
        assertTrue(r[1] * 10 <= r[2], "迟到不超过一成：" + r[1] + "/" + r[2]);
    }

    @Test
    void probingStopsWhereLatencyGrows() {
        // 真能做 2 个：爬到 2，探 3 时延迟变长，退回
        int[] r = simulate(2, 700, 1_000, 60);
        System.out.println("2 并行：lanes=" + r[0] + " 迟到 " + r[1] + "/" + r[2]);
        assertTrue(r[0] >= 2 && r[0] <= 3, "lanes " + r[0]);
        assertTrue(r[1] * 10 <= r[2], "迟到不超过一成：" + r[1] + "/" + r[2]);
    }

    @Test
    void serialDonorColdStartDoesNotGetStuck() {
        // codex-audit 第十六节 P1：真一个一个做的 donor 冷启动，一次派 4 个，延迟 0.7/1.4/2.1/2.8 秒。
        // 原来只看占满 lanes 的那个慢样本，退到 3；没排队的延迟又混进排队的样本估成 1.4 秒，空着也拒掉 1 秒截止的，卡死。
        // 前 10 秒截止宽（3.5 秒），之后全是 1 秒的紧任务
        int[] r = simulate(1, 700, 1_000, 60, false, 10_000, 3_500);
        System.out.println("冷启动 1 并行：lanes=" + r[0] + " 迟到 " + r[1] + "/" + r[2] + " laneLatency=" + r[3]);
        assertTrue(r[0] <= 2, "lanes " + r[0]);
        assertTrue(r[3] <= 800, "没排队的延迟按单独做的估：" + r[3]);
        assertTrue(r[2] >= 70, "没卡住：60 秒做了 " + r[2]);
        assertTrue(r[1] * 10 <= r[2], "迟到不超过一成：" + r[1] + "/" + r[2]);
    }

    @Test
    void parallelDonorColdStartKeepsFourLanes() {
        int[] r = simulate(4, 700, 1_000, 60, false, 10_000, 3_500);
        System.out.println("冷启动 4 并行：lanes=" + r[0] + " 迟到 " + r[1] + "/" + r[2] + " laneLatency=" + r[3]);
        assertEquals(4, r[0]);
        assertEquals(0, r[1]);
    }

    @Test
    void slowSoloTileDoesNotDropLanes() {
        // 单独做的一个 tile 慢（地形复杂）：不是并行造成的，不退 lanes。先按 4 个一批做出 4 lanes
        DonorThroughput t = new DonorThroughput();
        LoadMeter m = new LoadMeter();
        long now = 10_000;
        for (int k = 0; k < 6; k++) {
            for (int i = 0; i < 4; i++) {
                m.start(now);
            }
            now += 700;
            for (int i = 0; i < 4; i++) {
                long[] r = m.read(now);
                t.record(1, 700, now, r[0], r[1], i + 1);
                m.end(now);
            }
        }
        int before = t.lanes(1);
        assertEquals(4, before);
        m.start(now);
        now += 2_000;
        long[] r = m.read(now);
        t.record(1, 2_000, now, r[0], r[1], 1);
        assertEquals(before, t.lanes(1));
    }

    // ---- 11-10 kexu mc8：lanes 退到 1 了，在途还是接近 8（parallelism 6.4、延迟 6.8 秒）：lanes 只用来估，没限派活 ----

    @Test
    void inFlightCappedByLanes() {
        DonorThroughput t = new DonorThroughput();
        List<long[]> iv = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            iv.add(new long[] {10_000 + i * 700L, 10_700 + i * 700L});
        }
        feed(t, iv);
        assertEquals(1, t.lanes(1));
        assertEquals(1, t.inFlightCap(1, 8), "一个一个做的：租约名额有 8 个也只给 1 个");
        assertEquals(4, new DonorThroughput().inFlightCap(9, 8), "冷启动：按 maxConcurrency 探路");
        assertEquals(2, new DonorThroughput().inFlightCap(9, 2), "不超过租约名额");
    }

    @Test
    void serialDonorWithLooseDeadlinesDoesNotPileUp() {
        // 截止都很宽（10 秒），租约名额 8：原来派到 8 个在途，一个一个做的 donor 延迟涨到 5～6 秒，后来的紧任务都排在后面
        int[] r = simulate(1, 700, 10_000, 60, true, 0, 10_000, 8);
        System.out.println("宽截止 1 并行 8 名额：lanes=" + r[0] + " 总 " + r[2] + " 最长延迟 " + r[4]);
        assertTrue(r[4] <= 1_500, "在途不堆：最长延迟 " + r[4]);
        assertTrue(r[2] >= 70, "照样做满：" + r[2]);
    }

    @Test
    void parallelDonorWithLooseDeadlinesUsesItsLanes() {
        int[] r = simulate(4, 700, 10_000, 60, true, 0, 10_000, 8);
        System.out.println("宽截止 4 并行 8 名额：lanes=" + r[0] + " 总 " + r[2] + " 最长延迟 " + r[4]);
        assertEquals(4, r[0]);
        assertTrue(r[2] >= 300, "4 个并行做满：" + r[2]);
    }

    @Test
    void boostCeilingLetsLanesClimbPastMaxConcurrency() {
        // 11-09 加速模式：8 个线程的 donor 在途上限 8，lanes 也要能往上探到 8（不卡在 maxConcurrency 4）
        DonorThroughput t = new DonorThroughput();
        t.setCeiling(1, 8);
        LoadMeter m = new LoadMeter();
        long now = 10_000;
        for (int round = 0; round < 40; round++) {
            int n = t.inFlightCap(1, 8);
            for (int i = 0; i < n; i++) {
                m.start(now);
            }
            now += 700;   // 真能同时做 8 个，每个 0.7 秒
            for (int i = 0; i < n; i++) {
                long[] r = m.read(now);
                t.record(1, 700, now, r[0], r[1], i + 1);
                m.end(now);
            }
        }
        assertEquals(8, t.lanes(1));
        assertEquals(DonorThroughput.MAX_CONCURRENCY, new DonorThroughput().lanes(5), "没设的照旧");
    }

    // ---- 11-10 kexu k4（92f40c4 直接飞）：boost 开的那轮 lanes 退到 1、parallelism 1.0：
    //      后台 tile（离得远、单独做，比前方相邻的贵一倍）的慢样本触发了"一起做、慢过单独做 1.5 倍"的回退 ----

    /** 先按 4 个一批做出 4 lanes（前方的），返回在途表和时间。 */
    private static long[] fourLanes(DonorThroughput t, LoadMeter m) {
        long now = 10_000;
        for (int k = 0; k < 6; k++) {
            for (int i = 0; i < 4; i++) {
                m.start(now);
            }
            now += 700;
            for (int i = 0; i < 4; i++) {
                long[] r = m.read(now);
                t.record(1, 700, now, r[0], r[1], i + 1);
                m.end(now);
            }
        }
        return new long[] {now};
    }

    @Test
    void slowBackgroundSamplesDoNotDropLanes() {
        DonorThroughput t = new DonorThroughput();
        LoadMeter m = new LoadMeter();
        long now = fourLanes(t, m)[0];
        assertEquals(4, t.lanes(1));
        for (int i = 0; i < 6; i++) {
            m.start(now);
            now += 1_500;
            long[] r = m.read(now);
            t.record(1, 1_500, now, r[0], r[1], 3, true);   // 后台的：贵，和别的一起做
            m.end(now);
        }
        assertEquals(4, t.lanes(1), "后台 tile 的延迟不拿来判断并行（地形贵不等于并行多了变慢）");
        assertTrue(t.laneLatencyMs(1) <= 800, "马上开始的延迟也不混进后台的：" + t.laneLatencyMs(1));
    }

    @Test
    void oneSlowSharedSampleIsNotEnoughToBackOff() {
        // 前方的一个 tile 贵（新地形），一起做时慢了一次：不退；连着两次才退
        DonorThroughput t = new DonorThroughput();
        LoadMeter m = new LoadMeter();
        long now = fourLanes(t, m)[0];
        m.start(now);
        now += 1_500;
        long[] r = m.read(now);
        t.record(1, 1_500, now, r[0], r[1], 3);
        m.end(now);
        assertEquals(4, t.lanes(1), "慢一次不退");
        m.start(now);
        now += 1_500;
        r = m.read(now);
        t.record(1, 1_500, now, r[0], r[1], 3);
        m.end(now);
        assertEquals(2, t.lanes(1), "连着两次慢：退到派出时手上的个数 − 1");
    }

    // ---- codex-audit 第十八节（审 0163fc8）----

    @Test
    void donorThatLosesTwoLanesBacksOff() {
        // X2：已经学到 4 个并行，20 秒时真能并行的变成 2（用户开游戏抢 CPU），全是 1 秒紧任务。
        // 0163fc8：两次慢之间夹一个占满时的快的就清零，lanes 停在 3，变化后一半迟到
        int[] after = new int[2];
        int[] r = simulate(4, 700, 1_000, 80, true, 0, 1_000, 4, 2, 20_000, after);
        System.out.println("4→2：lanes=" + r[0] + " 变化后迟到 " + after[0] + "/" + after[1]);
        assertEquals(2, r[0], "退到 2");
        assertTrue(after[0] * 6 <= after[1], "变化后迟到不超过约一成半：" + after[0] + "/" + after[1]);
    }

    @Test
    void backgroundOnlyColdStartKeepsGoing() {
        // X1：新 worker 只做后台（没有前方的样本）：lanes 不能被后台的低在途算成 1（半额变 0 就再也不派）
        DonorThroughput t = new DonorThroughput();
        LoadMeter m = new LoadMeter();
        long now = 10_000;
        for (int round = 0; round < 12; round++) {
            int cap = t.inFlightCap(1, 8);
            int bg = BoostPolicy.backgroundSlots(BoostPolicy.dispatchCap(cap, true, 8));
            assertTrue(bg >= 1, "第 " + round + " 轮还能派后台：cap " + cap);
            for (int i = 0; i < bg; i++) {
                m.start(now);
            }
            now += 1_500;
            for (int i = 0; i < bg; i++) {
                long[] r = m.read(now);
                t.record(1, 1_500, now, r[0], r[1], i + 1, true);
                m.end(now);
            }
        }
        assertEquals(DonorThroughput.MAX_CONCURRENCY, t.lanes(1), "没有前方的样本：照冷启动");
        assertEquals(DonorThroughput.DEFAULT_MS, t.laneLatencyMs(1), "前方的延迟不拿后台的补：" + t.laneLatencyMs(1));
    }

    @Test
    void fastBackgroundDoesNotSupplyMissingSoloBaseline() {
        // X1 补充：前方只有 k4 的样本（没有单独做的），中间混进 100 ms 的后台（缓存命中）：
        // 单独做的基线不能拿后台的 100 ms 补，否则后面正常 700 ms 的前方都被当成慢了
        DonorThroughput t = new DonorThroughput();
        LoadMeter m = new LoadMeter();
        long now = 10_000;
        for (int round = 0; round < 8; round++) {
            for (int i = 0; i < 4; i++) {
                m.start(now);
            }
            now += 700;
            for (int i = 0; i < 4; i++) {
                long[] r = m.read(now);
                t.record(1, 700, now, r[0], r[1], 4, false);
                m.end(now);
            }
            m.start(now);
            now += 100;
            long[] r = m.read(now);
            t.record(1, 100, now, r[0], r[1], 2, true);
            m.end(now);
        }
        assertEquals(4, t.lanes(1));
        assertTrue(t.laneLatencyMs(1) >= 600, "马上开始的延迟按前方的：" + t.laneLatencyMs(1));
    }
}
