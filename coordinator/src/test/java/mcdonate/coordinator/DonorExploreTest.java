package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** codex-audit 第二十一节（审 177429c）余下的两条。 */
class DonorExploreTest {
    @Test
    void soloTaskStraddlingBackoffDoesNotInflateBaseline() {
        // Z2 余下：回退之前派出、回退之后才交回的慢 k=1（3 秒）不结束测量，但原来还进了单独基线的中位数；
        // 单独量超时以后 laneLatency 变成 1400，空着也拒 1 秒的任务
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
        for (int i = 0; i < 2; i++) {
            m.start(now);
            now += 1_400;
            long[] r = m.read(now);
            t.record(1, 1_400, now, r[0], r[1], 4);
            m.end(now);
        }
        assertTrue(t.calibrating(1));
        long[] r = m.read(now);
        t.record(1, 3_000, now + 500, r[0], r[1], 1);   // 派出在回退之前
        t.expireCalibration(1, now + DonorThroughput.CALIBRATE_MS + 1_000);
        assertTrue(!t.calibrating(1));
        assertTrue(t.laneLatencyMs(1) <= 800, "跨过回退的旧单独样本不进基线：" + t.laneLatencyMs(1));
        long later = now + DonorThroughput.CALIBRATE_MS + 2_000;
        assertTrue(t.canMeet(1, 0, later, later + 1_000), "空着能接 1 秒截止的");
    }

    @Test
    void cpuSharedColdStartThenTightDeadlinesKeepsDispatching() {
        // CPU 平分（几个一起做每个就慢几倍）、冷启动：前 10 秒截止宽（3.5 秒），之后全是 1 秒的。
        // 原来学到的延迟是 4 个一起时的 2 秒，空着也拒掉所有 1 秒的任务，40 秒 0 派发、学不到新的
        int[] after = new int[2];
        int[] r = DonorThroughputTest.simulate(4, 500, 1_000, 60, false, 10_000, 3_500, 4, 4, 10_000, after, 500,
                running -> 500L * running);
        System.out.println("CPU 平分冷启动后收紧：lanes=" + r[0] + " 变化后派出 " + after[1] + " 迟到 " + after[0]);
        assertTrue(after[1] >= 60, "截止收紧以后照样派：" + after[1]);
        assertTrue(r[0] <= 2, "lanes " + r[0]);
    }

    @Test
    void idleRejectionsAreCountedAndExplorationLetsOneThrough() {
        // 空着却全拒掉：记下来（status 和测试看"需求有没有满足"），过一阵放一个进来刷新样本
        DonorThroughput t = new DonorThroughput();
        t.record(1, 2_000, 10_000, 2_000, 8_000, 4);   // 学到的延迟 2 秒
        long now = 20_000;
        assertTrue(!t.canMeet(1, 0, now, now + 1_000));
        assertTrue(!t.canMeet(1, 0, now + 1_000, now + 2_000));
        assertTrue(t.idleRejects(1) >= 2);
        assertTrue(t.canMeet(1, 0, now + DonorThroughput.EXPLORE_MS + 1, now + DonorThroughput.EXPLORE_MS + 1_001),
                "空着一直拒了 " + DonorThroughput.EXPLORE_MS + " 毫秒：放一个探路");
        assertTrue(!t.canMeet(1, 1, now + DonorThroughput.EXPLORE_MS + 2, now + DonorThroughput.EXPLORE_MS + 1_002),
                "手上有了就不再放");
    }
}
