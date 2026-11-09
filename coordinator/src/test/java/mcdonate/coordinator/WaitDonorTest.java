package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 7.6 等 donor：什么时候等、等哪个闸门。 */
class WaitDonorTest {
    private final AtomicLong now = new AtomicLong(1_000_000);

    static long key(int x, int z) {
        return ((long) z << 32) | (x & 0xffffffffL);
    }

    private WaitDonor setup(String features, long avgMs, TaskQueue q, DonorThroughput t) {
        t.record(1, avgMs);
        return new WaitDonor(new Features(features), q, t);
    }

    @Test
    void offMeansNoGate() {
        TaskQueue q = new TaskQueue(now::get, 30_000, 3);
        WaitDonor w = setup("", 1_000, q, new DonorThroughput());
        q.enqueue(0, 0);
        q.assign(1);
        assertNull(w.gateFor(key(1, 1), now.get()));
    }

    @Test
    void leasedTileAndItsRingShareOneGate() {
        TaskQueue q = new TaskQueue(now::get, 30_000, 3);
        WaitDonor w = setup("waitdonor", 1_000, q, new DonorThroughput());
        q.enqueue(0, 0);
        q.assign(1);
        CompletableFuture<Void> inTile = w.gateFor(key(1, 1), now.get());
        CompletableFuture<Void> inRing = w.gateFor(key(-1, 4), now.get()); // tile (0,0) 外面一圈
        assertNotNull(inTile);
        assertSame(inTile, inRing);
        assertTrue(WaitDonor.isWaiting(key(1, 1)));
        assertNull(w.gateFor(key(-2, 0), now.get()), "外面第 2 圈不等");
        assertNull(w.gateFor(key(20, 20), now.get()), "别的 tile 没有任务");
        long id = q.find(0, 0);
        assertTrue(w.hasGate(id));
        w.release(id);
        assertTrue(inTile.isDone());
        assertFalse(w.hasGate(id));
        WaitDonor.leave(key(1, 1), false);
        WaitDonor.leave(key(-1, 4), false);
        assertFalse(WaitDonor.isWaiting(key(1, 1)));
    }

    @Test
    void tooSlowDonorNotWaitedFor() {
        TaskQueue q = new TaskQueue(now::get, 30_000, 3);
        WaitDonor w = setup("waitdonor", 10_000, q, new DonorThroughput()); // 平均 10 秒，刚派出去
        q.enqueue(0, 0);
        q.assign(1);
        assertNull(w.gateFor(key(1, 1), now.get()));
        // 8.5 秒以后，预计还剩 1.5 秒：等
        assertNotNull(w.gateFor(key(1, 1), now.get() + 8_500));
        WaitDonor.leave(key(1, 1), false);
    }

    @Test
    void queuedOrDoneTasksNotWaitedFor() {
        TaskQueue q = new TaskQueue(now::get, 30_000, 3);
        WaitDonor w = setup("waitdonor", 1_000, q, new DonorThroughput());
        q.enqueue(0, 0);
        assertNull(w.gateFor(key(1, 1), now.get()), "还在排队，不知道什么时候回来");
        long id = q.assign(1).orElseThrow().taskId();
        q.onResult(1, id, true);
        assertNull(w.gateFor(key(1, 1), now.get()), "做完了，存档里已经有");
    }

    @Test
    void timeoutCounted() {
        TaskQueue q = new TaskQueue(now::get, 30_000, 3);
        WaitDonor w = setup("waitdonor", 1_000, q, new DonorThroughput());
        WaitDonor.instance = w;
        try {
            WaitDonor.leave(key(5, 5), true);
            assertTrue(w.timeouts.get() == 1);
        } finally {
            WaitDonor.instance = null;
        }
    }
}
