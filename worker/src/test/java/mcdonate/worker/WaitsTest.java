package mcdonate.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 11-09 用户真机：任务 26 出异常以后，worker 主线程在 getChunk 里等一个永远不会完成的 chunk，卡了 5 分多钟，
 * 后面 4 个任务都不动。等 chunk 改成自己的循环：一边处理主线程任务一边等，超时就抛异常（任务报失败，接着做下一个）。
 */
class WaitsTest {
    @Test
    void returnsWhenDoneAndPollsMeanwhile() {
        AtomicInteger polls = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        Waits.until(() -> polls.get() >= 3, () -> {
            polls.incrementAndGet();
            return true;
        }, 1_000, clock::get, "测试");
        assertEquals(3, polls.get());
    }

    @Test
    void timesOut() {
        AtomicLong clock = new AtomicLong();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Waits.until(() -> false, () -> {
            clock.addAndGet(100);
            return false;
        }, 1_000, clock::get, "chunk (1, 2)"));
        assertEquals(true, e.getMessage().contains("chunk (1, 2)"), e.getMessage());
    }

    @Test
    void timeoutScalesWithAverage() {
        assertEquals(30_000, Waits.tileTimeoutMs(-1), "没有样本：30 秒");
        assertEquals(30_000, Waits.tileTimeoutMs(1_000), "最少 30 秒");
        assertEquals(50_000, Waits.tileTimeoutMs(5_000), "平均的 10 倍");
        assertEquals(120_000, Waits.tileTimeoutMs(20_000), "最多 120 秒（和 coordinator 的租约总上限一致；codex-audit A5）");
    }

    @Test
    void fatalStopsTheWaitAndIsDistinguishable() {
        // A5：原版生成出致命错误时（setFatalException），原版的 managedBlock 会抛出来；我们的循环也要查，并且和普通超时分得开
        AtomicLong clock = new AtomicLong();
        Waits.Fatal f = assertThrows(Waits.Fatal.class, () -> Waits.until(() -> false, () -> false, 10_000, clock::get, "x",
                () -> {
                    throw new IllegalStateException("世界生成出错");
                }));
        assertEquals("世界生成出错", f.getCause().getMessage());
    }
}
