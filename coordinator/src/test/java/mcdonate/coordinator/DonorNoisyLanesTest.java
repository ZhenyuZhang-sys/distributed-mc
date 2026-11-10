package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntToLongFunction;
import org.junit.jupiter.api.Test;

/**
 * 11-10 kexu 短 A/B（k3，boost 关，站 60 秒再飞）：8f0cf99 对 56b4eee 前方空白 3.5～3.7 对 3.3 秒，飞完 lanes 1。
 * 服务器日志拆开：开头 4～5 个在途（每个 1.4～2.8 秒），中段 3 个（约 1.1 秒），73 秒后只剩 1 个（0.5～0.9 秒），
 * 每秒剪掉 4～10 个"来不及"。并行有收益（1 个约 1.4 个/秒，3 个约 2.7 个/秒），是 tile 有贵有便宜（written 36 对 16）、
 * 一个样本对一档的平均判"多这一条不多出活"，噪声把 lanes 一档一档退下来，1 档时要连着 3 个不超过 1.2 倍才往上，很难。
 */
class DonorNoisyLanesTest {
    /** k3 那台：同时做 n 个时每个的基础耗时；乘上 tile 本身的贵贱（循环的一串系数）。 */
    static IntToLongFunction k3Like() {
        long[] base = {0, 700, 900, 1_100, 1_600};
        double[] noise = {0.7, 1.0, 1.4, 0.9, 1.2, 0.8, 1.3, 0.75, 1.1};
        int[] i = {0};
        return n -> (long) (base[Math.min(n, 4)] * (n > 4 ? n / 4.0 : 1) * noise[i[0]++ % noise.length]);
    }

    @Test
    void realK3TraceDoesNotDropToOneLane() throws Exception {
        // 按 kexu 那一轮的真实顺序重放（派出时刻、交回时刻、派出时在途数）。8f0cf99：21.5 秒一个 k 2 的 1.7 秒样本
        // 和"单独基线"比——没有单独样本，基线是最短的那个（收尾时便宜的 tile 0.47 秒）——lanes 4 直接退到 1
        List<long[]> rows = new ArrayList<>();
        try (var in = getClass().getResourceAsStream("/replay/k3-solo-8f0cf99.txt")) {
            for (String l : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (!l.isBlank() && !l.startsWith("#")) {
                    String[] p = l.trim().split(" ");
                    rows.add(new long[] {Long.parseLong(p[0]), Long.parseLong(p[1]), Long.parseLong(p[2])});
                }
            }
        }
        List<long[]> ev = new ArrayList<>();
        for (long[] r : rows) {
            ev.add(new long[] {r[0], 1, 0, 0});
            ev.add(new long[] {r[1], -1, r[1] - r[0], r[2]});
        }
        ev.sort(Comparator.comparingLong((long[] e) -> e[0]).thenComparingLong(e -> e[1]));
        DonorThroughput t = new DonorThroughput();
        LoadMeter m = new LoadMeter();
        int min = Integer.MAX_VALUE;
        for (long[] e : ev) {
            if (e[1] == 1) {
                m.start(e[0]);
                continue;
            }
            long[] r = m.read(e[0]);
            t.record(1, e[2], e[0], r[0], r[1], (int) e[3]);
            m.end(e[0]);
            min = Math.min(min, t.lanes(1));
        }
        System.out.println("k3 真实重放：最低 lanes " + min + "，最后 " + t.lanes(1));
        assertTrue(min >= 2, "并行有收益（3 个约 2.7 个/秒，1 个约 1.4 个/秒）：不退到 1，最低 " + min);
    }

    @Test
    void cheapDrainSampleIsNotTheSoloBaseline() {
        // 同样的形状、手造的数：冷启动 4 个；之后都是 4 个一起、每个 1.6～2.8 秒；收尾时一个便宜的 k 2 只要 0.47 秒，
        // 接着两个 k 2 的 1.7 秒。没有单独做的样本：不能拿 0.47 当单独基线把它们判慢
        DonorThroughput t = new DonorThroughput();
        LoadMeter m = new LoadMeter();
        long now = 10_000;
        for (int i = 0; i < 4; i++) {
            m.start(now);
        }
        now += 3_600;
        for (int k = 1; k <= 4; k++) {
            long[] r = m.read(now);
            t.record(1, 3_600, now, r[0], r[1], k);
            m.end(now);
        }
        long[] lat = {2_700, 2_800, 1_900, 1_600, 1_700, 2_400, 2_800, 1_600};
        for (long d : lat) {
            m.start(now);
            now += d;
            long[] r = m.read(now);
            t.record(1, d, now, r[0], r[1], 4);
            m.end(now);
        }
        for (long d : new long[] {470, 1_740, 1_700}) {
            m.start(now);
            now += d;
            long[] r = m.read(now);
            t.record(1, d, now, r[0], r[1], 2);
            m.end(now);
        }
        assertEquals(DonorThroughput.MAX_CONCURRENCY, t.lanes(1), "没量过单独做和 1 档：不退");
    }

    @Test
    void noisyTileCostsKeepSeveralLanes() {
        // 护栏（不是复现）：同时做 n 个时每个的基础耗时乘 tile 贵贱的系数，冷启动、截止 3 秒、120 秒。lanes 不走到 1
        int[] r = DonorThroughputTest.simulate(4, 700, 3_000, 120, false, 0, 3_000, 4, 4, Long.MAX_VALUE, null, 700, k3Like());
        System.out.println("k3 型有噪声：lanes=" + r[0] + " 总 " + r[2] + " 迟到 " + r[1] + " laneLatency=" + r[3]);
        assertTrue(r[0] >= 2, "lanes " + r[0]);
        assertTrue(r[2] >= 240, "吞吐不掉到 1 条（约 1.4 个/秒、170 个）：" + r[2]);
    }

    @Test
    void idleExplorationAfterOnlyBackgroundHistoryRestartsAtOneLane() {
        // codex-audit 8f0cf99 R7：只做过后台（没有 probes 记录）时，空着全拒后的探路派出 lanes 没回到 1
        DonorThroughput t = new DonorThroughput();
        LoadMeter m = new LoadMeter();
        long now = 10_000;
        for (int b = 0; b < 2; b++) {
            m.start(now);
            m.start(now);
            now += 2_000;
            for (int i = 0; i < 2; i++) {
                long[] r = m.read(now);
                t.record(1, 2_000, now, r[0], r[1], i + 1, true);
                m.end(now);
            }
        }
        assertTrue(!t.canMeet(1, 0, now, now + 1_000), "前方 1 秒的：按默认 3 秒拒");
        long later = now + DonorThroughput.EXPLORE_MS + 1;
        assertTrue(t.canMeet(1, 0, later, later + 1_000));
        assertTrue(t.leased(1, later));
        assertEquals(1, t.lanes(1), "探路时 lanes 回到 1");
        assertEquals(1, t.inFlightCap(1, 4));
        m.start(later);
        long back = later + 700;
        long[] r = m.read(back);
        t.record(1, 700, back, r[0], r[1], 1);
        m.end(back);
        assertEquals(1, t.lanes(1), "探路回来：一个单独的样本不够往上探");
        assertTrue(!t.canMeet(1, 1, back, back + 1_000), "手上一个、lanes 1：第二个 1 秒的要等前一个，赶不上（不按旧后台的并行度估）");
    }
}
