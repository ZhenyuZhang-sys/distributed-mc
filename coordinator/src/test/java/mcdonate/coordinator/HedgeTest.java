package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import mcdonate.coordinator.TaskQueue.Action;
import mcdonate.coordinator.TaskQueue.State;
import org.junit.jupiter.api.Test;

/** 第 7 步 7.4：对冲。 */
class HedgeTest {
    @Test
    void onTrackNotHedged() {
        // 平均 3 秒，派出后 1 秒、deadline 还有 4 秒：预计 3 秒时做完，赶得上
        assertFalse(Hedge.shouldHedge(0, 3_000, 1_000, 5_000));
        // 做到一半，剩余时间已经小于平均耗时，但预计仍赶得上：不撤
        assertFalse(Hedge.shouldHedge(0, 3_000, 2_500, 3_500));
    }

    @Test
    void lateHedged() {
        // 已经 4 秒了还没回来（比平均慢），预计再要 1.5 秒，deadline 在 5 秒：撤
        assertTrue(Hedge.shouldHedge(0, 3_000, 4_000, 5_000));
        // 一派出就注定赶不上
        assertTrue(Hedge.shouldHedge(0, 3_000, 0, 2_000));
    }

    @Test
    void noDeadlineNeverHedged() {
        assertFalse(Hedge.shouldHedge(0, 3_000, 100_000, Long.MAX_VALUE));
    }

    @Test
    void queueHedgeCancelsAndCountsWaste() {
        AtomicLong now = new AtomicLong(1_000);
        TaskQueue q = new TaskQueue(now::get, 30_000, 3);
        q.enqueue(0, 0);
        long id = q.assign(7).orElseThrow().taskId();
        assertEquals(1_000, q.leasedAt(id));
        assertEquals(7, q.hedgeToServer(id), "要给原来的 worker 发 cancel");
        assertEquals(State.DROPPED, q.state(id));
        assertEquals(1, q.hedgeCancelled());
        // 结果后来才回来：不导入，算浪费
        assertEquals(Action.DISCARD, q.onResult(7, id, true).action());
        assertEquals(1, q.hedgeWasted());
        assertEquals(-1, q.hedgeToServer(id), "不是 LEASED 的不能再撤");
    }
}
