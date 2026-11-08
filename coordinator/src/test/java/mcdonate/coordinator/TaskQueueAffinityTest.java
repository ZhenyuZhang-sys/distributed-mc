package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import mcdonate.coordinator.TaskQueue.Assignment;
import mcdonate.coordinator.TaskQueue.State;
import org.junit.jupiter.api.Test;

/** docs/step6-plan.md 0.3 派任务：认主和"包一片"。 */
class TaskQueueAffinityTest {
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final TaskQueue q = new TaskQueue(now::get, 30_000, 3);

    @Test
    void hardAffinityOnlyToThatWorld() {
        q.enqueue(0, 0, "W");
        assertTrue(q.assign(1).isEmpty(), "还不知道 worker 1 的世界，不派");
        q.setWorkerWorld(2, "V");
        assertTrue(q.assign(2).isEmpty(), "世界不对，不派");
        q.setWorkerWorld(3, "W");
        assertEquals(0, q.assign(3).orElseThrow().tx());
    }

    @Test
    void freeTaskBindsNeighboursToSameWorker() {
        // 派出 (0,0) 给 worker 1 后，队列里相邻的 (1,0) 归 worker 1；不相邻的 (5,5) 谁都能领
        q.enqueue(0, 0);
        q.enqueue(1, 0);
        q.enqueue(5, 5);
        assertEquals(0, q.assign(1).orElseThrow().tx());
        Assignment b = q.assign(2).orElseThrow();
        assertEquals(5, b.tx(), "worker 2 跳过归了 worker 1 的 (1,0)");
        q.onResult(1, q.find(0, 0), true);
        assertEquals(1, q.assign(1).orElseThrow().tx());
    }

    @Test
    void diagonalNeighbourAlsoBound() {
        q.enqueue(0, 0);
        q.enqueue(1, 1);
        q.assign(1);
        assertTrue(q.assign(2).isEmpty());
    }

    @Test
    void softBindingReleasedWhenWorkerGone() {
        q.enqueue(0, 0);
        q.enqueue(1, 0);
        q.assign(1);
        assertTrue(q.assign(2).isEmpty());
        q.workerGone(1);
        // (0,0) 回到队首、(1,0) 解绑，worker 2 都能领
        assertEquals(0, q.assign(2).orElseThrow().tx());
    }

    @Test
    void hardAffinityDroppedWhenWorldAbsentLong() {
        long id = q.enqueue(0, 0, "W") ? q.find(0, 0) : -1;
        assertEquals(List.of(), q.dropOrphans(Set.of()), "刚放进来，不到 60 秒");
        now.addAndGet(59_000);
        assertEquals(List.of(), q.dropOrphans(Set.of()));
        now.addAndGet(2_000);
        assertEquals(List.of(id), q.dropOrphans(Set.of()));
        assertEquals(State.DROPPED, q.state(id));
        assertTrue(q.enqueue(0, 0, "W"), "丢掉的可以再放");
    }

    @Test
    void hardAffinityKeptWhileWorldConnected() {
        long id = q.enqueue(0, 0, "W") ? q.find(0, 0) : -1;
        now.addAndGet(120_000);
        assertEquals(List.of(), q.dropOrphans(Set.of("W")));
        assertEquals(State.QUEUED, q.state(id));
    }

    @Test
    void countsIncludeDropped() {
        assertEquals("{\"queued\":0,\"leased\":0,\"done\":0,\"failed\":0,\"dropped\":0}", q.countsJson());
    }
}
