package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        // 4 个一批：每个派出到收到 4 秒，但每 100 ms 完成一个、每 4 秒一批 → 实际约 1 个/秒以上，不是 0.25
        DonorThroughput t = new DonorThroughput();
        for (int batch = 0; batch < 2; batch++) {
            for (int i = 0; i < 4; i++) {
                t.record(1, 4_000, 4_000 + batch * 4_000 + i * 100L);
            }
        }
        assertEquals(4_000, t.latencyMs(1), "延迟取中位数");
        double rate = t.ratePerMs(1) * 1000;
        assertTrue(rate > 1.5 && rate < 2.0, "按完成间隔：7 个间隔 4.3 秒，约 1.6 个/秒，实际 " + rate);
        long now = 100_000;
        assertTrue(t.canMeet(1, 3, now, now + 6_000), "手上 3 个、能并行：4 秒左右做完");
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
        // codex-audit F4：同一批几乎同时到（间隔 0～几毫秒），速率不能估成几十个/秒。
        // 11-09 起按忙碌时间：4 个一起做了 4 秒，就是 1 个/秒（原来按 1/延迟估 0.25，太保守）
        DonorThroughput t = new DonorThroughput();
        for (int i = 0; i < 4; i++) {
            t.record(1, 4_000, 50_000 + i);
        }
        assertEquals(1.0, t.ratePerMs(1) * 1000, 0.01);
    }

    @Test
    void coldStartRateIsOptimisticForPruning() {
        // codex-audit G4：冷启动只改了 canMeet，剪枝还按 1/默认延迟（串行）算速率，派出去探路的 4 个里剪掉 3 个
        assertEquals(DonorThroughput.MAX_CONCURRENCY / (double) DonorThroughput.DEFAULT_MS, new DonorThroughput().ratePerMs(7), 1e-12);
    }

    // ---- 11-09 正式服（f19d087、用户 M5）：inFlight 0、队里 37～40 个，启动以来 hopeless 398 ----
    // 速率按最近 10 个的完成间隔算，donor 等派活的空档也算进去，速率被低估；剪枝和 canMeet 按低估的速率
    // 把能做完的判来不及 → 派得更少 → 空档更多 → 速率更低（distributed-mc-75 读代码发现的循环）。

    @Test
    void idleGapsDoNotLowerRate() {
        // 每批 2 个并行（700 ms），批与批之间 donor 空等 1.2 秒（等下一秒的派活）：忙的时候约 2.7 个/秒
        DonorThroughput t = new DonorThroughput();
        for (int k = 0; k < 5; k++) {
            long s0 = 10_000 + k * 1_900L;
            t.record(1, 700, s0 + 700);
            t.record(1, 700, s0 + 750);
        }
        double rate = t.ratePerMs(1) * 1000;
        assertTrue(rate > 2.5, "空档不算进去：10 个、忙 3.75 秒，实际 " + rate);
    }

    @Test
    void freeSlotStartsNow() {
        // donor 还有空名额：再给它一个，马上就开始做，延迟后做完；不按"前面还有几个 ÷ 速率"排队
        DonorThroughput t = new DonorThroughput();
        t.record(1, 700, 50_000);     // 只有一个样本：速率按 1/延迟，最保守
        long now = 100_000;
        assertTrue(t.canMeet(1, 2, 4, now, now + 1_000), "4 个名额用了 2 个：0.7 秒做完");
        assertFalse(t.canMeet(1, 4, 4, now, now + 1_000), "名额满了才排队");
        assertFalse(t.canMeet(1, 2, now, now + 1_000), "不给名额数时照旧（一个一个）");
    }
}
