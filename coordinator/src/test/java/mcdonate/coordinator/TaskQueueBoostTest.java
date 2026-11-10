package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 11-09 加速模式：开了加速的 donor 空着时做后台 tile（像 Chunky 一圈一圈往外）。优先级最低：
 * 只给开了加速的、队里没有它能做的有截止任务、它手上也没有有截止的任务时才派；后台的最多占它一半的在途名额。
 * 找需求算过、没有截止时间的（侧后方）对不开加速的照旧不派，对开了加速的也算后台。
 */
class TaskQueueBoostTest {
    private final AtomicLong now = new AtomicLong(1_000_000);

    private TaskQueue queue(String features) {
        return new TaskQueue(now::get, 30_000, 3, new Features(features));
    }

    private static java.util.Optional<TaskQueue.Assignment> assign(TaskQueue q, int worker, int max, boolean boost) {
        return q.assign(worker, max, due -> true, boost);
    }

    @Test
    void backgroundOnlyToBoostDonors() {
        TaskQueue q = queue("deadline");
        assertTrue(q.enqueueBackground(50, 50, null));
        assertTrue(assign(q, 1, 4, false).isEmpty(), "没开加速的不做后台");
        assertEquals(50, assign(q, 2, 4, true).orElseThrow().tx());
        assertEquals(1, q.backgroundLeased(2));
    }

    @Test
    void backgroundOnlyToBoostDonorsWhenDeadlineOff() {
        TaskQueue q = queue("");
        q.enqueueBackground(50, 50, null);
        q.enqueue(1, 1);
        assertEquals(1, assign(q, 1, 4, false).orElseThrow().tx(), "先进先出也跳过后台的");
        assertTrue(assign(q, 1, 4, false).isEmpty());
        assertEquals(50, assign(q, 2, 4, true).orElseThrow().tx());
    }

    @Test
    void deadlineTasksFirst() {
        TaskQueue q = queue("deadline");
        q.enqueueBackground(50, 50, null);
        q.enqueue(5, 0);
        q.setDue(5, 0, now.get() + 9_000, now.get() + 7_000, 1);
        assertEquals(5, assign(q, 1, 4, true).orElseThrow().tx(), "有截止的先派，后台排最后");
    }

    @Test
    void noBackgroundWhileHoldingDeadlineTask() {
        TaskQueue q = queue("deadline");
        q.enqueue(5, 0);
        q.setDue(5, 0, now.get() + 9_000, now.get() + 7_000, 1);
        long id = assign(q, 1, 4, true).orElseThrow().taskId();
        q.enqueueBackground(50, 50, null);
        assertTrue(assign(q, 1, 4, true).isEmpty(), "手上有有截止的任务：后台不派，名额留给前方");
        assertEquals(TaskQueue.Action.IMPORT, q.onResult(1, id, true).action());
        assertEquals(50, assign(q, 1, 4, true).orElseThrow().tx());
    }

    @Test
    void backgroundUsesAtMostHalfTheSlots() {
        TaskQueue q = queue("deadline");
        for (int i = 0; i < 6; i++) {
            q.enqueueBackground(50 + 2 * i, 50, null);
        }
        assertTrue(assign(q, 1, 4, true).isPresent());
        assertTrue(assign(q, 1, 4, true).isPresent());
        assertTrue(assign(q, 1, 4, true).isEmpty(), "4 个名额后台最多占 2 个，前方任务来了马上有空位");
        // codex-audit 第十七节 B2：只有 1 个名额时不做后台（原来允许，唯一的名额被后台占着，急活进不来）
        assertTrue(assign(q, 2, 1, true).isEmpty(), "只有 1 个名额时不做后台");
    }

    @Test
    void singleLaneLeavesAdmissionForUrgentWork() {
        TaskQueue q = queue("deadline");
        q.enqueueBackground(50, 50, null);
        assertTrue(assign(q, 1, 1, true).isEmpty());
        q.enqueue(5, 0);
        q.setDue(5, 0, now.get() + 9_000, now.get() + 7_000, 1);
        assertEquals(5, assign(q, 1, 1, true).orElseThrow().tx(), "急活来了能马上派");
    }

    @Test
    void capShrinkRevokesExcessBackground() {
        // B2：上限 8 时派了 4 个后台，之后关了加速（上限 4）：后台超过一半的收回，前方的有空位
        TaskQueue q = queue("deadline");
        for (int i = 0; i < 6; i++) {
            q.enqueueBackground(50 + 2 * i, 50, null);
        }
        for (int i = 0; i < 4; i++) {
            assertTrue(assign(q, 1, 8, true).isPresent());
        }
        assertEquals(4, q.inFlight(1));
        java.util.List<TaskQueue.Cancelled> back = q.revokeExcessBackground(1, 4);
        assertEquals(2, back.size(), "4 个名额：后台最多留 2 个");
        assertEquals(2, q.inFlight(1));
        assertEquals(TaskQueue.State.QUEUED, q.state(back.get(0).taskId()), "收回的回到队里，以后还能做");
        q.enqueue(5, 0);
        q.setDue(5, 0, now.get() + 9_000, now.get() + 7_000, 1);
        assertEquals(5, assign(q, 1, 4, false).orElseThrow().tx());
        assertTrue(q.revokeExcessBackground(1, 4).isEmpty(), "没超就不收");
    }

