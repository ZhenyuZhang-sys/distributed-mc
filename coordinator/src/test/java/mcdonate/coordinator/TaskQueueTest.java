package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import mcdonate.coordinator.TaskQueue.Action;
import mcdonate.coordinator.TaskQueue.Assignment;
import mcdonate.coordinator.TaskQueue.Decision;
import mcdonate.coordinator.TaskQueue.State;
import org.junit.jupiter.api.Test;

/** 不涉及认主的队列行为。用到两个 tile 时故意不相邻，免得触发"包一片"（见 TaskQueueAffinityTest）。 */
class TaskQueueTest {
    private static final long LEASE = 30_000;
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final TaskQueue q = new TaskQueue(now::get, LEASE, 3);

    private Assignment assign(int worker) {
        return q.assign(worker).orElseThrow();
    }

    @Test
    void enqueueDeduplicates() {
        assertTrue(q.enqueue(1, 2));
        assertFalse(q.enqueue(1, 2));
        Assignment a = assign(7);
        assertFalse(q.enqueue(1, 2), "租出去的也算");
        q.onResult(7, a.taskId(), true);
        assertFalse(q.enqueue(1, 2), "做完的不再放");
        assertEquals(1, q.count(State.DONE));
    }

    @Test
    void idleWorkerTakesHeadInOrder() {
        q.enqueue(1, 1);
        q.enqueue(5, 5);
        Assignment a = assign(1);
        assertEquals(1, a.tx());
        assertEquals(LEASE, a.leaseMs());
        assertTrue(q.assign(1).isEmpty(), "手上有任务的 worker 不再拿");
        assertEquals(5, assign(2).tx());
        assertTrue(q.assign(3).isEmpty(), "队列空了");
    }

    @Test
    void leaseExpiresBackToHead() {
        q.enqueue(1, 1);
        q.enqueue(5, 5);
        Assignment a = assign(1);
        now.addAndGet(LEASE - 1);
        assertEquals(List.of(), q.expire());
        now.addAndGet(2);
        assertEquals(List.of(a.taskId()), q.expire());
        assertEquals(State.QUEUED, q.state(a.taskId()));
        assertEquals(1, assign(2).tx(), "到期的回到队首");
        assertTrue(q.assign(1).isPresent(), "原 worker 的租约放掉了，可以再领");
    }

    @Test
    void heartbeatRenewsLease() {
        q.enqueue(1, 1);
        Assignment a = assign(1);
        now.addAndGet(LEASE - 1000);
        q.heartbeat(1, a.taskId());
        now.addAndGet(LEASE - 1000);
        assertEquals(List.of(), q.expire(), "续过租，不到期");
        now.addAndGet(2000);
        assertEquals(List.of(a.taskId()), q.expire());
    }

    @Test
    void heartbeatFromOtherWorkerDoesNotRenew() {
        q.enqueue(1, 1);
        Assignment a = assign(1);
        now.addAndGet(LEASE - 1000);
        q.heartbeat(2, a.taskId());
        now.addAndGet(2000);
        assertEquals(List.of(a.taskId()), q.expire());
    }

    @Test
    void workerGoneReturnsTaskToHeadImmediately() {
        q.enqueue(1, 1);
        q.enqueue(5, 5);
        Assignment a = assign(1);
        assertEquals(List.of(a.taskId()), q.workerGone(1));
        assertEquals(1, assign(2).tx());
    }

    // ---- 2.4 的状态表：ok = true ----

    @Test
    void okFromLeaseHolderImportsAndMarksDone() {
        q.enqueue(1, 1);
        Assignment a = assign(1);
        Decision d = q.onResult(1, a.taskId(), true);
        assertEquals(Action.IMPORT, d.action());
        assertEquals(Optional.empty(), d.cancelWorker());
        assertEquals(State.DONE, q.state(a.taskId()));
        assertTrue(q.assign(1).isEmpty(), "队列空了");
    }

