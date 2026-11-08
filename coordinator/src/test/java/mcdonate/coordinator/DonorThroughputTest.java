package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 第 7 步 7.3：按实测算力分配。 */
class DonorThroughputTest {
    @Test
    void unknownDonorUsesDefault() {
        assertEquals(DonorThroughput.DEFAULT_MS, new DonorThroughput().averageMs(1));
    }

    @Test
    void averageOfLastTen() {
        DonorThroughput t = new DonorThroughput();
        for (int i = 0; i < 5; i++) {
            t.record(1, 10_000); // 早先很慢
        }
        for (int i = 0; i < 10; i++) {
            t.record(1, 2_000); // 最近 10 个都是 2 秒
        }
        assertEquals(2_000, t.averageMs(1));
    }

    @Test
    void concurrencyFromLease() {
        DonorThroughput t = new DonorThroughput();
        t.record(1, 2_000);
        assertEquals(4, t.concurrency(1, 30_000), "快的封顶 4");
        t.record(2, 20_000);
        assertEquals(1, t.concurrency(2, 30_000));
        t.record(3, 60_000);
        assertEquals(1, t.concurrency(3, 30_000), "至少 1");
        t.record(4, 10_000);
        assertEquals(3, t.concurrency(4, 30_000));
    }

    @Test
    void slowDonorOnlyGetsLooseDeadlines() {
        DonorThroughput t = new DonorThroughput();
        t.record(1, 2_000);   // 快
        t.record(2, 12_000);  // 慢（相当于 0.5 核）
        long now = 100_000;
        long tight = now + 5_000, loose = now + 60_000;
        assertTrue(t.canMeet(1, 0, now, tight));
        assertFalse(t.canMeet(2, 0, now, tight), "慢 donor 赶不上紧的");
        assertTrue(t.canMeet(2, 0, now, loose));
        assertFalse(t.canMeet(1, 2, now, tight), "快 donor 手上排了 2 个也赶不上");
        assertTrue(t.canMeet(2, 3, now, Long.MAX_VALUE), "不急的总能派");
    }

    @Test
    void forgetResets() {
        DonorThroughput t = new DonorThroughput();
        t.record(1, 9_000);
        t.forget(1);
        assertEquals(DonorThroughput.DEFAULT_MS, t.averageMs(1));
    }
}