    @Test
    void promotedInFlightBackgroundBlocksFurtherBackground() {
        // B3：派出去的后台 A 后来有了截止（玩家转过来了）：它就是前方任务，手上有它时不再补后台
        TaskQueue q = queue("deadline");
        q.enqueueBackground(50, 50, null);
        q.enqueueBackground(60, 60, null);
        assertEquals(50, assign(q, 1, 4, true).orElseThrow().tx());
        q.setDue(50, 50, now.get() + 9_000, now.get() + 7_000, 1);
        assertTrue(assign(q, 1, 4, true).isEmpty(), "手上有有截止的任务（转成前方的 A）：不补后台");
        assertTrue(q.revokeExcessBackground(1, 1).isEmpty(), "转成前方的不当后台收回");
        assertTrue(!q.currentlyBackground(q.find(50, 50)), "A 现在是前方任务");
    }

    @Test
    void sideTilesWithoutDeadlineGoToBoostDonorsAsBackground() {
        TaskQueue q = queue("deadline");
        q.enqueue(0, 3);
        q.setDue(0, 3, Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE);   // 找需求算过，玩家不会去
        assertTrue(assign(q, 1, 4, false).isEmpty(), "不开加速：照旧不派");
        assertEquals(0, assign(q, 2, 4, true).orElseThrow().tx());
        assertEquals(1, q.backgroundLeased(2));
    }

    @Test
    void finiteDeadlineMakesBackgroundNormal() {
        TaskQueue q = queue("deadline");
        q.enqueueBackground(50, 50, null);
        q.setDue(50, 50, now.get() + 9_000, now.get() + 7_000, 1);   // 玩家飞过来了，进了找需求的范围
        assertEquals(50, assign(q, 1, 4, false).orElseThrow().tx(), "有截止了就是普通任务，谁都能做");
        assertEquals(0, q.backgroundLeased(1));
    }

    @Test
    void dropPassedKeepsBackgroundInsideItsArea() {
        TaskQueue q = queue("deadline");
        q.enqueueBackground(50, 50, null);
        q.enqueueBackground(90, 90, null);
        TaskQueue.Stale s = q.dropPassed((x, z) -> false, (x, z) -> x < 60);
        assertEquals(1, s.dropped().size(), "离开后台范围的删掉，范围里的留着");
        assertEquals(50, assign(q, 1, 4, true).orElseThrow().tx());
    }

    @Test
    void countsAcceptedBackground() {
        TaskQueue q = queue("deadline");
        q.enqueueBackground(50, 50, null);
        long id = assign(q, 2, 4, true).orElseThrow().taskId();
        assertEquals(TaskQueue.Action.IMPORT, q.onResult(2, id, true).action());
        q.markImported(id, "w");
        assertEquals(1, q.backgroundAccepted(2));
        assertEquals(0, q.backgroundQueued());
    }

    @Test
    void backgroundNotCountedTowardDemandCap() {
        TaskQueue q = queue("deadline");
        q.enqueueBackground(50, 50, null);
        assertEquals(0, q.queuedCountingTowardCap());
        assertEquals(1, q.backgroundQueued());
    }

    @Test
    void revocationPrefersNewestInSameMillisecond() {
        // X4：同一毫秒派出的 4 个后台，上限 8→4 收回 2 个：收最近派的（编号大的，多半还没开始做），不收最早的
        TaskQueue q = queue("deadline");
        for (int i = 0; i < 4; i++) {
            q.enqueueBackground(50 + 2 * i, 50, null);
        }
        long[] ids = new long[4];
        for (int i = 0; i < 4; i++) {
            ids[i] = assign(q, 1, 8, true).orElseThrow().taskId();
        }
        java.util.List<TaskQueue.Cancelled> back = q.revokeExcessBackground(1, 4);
        assertEquals(java.util.Set.of(ids[2], ids[3]), java.util.Set.of(back.get(0).taskId(), back.get(1).taskId()));
    }

    @Test
    void finishedBackgroundBecomesForegroundWhenNeeded() {
        // X3：后台 A 已经交回、排着导入（DONE），玩家飞过来了：找需求给它截止时间，它就该走前方的导入
        TaskQueue q = queue("deadline");
        q.enqueueBackground(50, 50, null);
        long id = assign(q, 1, 4, true).orElseThrow().taskId();
        assertEquals(TaskQueue.Action.IMPORT, q.onResult(1, id, true).action());
        assertTrue(q.currentlyBackground(id));
        q.setDue(50, 50, now.get() + 9_000, now.get() + 7_000, 1);
        assertTrue(!q.currentlyBackground(id), "有了截止：不再是后台");
    }
}
