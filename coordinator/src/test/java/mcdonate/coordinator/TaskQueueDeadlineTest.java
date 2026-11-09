package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import mcdonate.coordinator.TaskQueue.State;
import org.junit.jupiter.api.Test;

/** 第 7 步 7.2（due 排序、丢掉来不及的）、7.3（一个 worker 同时租几个、慢的只派来得及的）、7.4 用的租约列表。 */
class TaskQueueDeadlineTest {
    private final AtomicLong now = new AtomicLong(1_000_000);

    private TaskQueue queue(String features) {
        return new TaskQueue(now::get, 30_000, 3, new Features(features));
    }

    @Test
    void fifoWhenDeadlineOff() {
        TaskQueue q = queue("");
        q.enqueue(0, 0);
        q.enqueue(5, 5);
        q.setDue(5, 5, now.get() + 1_000);
        assertEquals(0, q.assign(1).orElseThrow().tx(), "开关关着：先进先出，不看 due");
    }

    @Test
    void earliestDueFirst() {
        TaskQueue q = queue("deadline");
        q.enqueue(0, 0);
        q.enqueue(5, 5);
        q.enqueue(9, 9);
        q.setDue(0, 0, now.get() + 20_000);
        q.setDue(9, 9, now.get() + 3_000);
        // (5,5) 不知道 due：排在知道的后面
        assertEquals(9, q.assign(1).orElseThrow().tx());
        assertEquals(0, q.assign(2).orElseThrow().tx());
        assertEquals(5, q.assign(3).orElseThrow().tx());
    }

    @Test
    void lateQueuedTasksDropped() {
        TaskQueue q = queue("deadline");
        q.enqueue(0, 0);
        q.enqueue(5, 5);
        long a = q.find(0, 0);
        q.setDue(0, 0, now.get() + 1_000);
        q.setDue(5, 5, now.get() + 10_000);
        now.addAndGet(2_000);
        assertEquals(List.of(a), q.dropLate());
        assertEquals(State.DROPPED, q.state(a));
        assertEquals(5, q.assign(1).orElseThrow().tx());
    }

    // ---- 7.2 的漏洞（11-09 f3 实测：几轮里 donor 写入的 chunk 几乎全在玩家身后）----
    // 计划写了"玩家已经到了的任务直接丢掉"，实现只丢了 due 已过的；飞过的 tile 没有将来的到达时间（due 是无穷大），
    // 一直留在队列里，别的做完了照样派。开 deadline 时：飞过了（due 无穷大）、又不在任何玩家当前需求范围里的，删掉；派出去的撤回。

    @Test
    void passedTilesOutsideDemandAreDroppedAndLeasedOnesCancelled() {
        TaskQueue q = queue("deadline");
        // 玩家沿 +x 飞，tile 0..4 在身后，10 在前面
        for (int x : new int[] {0, 2, 4, 10}) {
            q.enqueue(x, 0);
        }
        q.enqueue(30, 30); // 从来没被找需求算过 due（比如手动派的）：不动
        TaskQueue.Assignment leased = q.assign(7).orElseThrow(); // 派出去一个（tile 0）
        for (int x : new int[] {0, 2, 4}) {
            q.setDue(x, 0, Long.MAX_VALUE); // 飞过了：将来不会再看到
        }
        q.setDue(10, 0, now.get() + 5_000);
        // 当前需求范围：只有 x >= 3 的（tile 4 还在范围里，即使飞过了也留着）
        TaskQueue.Stale stale = q.dropPassed((tx, tz) -> tx >= 3);
        assertEquals(List.of(q.find(2, 0)), stale.dropped(), "排队中、飞过了、不在范围里的删掉");
        assertEquals(List.of(new TaskQueue.Cancelled(leased.taskId(), 7)), stale.cancelled(), "派出去的撤回、发 cancel");
        assertEquals(State.DROPPED, q.state(leased.taskId()));
        assertEquals(State.QUEUED, q.state(q.find(4, 0)), "还在范围里的留着");
        assertEquals(State.QUEUED, q.state(q.find(10, 0)), "前面的留着");
        assertEquals(State.QUEUED, q.state(q.find(30, 30)), "没算过 due 的不动");
        assertEquals(0, q.inFlight(7));
    }

