package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 11-10 codex-pipe 做 territory 原型时撞到：第二个 donor 刚连上，冷启动一次派 maxConcurrency 个、一起交回，
 * 延迟估成 16 秒、slots 1，之后 120 秒只交回 1 个 tile。刚起来的 worker 第一批特别慢（JIT 没热、世界缓存空、
 * 第一批 tile 互不相邻、2 核上 4 个一起做），这批的延迟不代表它平时的速度。
 * 修：第一个结果回来之前派出的那批算"预热"，照样算进并行度，但不进任何延迟估计、不拿来往上探或回退。
 */
class DonorWarmupTest {
    @Test
    void coldBatchDoesNotDefineLatency() {
        DonorThroughput t = new DonorThroughput();
        LoadMeter m = new LoadMeter();
        long now = 10_000;
        for (int i = 0; i < 4; i++) {
            m.start(now);
        }
        now += 16_000;
        for (int i = 0; i < 4; i++) {
            long[] r = m.read(now);
            t.record(1, 16_000, now + i, r[0], r[1], i + 1);
            m.end(now + i);
        }
        assertTrue(t.latencyMs(1) <= DonorThroughput.DEFAULT_MS, "预热那批不算延迟：" + t.latencyMs(1));
        assertTrue(t.concurrency(1, 30_000) >= 2, "租约名额不被压成 1：" + t.concurrency(1, 30_000));
        assertTrue(t.laneLatencyMs(1) <= DonorThroughput.DEFAULT_MS, "马上开始的延迟也不按 16 秒：" + t.laneLatencyMs(1));
        long later = now + 100;
        assertTrue(t.canMeet(1, 0, later, later + 5_000), "空着能接 5 秒截止的");
        assertEquals(DonorThroughput.MAX_CONCURRENCY, t.lanes(1), "还没有正常的前方样本：照冷启动");
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
