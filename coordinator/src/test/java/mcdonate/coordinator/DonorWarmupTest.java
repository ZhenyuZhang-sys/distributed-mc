package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 11-10 codex-pipe 做 territory 原型时撞到：第二个 donor 刚连上，冷启动一次派 maxConcurrency 个、一起交回，
 * 延迟估成 16 秒、slots 1，之后 120 秒只交回 1 个 tile。刚起来的 worker 第一批特别慢（JIT 没热、世界缓存空、
 * 第一批 tile 互不相邻、2 核上 4 个一起做），这批的延迟不代表它平时的速度。
 * 修：第一个结果回来之前派出的那批算"预热"，照样算进并行度。有正常样本以后不进任何延迟估计；
 * 还没有时按预热的最慢值保守估，空着就放一个测量（codex-audit 4610642 R5）。退并行只在批内比（R6）。
 */
class DonorWarmupTest {
    /** 冷启动一批 4 个、都 16 秒、同时交回（按派出顺序 k1→k4 或反过来）。 */
    private static void warmBatch(DonorThroughput t, LoadMeter m, long start, long ms, boolean reverse) {
        for (int i = 0; i < 4; i++) {
            m.start(start);
        }
        long done = start + ms;
        for (int j = 0; j < 4; j++) {
            int k = reverse ? 4 - j : j + 1;
            long[] r = m.read(done);
            t.record(1, ms, done, r[0], r[1], k);
            m.end(done);
        }
    }

    @Test
    void coldBatchIsAnUpperBoundUntilMeasured() {
        // codex-audit 4610642 R5：首批 16 秒可能是 JIT 没热，也可能就是这台 donor 的速度，四条一样慢分不出来。
        // 原来丢掉首批、按默认 3 秒估，真 16 秒的 donor 第二批 5 秒截止的 4 个全派、全迟到。
        // 改：只有预热样本时按预热的最慢值保守估；空着时放一个测量（不看截止），拿到正常样本再按它
        DonorThroughput t = new DonorThroughput();
        LoadMeter m = new LoadMeter();
        warmBatch(t, m, 10_000, 16_000, false);
        long now = 26_100;
        assertEquals(16_000, t.laneLatencyMs(1), "只有预热样本：按预热的最慢值");
        assertTrue(t.canMeet(1, 0, now, now + 5_000), "空着：放一个测量");
        assertTrue(!t.canMeet(1, 1, now, now + 5_000), "手上有一个测量了：紧的不再派（不按乐观默认整批放）");
        assertTrue(t.canMeet(1, 1, now, now + 40_000), "宽截止的照常派");
    }

    @Test
    void trulySlowDonorStaysSlowAfterMeasurement() {
        // 测量那个也是 16 秒：是真慢，之后紧的不派（空着全拒 EXPLORE_MS 才再放一个）
        DonorThroughput t = new DonorThroughput();
        LoadMeter m = new LoadMeter();
        warmBatch(t, m, 10_000, 16_000, false);
        long now = 26_000;
        m.start(now);
        now += 16_000;
        long[] r = m.read(now);
        t.record(1, 16_000, now, r[0], r[1], 1);
        m.end(now);
        assertEquals(16_000, t.laneLatencyMs(1));
        assertTrue(!t.canMeet(1, 0, now + 100, now + 5_100), "正常样本也是 16 秒：紧的不派");
    }

    @Test
    void reverseOrderWarmBatchDoesNotBackOff() {
        // codex-audit 4610642 R6：一批一样慢、只是报回顺序 k4→k1，原来缺了批内的低档就拿默认 3 秒比，退到 2
        DonorThroughput t = new DonorThroughput();
        LoadMeter m = new LoadMeter();
        warmBatch(t, m, 10_000, 16_000, true);
        assertEquals(DonorThroughput.MAX_CONCURRENCY, t.lanes(1), "一批一样慢：不退");
        assertTrue(!t.calibrating(1));
    }

    @Test
    void serialWarmBatchStillBacksOff() {
        // 真一个一个做（4、8、12、16 秒）：批内越多越慢，照样退
        DonorThroughput t = new DonorThroughput();
        LoadMeter m = new LoadMeter();
        for (int i = 0; i < 4; i++) {
            m.start(10_000);
        }
        for (int k = 1; k <= 4; k++) {
            long done = 10_000 + 4_000L * k;
            long[] r = m.read(done);
            t.record(1, 4_000L * k, done, r[0], r[1], k);
            m.end(done);
        }
        assertTrue(t.lanes(1) <= 2, "批内越多越慢：退，lanes " + t.lanes(1));
    }

    @Test
    void measurementIsReleasedOnlyOnLease() {
        // codex-audit：探路次数在谓词放行时就记，没绑真实租约。改成派出时（leased）才算
        DonorThroughput t = new DonorThroughput();
        LoadMeter m = new LoadMeter();
        warmBatch(t, m, 10_000, 16_000, false);
        long now = 26_100;
        assertTrue(t.canMeet(1, 0, now, now + 5_000));
        assertTrue(t.canMeet(1, 0, now, now + 5_000), "还没派出：再问照样放（assign 会对几个候选都问）");
        assertEquals(0, t.explorations(1));
        t.leased(1, now);
        assertEquals(1, t.explorations(1));
    }

    @Test
    void laterSamplesSetTheRealSpeed() {
        // 预热以后正常的样本（单独做 2 秒）：延迟按它们
        DonorThroughput t = new DonorThroughput();
        LoadMeter m = new LoadMeter();
        long now = 10_000;
        for (int i = 0; i < 4; i++) {
            m.start(now);
        }
        now += 16_000;
        for (int i = 0; i < 4; i++) {
            long[] r = m.read(now);
            t.record(1, 16_000, now, r[0], r[1], i + 1);
            m.end(now);
        }
        for (int i = 0; i < 5; i++) {
            m.start(now);
            now += 2_000;
            long[] r = m.read(now);
            t.record(1, 2_000, now, r[0], r[1], 1);
            m.end(now);
        }
        assertEquals(2_000, t.latencyMs(1));
        assertEquals(2_000, t.laneLatencyMs(1));
    }
}