    @Test
    void inDemandAreaIsChebyshevAroundAnyPlayer() {
        long p = ((long) 0 << 32) | (100 & 0xffffffffL); // 玩家在 chunk (100, 0)
        // tile 大小 4、outer 12：tile 28 覆盖 chunk 112..115，离 100 是 12 圈，在；tile 29 是 16 圈，不在
        assertTrue(DemandScanner.inArea(28, 0, new long[] {p}, 12, 4));
        assertTrue(!DemandScanner.inArea(29, 0, new long[] {p}, 12, 4));
        assertTrue(DemandScanner.inArea(22, -3, new long[] {p}, 12, 4), "负坐标：tile -3 覆盖 chunk -12..-9");
        assertTrue(!DemandScanner.inArea(0, 0, new long[0], 12, 4), "没有玩家：都不在范围里");
    }

    @Test
    void passedTilesKeptWhenDeadlineOff() {
        TaskQueue q = queue(""); // v0（先来先服务）是对照组，保持原样
        q.enqueue(0, 0);
        q.setDue(0, 0, Long.MAX_VALUE);
        TaskQueue.Stale stale = q.dropPassed((tx, tz) -> false);
        assertTrue(stale.dropped().isEmpty() && stale.cancelled().isEmpty());
    }

    @Test
    void severalLeasesPerWorker() {
        TaskQueue q = queue("throughput");
        for (int i = 0; i < 5; i++) {
            q.enqueue(10 * i, 0); // 互不相邻，不触发"包一片"
        }
        int n = 0;
        while (q.assign(1, 3, due -> true).isPresent()) {
            n++;
        }
        assertEquals(3, n, "最多同时 3 个");
        assertEquals(3, q.inFlight(1));
        // 默认的 assign(worker) 仍然一次一个
        assertTrue(q.assign(2).isPresent());
        assertTrue(q.assign(2).isEmpty());
    }

    @Test
    void workerGoneReturnsAllItsLeases() {
        TaskQueue q = queue("throughput");
        q.enqueue(0, 0);
        q.enqueue(10, 0);
        q.assign(1, 2, due -> true);
        q.assign(1, 2, due -> true);
        assertEquals(2, q.workerGone(1).size());
        assertEquals(0, q.inFlight(1));
        assertEquals(2, q.count(State.QUEUED));
    }

    @Test
    void slowWorkerOnlyGetsTasksItCanMeet() {
        TaskQueue q = queue("deadline,throughput");
        q.enqueue(0, 0);
        q.enqueue(10, 0);
        q.setDue(0, 0, now.get() + 2_000);
        q.setDue(10, 0, now.get() + 60_000);
        long t = now.get();
        // 这个 worker 做一个要 5 秒：2 秒后就要的派不了，只派 60 秒后的
        var a = q.assign(1, 1, due -> t + 5_000 <= due).orElseThrow();
        assertEquals(10, a.tx());
    }

    @Test
    void resultFreesOnlyThatLease() {
        TaskQueue q = queue("throughput");
        q.enqueue(0, 0);
        q.enqueue(10, 0);
        long a = q.assign(1, 2, due -> true).orElseThrow().taskId();
        q.assign(1, 2, due -> true).orElseThrow();
        q.onResult(1, a, true);
        assertEquals(1, q.inFlight(1));
    }

    @Test
    void leasesWithDueForHedging() {
        TaskQueue q = queue("deadline");
        q.enqueue(0, 0);
        q.enqueue(10, 0);
        q.setDue(0, 0, now.get() + 4_000);
        long a = q.assign(1).orElseThrow().taskId();
        q.assign(2).orElseThrow();
        List<TaskQueue.Lease> l = q.leasesWithDue();
        assertEquals(1, l.size(), "不知道 due 的不列");
        assertEquals(a, l.get(0).taskId());
        assertEquals(1, q.hedgeToServer(a));
        assertEquals(0, q.inFlight(1));
        assertTrue(q.leasesWithDue().isEmpty());
    }

    // ---- 11-09 调度：来不及导入的不派（7.3 的 canMeet，不开 throughput 也生效）----
    // importBy = 预计这个 tile 的区域进服务器前沿的时间；之后交回会被判"在内存里"整个丢掉。
    // 派之前用 dueOk 问"这个 donor 在 importBy − 余量之前做得完吗"，做不完的留在队列里（快的 donor 可能做得完），
    // importBy 过了还没派的丢掉，交给服务器。

