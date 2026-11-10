package mcdonate.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExitDeadlineTest {
    @Test
    void blockedGracefulStopCannotPreventHardExit() throws Exception {
        CountDownLatch hard = new CountDownLatch(1);
        ExitDeadline stop = new ExitDeadline(100, hard::countDown);
        Thread blocked = new Thread(() -> stop.stop(() -> {
            try { new CountDownLatch(1).await(); } catch (InterruptedException ignored) { }
        }));
        blocked.setDaemon(true);
        blocked.start();
        assertTrue(hard.await(2, TimeUnit.SECONDS));
        blocked.interrupt();
    }

    @Test
    void repeatedExitRequestsArmOneDeadline() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        ExitDeadline stop = new ExitDeadline(50, () -> { calls.incrementAndGet(); done.countDown(); });
        stop.arm(); stop.arm(); stop.stop(() -> {});
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
    }
}
