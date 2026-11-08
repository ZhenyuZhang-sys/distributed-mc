package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
