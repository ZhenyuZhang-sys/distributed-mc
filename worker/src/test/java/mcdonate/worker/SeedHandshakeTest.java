package mcdonate.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SeedHandshakeTest {
    @AfterEach
    void reset() {
        SeedHandshake.resetForTest();
    }

    @Test
    void handshakeOnlyOnce() {
        // DedicatedServerProperties 可能被重新构造，parseSeed 被调多次：只握手一次
        AtomicInteger calls = new AtomicInteger();
        assertEquals(424242L, SeedHandshake.seed(() -> { calls.incrementAndGet(); return 424242L; }));
        assertEquals(424242L, SeedHandshake.seed(() -> { calls.incrementAndGet(); return 1L; }));
        assertEquals(1, calls.get());
    }

    @Test
    void noCoordinatorKeepsOriginalSeed() {
        System.clearProperty("mcdonate.coordinator");
        assertEquals("123", WorkerSeed.levelSeed("123"));
    }
}