    @Test
    void dispatchSkipsTilesThatCannotBeImportedInTime() {
        TaskQueue q = queue("deadline");
        q.enqueue(0, 0);
        q.enqueue(5, 5);
        q.setDue(0, 0, now.get() + 4_000, now.get() + 2_000);   // 最先看到，但 2 秒后就进前沿
        q.setDue(5, 5, now.get() + 9_000, now.get() + 7_000);
        long[] asked = new long[1];
        var a = q.assign(1, 1, limit -> {
            asked[0] = limit;
            return now.get() + 5_000 <= limit;                   // 这个 donor 要 5 秒
        });
        assertEquals(5, a.orElseThrow().tx(), "(0,0) 来不及，派 (5,5)");
        assertEquals(now.get() + 7_000 - TaskQueue.IMPORT_MARGIN_MS, asked[0], "问的是 importBy 减余量");
        assertEquals(State.QUEUED, q.state(q.find(0, 0)), "来不及的先留着，快的 donor 也许做得完");
    }

    @Test
    void earliestDueFirstAmongFeasible() {
        TaskQueue q = queue("deadline");
        q.enqueue(9, 9);
        q.enqueue(5, 5);
        q.setDue(9, 9, now.get() + 30_000, now.get() + 28_000);
        q.setDue(5, 5, now.get() + 12_000, now.get() + 10_000);
        assertEquals(5, q.assign(1, 1, limit -> true).orElseThrow().tx(), "能做完的里面，玩家最先看到的先派");
    }

    @Test
    void withoutImportByFallsBackToDue() {
        TaskQueue q = queue("deadline");
        q.enqueue(0, 0);
        q.setDue(0, 0, now.get() + 4_000);
        long[] asked = new long[1];
        q.assign(1, 1, limit -> {
            asked[0] = limit;
            return true;
        });
        assertEquals(now.get() + 4_000, asked[0], "不知道前沿时照旧按 due 问");
    }

    @Test
    void importLateQueuedTasksDropped() {
        TaskQueue q = queue("deadline");
        q.enqueue(0, 0);
        q.enqueue(5, 5);
        long a = q.find(0, 0);
        q.setDue(0, 0, now.get() + 6_000, now.get() + 1_000);
        q.setDue(5, 5, now.get() + 20_000, now.get() + 15_000);
        now.addAndGet(2_000);
        assertEquals(List.of(a), q.dropLate(), "区域已经进了服务器前沿：交回也会被丢，不派了");
        assertEquals(State.DROPPED, q.state(a));
        assertTrue(q.staleJson().contains("\"import_late\":1"), q.staleJson());
    }

    @Test
    void enqueueTimeKept() {
        TaskQueue q = queue("");
        q.enqueue(0, 0);
        long id = q.find(0, 0);
        now.addAndGet(700);
        q.assign(1);
        assertEquals(now.get() - 700, q.enqueuedAt(id));
        assertEquals(-1, q.enqueuedAt(999));
    }

    // ---- 11-09 调度：排队排太久（f2 实测入队→派出 p50 4～10 秒、p90 22～43 秒，派出→交回只要约 1.4 秒）----
    // 按 importBy（不知道时按 due）从早到晚排，第 i 个大约在 now + (i + 在租的一半) / 总吞吐 开始、再过一个 tile 的耗时做完；
    // 做完晚于 importBy − 余量的，现在就丢掉交给服务器，不占队列（排在它后面的往前挪）。

    private static final double ONE_PER_2S = 1.0 / 2_000; // 总吞吐：每 2 秒 1 个 tile

    @Test
    void hopelessTasksPruned() {
        TaskQueue q = queue("deadline");
        for (int i = 0; i < 5; i++) {
            q.enqueue(i, 0);
            q.setDue(i, 0, now.get() + 5_000 + i, now.get() + 3_000 + i); // 都是 3 秒后进前沿
        }
        // 第 0 个：0 秒开始、1 秒做完 ≤ 3 − 1 秒，留着；第 1 个：2 秒开始、3 秒做完 > 2 秒，丢；后面的也一样
        List<Long> dropped = q.pruneHopeless(ONE_PER_2S, 1_000);
        assertEquals(4, dropped.size(), dropped.toString());
        assertEquals(State.QUEUED, q.state(q.find(0, 0)), "最急的那个来得及，留着");
        assertTrue(q.staleJson().contains("\"hopeless\":4"), q.staleJson());
    }

