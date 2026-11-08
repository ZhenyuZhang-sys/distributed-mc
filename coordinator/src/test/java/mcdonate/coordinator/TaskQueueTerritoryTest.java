package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import mcdonate.coordinator.TaskQueue.State;
import org.junit.jupiter.api.Test;

/** 第 7 步 7.1：按领地派任务。 */
class TaskQueueTerritoryTest {
    private final AtomicLong now = new AtomicLong(1_000_000);

    private TaskQueue queue(boolean territory) {
        return new TaskQueue(now::get, 30_000, 3, new Features(territory ? "territory" : ""));
    }

    @Test
    void nextToImportedOnlyToThatWorld() {
        TaskQueue q = queue(true);
        q.setWorkerWorld(1, "W");
        q.setWorkerWorld(2, "V");
        q.enqueue(0, 0);
        long a = q.assign(1).orElseThrow().taskId();
        q.onResult(1, a, true);
        q.markImported(a, "W");
        q.enqueue(1, 0); // 挨着 W 导入的 (0,0)
        assertTrue(q.assign(2).isEmpty(), "V 的 worker 不该领");
        assertEquals(1, q.assign(1).orElseThrow().tx());
    }

    @Test
    void nextToLeasedOnlyToThatWorker() {
        TaskQueue q = queue(true);
        q.enqueue(0, 0);
        q.assign(1); // worker 1 租着 (0,0)
        q.enqueue(0, 1);
        assertTrue(q.assign(2).isEmpty());
    }

    @Test
    void betweenTwoDonorsDroppedToServer() {
        TaskQueue q = queue(true);
        q.setWorkerWorld(1, "W");
        q.setWorkerWorld(2, "V");
        q.enqueue(0, 0);
        q.enqueue(2, 0);
        long a = q.assign(1).orElseThrow().taskId();
        long b = q.assign(2).orElseThrow().taskId();
        q.onResult(1, a, true);
        q.markImported(a, "W");
        q.onResult(2, b, true);
        q.markImported(b, "V");
        q.enqueue(1, 0); // 夹在 W 和 V 中间
        assertTrue(q.assign(1).isEmpty());
        assertTrue(q.assign(2).isEmpty());
        assertEquals(State.DROPPED, q.state(q.find(1, 0)));
    }

    @Test
    void discardedImportIsNotTerritory() {
        TaskQueue q = queue(true);
        q.setWorkerWorld(1, "W");
        q.enqueue(0, 0);
        long a = q.assign(1).orElseThrow().taskId();
        q.onResult(1, a, true); // 结果整个丢弃：不调 markImported
        q.enqueue(1, 0);
        q.setWorkerWorld(2, "V");
        assertEquals(1, q.assign(2).orElseThrow().tx());
    }

    @Test
    void offMeansV0() {
        TaskQueue q = queue(false);
        q.setWorkerWorld(1, "W");
        q.setWorkerWorld(2, "V");
        q.enqueue(0, 0);
        long a = q.assign(1).orElseThrow().taskId();
        q.onResult(1, a, true);
        q.markImported(a, "W");
        q.enqueue(1, 0);
        assertEquals(1, q.assign(2).orElseThrow().tx(), "关着时照第 6 步：谁都能领");
    }
}