    @Test
    void lateOkWhileReleasedToOtherImportsAndCancelsOther() {
        q.enqueue(1, 1);
        Assignment a = assign(1);
        now.addAndGet(LEASE + 1);
        q.expire();
        Assignment b = assign(2);
        assertEquals(a.taskId(), b.taskId());
        Decision d = q.onResult(1, a.taskId(), true);
        assertEquals(Action.IMPORT, d.action());
        assertEquals(Optional.of(2), d.cancelWorker());
        assertEquals(State.DONE, q.state(a.taskId()));
        assertTrue(q.assign(2).isEmpty(), "worker 2 被撤掉后空闲，但队列已空");
        // worker 2 后来又发了结果：丢弃
        assertEquals(Action.DISCARD, q.onResult(2, a.taskId(), true).action());
    }

    @Test
    void lateOkWhileQueuedImportsAndRemovesFromQueue() {
        q.enqueue(1, 1);
        Assignment a = assign(1);
        now.addAndGet(LEASE + 1);
        q.expire();
        assertEquals(Action.IMPORT, q.onResult(1, a.taskId(), true).action());
        assertEquals(State.DONE, q.state(a.taskId()));
        assertTrue(q.assign(3).isEmpty(), "从队列里拿掉了");
    }

    @Test
    void duplicateOkAfterDoneIsDiscarded() {
        q.enqueue(1, 1);
        Assignment a = assign(1);
        q.onResult(1, a.taskId(), true);
        Decision d = q.onResult(1, a.taskId(), true);
        assertEquals(Action.DISCARD, d.action());
        assertTrue(d.reason().contains("重复"), d.reason());
    }

    @Test
    void unknownTaskIsDiscarded() {
        assertEquals(Action.DISCARD, q.onResult(1, 999, true).action());
        assertEquals(Action.DISCARD, q.onResult(1, 999, false).action());
    }

    @Test
    void importFailureReturnsTaskToQueue() {
        q.enqueue(1, 1);
        Assignment a = assign(1);
        q.onResult(1, a.taskId(), true);
        q.importFailed(a.taskId());
        assertEquals(State.QUEUED, q.state(a.taskId()));
        assertEquals(a.taskId(), assign(2).taskId());
    }

    // ---- 2.4 的状态表：ok = false ----

    @Test
    void failureFromLeaseHolderRequeuesAtTail() {
        q.enqueue(1, 1);
        q.enqueue(5, 5);
        Assignment a = assign(1);
        assertEquals(Action.REQUEUE, q.onResult(1, a.taskId(), false).action());
        assertEquals(5, assign(1).tx(), "失败的放到队尾");
        assertEquals(1, assign(2).tx());
    }

    @Test
    void failsAfterThreeAttempts() {
        q.enqueue(1, 1);
        for (int i = 1; i <= 2; i++) {
            Assignment a = assign(1);
            assertEquals(Action.REQUEUE, q.onResult(1, a.taskId(), false).action());
        }
        Assignment a = assign(1);
        assertEquals(Action.FAIL, q.onResult(1, a.taskId(), false).action());
        assertEquals(State.FAILED, q.state(a.taskId()));
        assertTrue(q.assign(1).isEmpty());
        assertTrue(q.enqueue(1, 1), "失败的可以手动重新放");
    }

    @Test
    void failureFromOldLeaseHolderIsIgnored() {
        q.enqueue(1, 1);
        Assignment a = assign(1);
        now.addAndGet(LEASE + 1);
        q.expire();
        assign(2);
        assertEquals(Action.DISCARD, q.onResult(1, a.taskId(), false).action());
        assertEquals(State.LEASED, q.state(a.taskId()), "worker 2 还在做");
    }

    @Test
    void failureWhileQueuedIsIgnored() {
        q.enqueue(1, 1);
        Assignment a = assign(1);
        now.addAndGet(LEASE + 1);
        q.expire();
        assertEquals(Action.DISCARD, q.onResult(1, a.taskId(), false).action());
        assertEquals(State.QUEUED, q.state(a.taskId()));
    }

    @Test
    void failureAfterDoneIsDiscarded() {
        q.enqueue(1, 1);
        Assignment a = assign(1);
        q.onResult(1, a.taskId(), true);
        assertEquals(Action.DISCARD, q.onResult(1, a.taskId(), false).action());
        assertEquals(State.DONE, q.state(a.taskId()));
    }

    @Test
    void statusJson() {
        q.enqueue(1, 1);
        q.enqueue(5, 5);
        assign(5);
        assertEquals("{\"queued\":1,\"leased\":1,\"done\":0,\"failed\":0,\"dropped\":0}", q.countsJson());
    }
}
