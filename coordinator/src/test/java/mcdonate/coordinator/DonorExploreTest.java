package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        t.record(1, 2_000, 8_000, 2_000, 8_000, 4);    // 预热那批（11-10 起只有预热样本时会马上放测量）
        t.record(1, 2_000, 10_000, 4_000, 16_000, 4);  // 正常的：学到的延迟 2 秒
        long now = 20_000;
        assertTrue(!t.canMeet(1, 0, now, now + 1_000));
        assertTrue(!t.canMeet(1, 0, now + 1_000, now + 2_000));
        assertTrue(t.idleRejects(1) >= 2);
        assertTrue(t.canMeet(1, 0, now + DonorThroughput.EXPLORE_MS + 1, now + DonorThroughput.EXPLORE_MS + 1_001),
                "空着一直拒了 " + DonorThroughput.EXPLORE_MS + " 毫秒：放一个探路");
        t.leased(1, now + DonorThroughput.EXPLORE_MS + 1);
        assertTrue(!t.canMeet(1, 1, now + DonorThroughput.EXPLORE_MS + 2, now + DonorThroughput.EXPLORE_MS + 1_002),
                "手上有了就不再放");
    }

    @Test
    void pruningLeavesOneTaskForAnIdleExploration() {
        // codex-audit 第二十一节 R3：tick 里先剪枝再派活。空着的 donor 学到 2 秒、每秒来一个只剩 1 秒的前方 tile：
        // 全在剪枝时被剪掉，canMeet 从来没被问，"空着全拒 5 秒放一个"永远不触发。按生产顺序重放 12 秒
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(20_000);
        TaskQueue q = new TaskQueue(clock::get, 30_000, 3, new Features("deadline"));
        DonorThroughput t = new DonorThroughput();
        t.record(1, 2_000, 8_000, 2_000, 8_000, 4);
        t.record(1, 2_000, 10_000, 4_000, 16_000, 4);
        int assigned = 0;
        for (int s = 0; s < 12; s++) {
            long now = clock.get();
            q.enqueue(s, 0);
            q.setDue(s, 0, now + 1_000 + TaskQueue.IMPORT_MARGIN_MS, now + 1_000 + TaskQueue.IMPORT_MARGIN_MS, 0);
            CoordinatorServer.pruneAndNote(q, t, java.util.List.of(1), now);
            java.util.Optional<TaskQueue.Assignment> a = q.assign(1, 4, due -> t.canMeet(1, q.inFlight(1), now, due));
            if (a.isPresent()) {
                t.leased(1, now);
                assigned++;
                q.onResult(1, a.get().taskId(), true);
            }
            clock.addAndGet(1_000);
        }
        assertTrue(assigned >= 1 && t.explorations(1) >= 1, "剪枝给空着的探路留一个：派出 " + assigned + "，探路 " + t.explorations(1));
        assertTrue(assigned <= 3, "只是偶尔放一个：" + assigned);
    }

    @Test
    void idleExplorationRestartsLanesFromOne() {
        // codex-audit 第二十一节 R4：CPU 平分，学到的是 4 个一起的 2 秒；探路单独 500 ms 回来后原来照旧 lanes 4，
        // 一下补满 4 个、4 个一起又是 2 秒，1 秒截止的 40 个里 36 个迟到。改：探路派出时 lanes 回到 1、重新往上探
        DonorThroughput t = new DonorThroughput();
        LoadMeter m = new LoadMeter();
        long now = 10_000;
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 4; i++) {
                m.start(now);
            }
            now += 2_000;
            for (int k = 1; k <= 4; k++) {
                long[] r = m.read(now);
                t.record(1, 2_000, now, r[0], r[1], k);
                m.end(now);
            }
        }
        assertTrue(!t.canMeet(1, 0, now, now + 1_000), "学到 2 秒：空着也拒 1 秒的");
        long later = now + DonorThroughput.EXPLORE_MS + 1;
        assertTrue(t.canMeet(1, 0, later, later + 1_000), "拒了 EXPLORE_MS：放一个探路");
        assertTrue(t.leased(1, later), "派出的是探路");
        assertEquals(1, t.lanes(1), "探路时 lanes 回到 1");
        m.start(later);
        long back = later + 500;
        long[] r = m.read(back);
        t.record(1, 500, back, r[0], r[1], 1);
        m.end(back);
        assertEquals(500, t.laneLatencyMs(1), "探路那个当新的单独基线");
        assertTrue(t.canMeet(1, 0, back, back + 1_000), "空着：1 秒的能接");
        assertEquals(1, t.inFlightCap(1, 4), "在途上限 1：不按 4 条并行补满（往上探要占满 1 条时连着几个快才 +1）");
    }
}