    @Test
    void droppedOnesDoNotTakeAPlace() {
        TaskQueue q = queue("deadline");
        q.enqueue(0, 0);
        q.enqueue(1, 0);
        q.enqueue(2, 0);
        // 11-09 起派发和剪枝共用一个预测：now + max(延迟, (前面的个数 + 1) / 速率)（codex-audit F2）
        q.setDue(0, 0, now.get() + 4_000, now.get() + 3_000);   // 第 0 位：max(1, 2) = 2 秒 ≤ 2 秒，留
        q.setDue(1, 0, now.get() + 4_500, now.get() + 3_500);   // 第 1 位：max(1, 4) = 4 秒 > 2.5 秒，丢
        q.setDue(2, 0, now.get() + 6_000, now.get() + 5_000);   // 丢掉的不占位置：仍是第 1 位，4 秒 ≤ 4 秒，留
        assertEquals(List.of(q.find(1, 0)), q.pruneHopeless(ONE_PER_2S, 1_000));
    }

    @Test
    void leasedTasksDelayTheQueue() {
        TaskQueue q = queue("deadline");
        q.enqueue(9, 9);
        q.assign(1);            // 在租 1 个：排队的要等它做完一半
        q.enqueue(0, 0);
        q.setDue(0, 0, now.get() + 3_000, now.get() + 2_500);   // 1 秒后才开始、2 秒做完 > 1.5 秒
        assertEquals(List.of(q.find(0, 0)), q.pruneHopeless(ONE_PER_2S, 1_000));
    }

    @Test
    void unknownDeadlinesKeptAtTheEnd() {
        TaskQueue q = queue("deadline");
        for (int i = 0; i < 4; i++) {
            q.enqueue(i, 0);   // 没有 due（玩家不朝那边走）：永远不丢，也不挤占有 deadline 的位置
        }
        q.enqueue(9, 0);
        // 第 0 位（没有截止的不占位置）：max(1, 2) = 2 秒 ≤ 3.5 − 1 秒，留（11-09 起派发和剪枝共用一个预测）
        q.setDue(9, 0, now.get() + 4_000, now.get() + 3_500);
        assertEquals(List.of(), q.pruneHopeless(ONE_PER_2S, 1_000));
    }

    @Test
    void noPruningWithoutDeadlineOrDonors() {
        TaskQueue off = queue("");
        off.enqueue(0, 0);
        off.enqueue(1, 0);
        off.setDue(1, 0, now.get() + 2_000, now.get() + 1_000);
        assertEquals(List.of(), off.pruneHopeless(ONE_PER_2S, 1_000), "开关关着（v0 对照组）不动");
        TaskQueue q = queue("deadline");
        q.enqueue(0, 0);
        q.setDue(0, 0, now.get() + 2_000, now.get() + 1_000);
        assertEquals(List.of(), q.pruneHopeless(0, 1_000), "没有 donor（吞吐 0）时估不了，不丢");
    }

    @Test
    void slackRecordedAtLease() {
        // 派出时离截止（importBy，不知道时 due）还有多少毫秒；都不知道是 -1。看排队久的是不是没有 deadline 的
        TaskQueue q = queue("deadline");
        q.enqueue(0, 0);
        q.enqueue(5, 0);
        q.setDue(0, 0, now.get() + 9_000, now.get() + 7_000);
        long a = q.find(0, 0), b = q.find(5, 0);
        q.assign(1);
        q.assign(2);
        assertEquals(7_000, q.slackAtLease(a));
        assertEquals(-1, q.slackAtLease(b));
        assertEquals(-1, q.slackAtLease(999));
    }

    @Test
    void heartbeatsCannotRenewForever() {
        // 11-09 用户真机：worker 卡死（主线程在等一个永远不会完成的 chunk），心跳照发，任务续租了 5 分钟，inFlight 一直是 4。
        // 从派出起最多续到 MAX_LEASE_TOTAL_MS，过了就不再续，到期收回重派
        TaskQueue q = queue("");
        q.enqueue(0, 0);
        long id = q.assign(1).orElseThrow().taskId();
        for (long t = 0; t < TaskQueue.MAX_LEASE_TOTAL_MS + 40_000; t += 5_000) {
            now.addAndGet(5_000);
            q.heartbeat(1, id);
            q.expire();
        }
        assertEquals(State.QUEUED, q.state(id), "总时长到了上限，不再续租，收回");
    }

