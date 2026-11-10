package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToLongFunction;
import org.junit.jupiter.api.Test;

/**
 * 11-10 调度定：lanes 按"上限设成 L、占满的那段时间里实测交回速率"分档比，不按单个样本的延迟。
 * 原因（kexu 两轮重放）：样本按"派出时在途几个"分档，不是真正同时跑几个；队列空了又满的过渡期派出的几个刚好便宜，
 * 成了低一档的参照，下一个样本对它判"多一条不多出活"就退档，之后爬不回来（codex-audit 一直留着的 R1）。
 * 做法：试 L+1 量一段，快过 L 一定比例才留；定期（或速率掉了）试 L−1，不比 L 慢一成以上就留；没占满的时段不量。
 */
class DonorLanesByRateTest {
    /** 按真实顺序重放一轮（派出时刻、交回时刻、派出时在途数），返回 afterMs 以后最低的 lanes。 */
    static int replayMinLanes(String resource, long afterMs) throws Exception {
        List<long[]> ev = new ArrayList<>();
        try (var in = DonorLanesByRateTest.class.getResourceAsStream(resource)) {
            for (String l : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (!l.isBlank() && !l.startsWith("#")) {
                    String[] p = l.trim().split(" ");
                    long a = Long.parseLong(p[0]), b = Long.parseLong(p[1]);
                    ev.add(new long[] {a, 1, 0, 0});
                    ev.add(new long[] {b, -1, b - a, Long.parseLong(p[2])});
                }
            }
        }
        ev.sort(java.util.Comparator.comparingLong((long[] e) -> e[0]).thenComparingLong(e -> e[1]));
        DonorThroughput t = new DonorThroughput();
        LoadMeter m = new LoadMeter();
        int min = Integer.MAX_VALUE;
        long start = ev.get(0)[0];
        for (long[] e : ev) {
            if (e[1] == 1) {
                m.start(e[0]);
                continue;
            }
            long[] r = m.read(e[0]);
            t.record(1, e[2], e[0], r[0], r[1], (int) e[3]);
            m.end(e[0]);
            if (e[0] - start >= afterMs) {
                min = Math.min(min, t.lanes(1));
            }
        }
        System.out.println(resource + " 重放：最低 lanes " + min + "，最后 " + t.lanes(1));
        return min;
    }

    @Test
    void drainedQueueTraceKeepsLanes() throws Exception {
        // 625fe9ef：83～85 秒队列短暂空了，派出时在途 2 的便宜样本当了 2 档参照，86 秒退档，最后停在 1
        int min = replayMinLanes("/replay/k3-pool-b9b8f42-625f.txt", 20_000);
        assertTrue(min >= 3, "队列空一下不退档：最低 " + min);
    }

    @Test
    void closeLevelsTraceDoesNotDropToTwo() throws Exception {
        // caa3：3→4 后一个 k 3 的慢样本加上窗口里留的一个慢的，退到 2，约 50 秒停在 2
        int min = replayMinLanes("/replay/k3-solo2-2736827-caa3.txt", 20_000);
        assertTrue(min >= 3, "最低 " + min);
    }

    /**
     * 闭环小仿真：donor 真能同时做 4 个，同时做 n 个时每个要 cost(n)（开始做时定）；需求一直有，gap 那段没有新需求。
     * 截止 3 秒。返回 {最后的 lanes, 总交回, gap 之后交回, gap 之后最低 lanes}。
     */
    static int[] run(IntToLongFunction cost, long seconds, long gapFrom, long gapTo) {
        DonorThroughput t = new DonorThroughput();
        LoadMeter m = new LoadMeter();
        List<long[]> work = new ArrayList<>();   // {派出时刻, 做完时刻, 派出时在途}
        long now = 0, end = seconds * 1_000;
        int total = 0, after = 0, minAfter = Integer.MAX_VALUE;
        while (now < end) {
            boolean demand = now < gapFrom || now >= gapTo;
            while (demand && work.size() < t.inFlightCap(1, 4) && t.canMeet(1, work.size(), now, now + 3_000)) {
                m.start(now);
                work.add(new long[] {now, now + cost.applyAsLong(work.size() + 1), work.size() + 1});
                t.leased(1, now);
            }
            long next = now + 100;
            for (long[] w : work) {
                next = Math.min(next, w[1]);
            }
            now = next;
            for (var it = work.iterator(); it.hasNext(); ) {
                long[] w = it.next();
                if (w[1] <= now) {
                    long[] r = m.read(now);
                    t.record(1, now - w[0], now, r[0], r[1], (int) w[2]);
                    m.end(now);
                    total++;
                    if (w[0] >= gapTo) {
                        after++;
                    }
                    it.remove();
                }
            }
            if (now >= gapTo + 5_000) {
                minAfter = Math.min(minAfter, t.lanes(1));
            }
        }
        return new int[] {t.lanes(1), total, after, minAfter};
    }

    @Test
    void demandGapThenResumeClimbsBack() {
        // 有噪声的 k3 型 donor，60～63 秒没有新需求（队列空了），之后照常：之后 lanes 不长期停在低档
        int[] r = run(DonorNoisyLanesTest.k3Like(), 120, 60_000, 63_000);
        System.out.println("需求断 3 秒：最后 lanes " + r[0] + " 总 " + r[1] + " 之后 " + r[2] + " 之后最低 lanes " + r[3]);
        assertTrue(r[3] >= 2, "之后最低 " + r[3]);
        assertTrue(r[2] >= 135, "之后 57 秒约 2.5 个/秒以上：" + r[2]);
    }

    @Test
    void noisyDonorSettlesOnTheBestLevel() {
        // 同时做 1/2/3/4 个时速率约 1.4/2.2/2.7/2.5 个/秒（kexu k3 实测的形状），tile 贵贱乘系数
        int[] r = run(DonorNoisyLanesTest.k3Like(), 120, Long.MAX_VALUE, Long.MAX_VALUE);
        System.out.println("k3 型有噪声闭环：最后 lanes " + r[0] + " 总 " + r[1]);
        assertTrue(r[0] >= 3, "停在 3～4：" + r[0]);
        assertTrue(r[1] >= 290, "接近最好那档（3 条约 2.7 个/秒）：" + r[1]);
    }
}