    // ---- 11-09（kexu 烟花直线 33.5 格/秒：donor 写入的 chunk 只有 26% 在玩家前方）----
    // 找需求算过、却没有截止时间的 tile（玩家不朝那边走：侧面、后面）不派；派活先做前方走廊（离飞行线近的），再做两侧

    @Test
    void scoredTilesWithoutDeadlineNotAssigned() {
        TaskQueue q = queue("deadline");
        q.enqueue(0, 0);
        q.setDue(0, 0, Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE);   // 侧后方：找需求算过，到不了
        assertTrue(q.assign(1).isEmpty(), "玩家不会去的 tile 不派");
        q.enqueue(5, 0);
        q.setDue(5, 0, now.get() + 9_000, now.get() + 7_000, 2);
        assertEquals(5, q.assign(1).orElseThrow().tx());
    }

    @Test
    void corridorBeforeSides() {
        TaskQueue q = queue("deadline");
        q.enqueue(0, 3);
        q.enqueue(5, 0);
        q.setDue(0, 3, now.get() + 5_000, now.get() + 4_000, 12);     // 更早看到，但在侧面 12 个 chunk
        q.setDue(5, 0, now.get() + 8_000, now.get() + 7_000, 1);      // 正前方
        assertEquals(5, q.assign(1).orElseThrow().tx(), "走廊里的先派");
        assertEquals(0, q.assign(2).orElseThrow().tx(), "走廊里没有了再派两侧");
    }

    // ---- 11-09 codex-audit A2～A4：任务生命周期 ----

    @Test
    void reenqueueGetsNewIdSoOldResultCannotTakeIt() {
        // A3：撤回（hedge / 飞过）后同一个 tile 又需要了：新任务用新编号，旧编号的结果回来时作废，不能拿走新任务的导入权
        TaskQueue q = queue("deadline");
        q.enqueue(0, 0);
        long old = q.assign(1).orElseThrow().taskId();
        q.hedgeToServer(old);
        assertTrue(q.enqueue(0, 0));
        long fresh = q.find(0, 0);
        assertTrue(fresh != old, "重新需要的是新任务");
        assertEquals(TaskQueue.Action.DISCARD, q.onResult(1, old, true).action(), "撤回过的旧任务的结果作废");
        assertEquals(State.QUEUED, q.state(fresh));
    }

    @Test
    void resetInvalidatesPendingImports() {
        // A2：结果已经交给主线程排着导入，这时 /donor reset：导入前再查，代次变了就不导
        TaskQueue q = queue("");
        long epoch = q.epoch();
        q.reset();
        assertTrue(q.epoch() != epoch);
    }

    @Test
    void repeatedLeaseExpiryFailsTheTask() {
        // A4：卡死的 worker 心跳照发，租约总时长到了上限就收回；收回也算一次失败，连续几次就 FAILED，不能无限拿回
        TaskQueue q = new TaskQueue(now::get, 30_000, 3, new Features(""));
        q.enqueue(0, 0);
        long id = q.find(0, 0);
        List<TaskQueue.Cancelled> cancelled = new java.util.ArrayList<>();
        for (int round = 0; round < 3; round++) {
            q.assign(1);
            for (long t = 0; t <= TaskQueue.MAX_LEASE_TOTAL_MS + 10_000; t += 5_000) {
                now.addAndGet(5_000);
                q.heartbeat(1, id);
                cancelled.addAll(q.expireLeases());
            }
        }
        assertEquals(State.FAILED, q.state(id), "连续 3 次租约到期：放弃");
        assertEquals(3, cancelled.size(), "每次收回都要给原来的 worker 发 cancel");
        assertEquals(1, cancelled.get(0).worker());
    }

    @Test
    void firstLeaseClampedToTotalCap() {
        // A4：没有心跳时第一个租约也不超过总上限
        TaskQueue q = new TaskQueue(now::get, TaskQueue.MAX_LEASE_TOTAL_MS * 2, 3, new Features(""));
        q.enqueue(0, 0);
        long id = q.assign(1).orElseThrow().taskId();
        now.addAndGet(TaskQueue.MAX_LEASE_TOTAL_MS + 1);
        assertEquals(1, q.expireLeases().size());
        assertEquals(State.QUEUED, q.state(id));
    }

    // ---- 11-09（kexu 三臂：30a7c22 的 8 核 donor 只用 1.2 核；codex-audit：侧面的任务占满队列上限后不再扫描）----

    @Test
    void onlyTasksWithDeadlineCountTowardTheCap() {
        TaskQueue q = queue("deadline");
        for (int i = 0; i < 5; i++) {
            q.enqueue(i, 9);
            q.setDue(i, 9, Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE);   // 侧面：不派，也不该占队列名额
        }
        q.enqueue(0, 0);
        q.setDue(0, 0, now.get() + 9_000, now.get() + 7_000, 1);
        q.enqueue(1, 0);                                                         // 刚入队，还没算过：算一个
        assertEquals(2, q.queuedCountingTowardCap());
    }

    @Test
    void dispatchAndPruneAgree() {
        // codex-audit F2：同一组数据，canMeet 说做得完的，剪枝不能剪掉。速率 1 个/秒、延迟 4 秒、手上 3 个在做
        DonorThroughput t = new DonorThroughput();
        for (int i = 0; i < 10; i++) {
            t.record(1, 4_000, 10_000 + i * 1_000L);
        }
        TaskQueue q = queue("deadline");
        for (int i = 0; i < 3; i++) {
            q.enqueue(9, i);
            q.assign(1, 4, d -> true);
        }
        q.enqueue(0, 0);
        long by = now.get() + 5_000 + TaskQueue.IMPORT_MARGIN_MS;     // 4 + 1 = 5 秒能做完
        q.setDue(0, 0, by + 1_000, by, 0);
        assertTrue(t.canMeet(1, q.inFlight(1), now.get(), by - TaskQueue.IMPORT_MARGIN_MS));
        assertEquals(List.of(), q.pruneHopeless(t.ratePerMs(1), t.latencyMs(1)), "派发说做得完的，剪枝不剪");
    }

    @Test
    void droppedTileCanComeBackAfterCooldown() {
        // codex-audit F6：飞过被删、或被剪掉的 tile，玩家转回来、需求重新成立时要能重新入队（冷却 5 秒，免得剪了又放、放了又剪）
        TaskQueue q = queue("deadline");
        q.enqueue(0, 0);
        q.setDue(0, 0, now.get() + 2_000, now.get() + 1_000, 0);
        now.addAndGet(1_500);
        q.dropLate();
        assertFalse(q.canEnqueueAgain(0, 0), "刚丢掉：先不放回去");
        now.addAndGet(TaskQueue.REQUEUE_COOLDOWN_MS);
        assertTrue(q.canEnqueueAgain(0, 0));
        assertTrue(q.enqueue(0, 0));
        assertFalse(q.canEnqueueAgain(0, 0), "已经在排队");
    }

    @Test
    void pruneTileTimeIsTheFastestDonors() {
        // 一个慢 donor（20 秒）不能让快 donor（2 秒）做得完的任务被剪掉：剪枝按最快的那个估一个 tile 的时间
        assertEquals(2_000, CoordinatorServer.pruneTileMs(new long[] {20_000, 2_000}));
        assertEquals(0, CoordinatorServer.pruneTileMs(new long[0]));
    }

    @Test
    void pruneUsesTheIdleFastDonor() {
        // codex-audit G5：剪枝按所有 donor 加起来的在途数排队，慢 donor 手上的 4 个让空着的快 donor 能做完的任务被剪掉。
        // 按每个 donor 分别估，排队的依次给最早做完的那个
        TaskQueue q = queue("deadline");
        q.enqueue(0, 0);
        q.setDue(0, 0, now.get() + 4_000, now.get() + 3_000, 0);           // 2 秒内要做完
        List<TaskQueue.DonorEstimate> donors = List.of(
                new TaskQueue.DonorEstimate(20_000, 1.0 / 20_000, 4),        // 慢的，手上 4 个
                new TaskQueue.DonorEstimate(1_000, 1.0 / 1_000, 0));         // 快的，空着
        assertEquals(List.of(), q.pruneHopeless(donors), "快 donor 1 秒做完，不剪");
        assertEquals(1, q.pruneHopeless(List.of(donors.get(0))).size(), "只有慢的：做不完，剪");
    }

}
