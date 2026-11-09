package mcdonate.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ---- 第 9 步（调度 11-09）：客户端起的 worker 用邀请里的 seed，不为了 seed 单独握手（token 只换一次连接） ----

    @Test
    void inviteSeedSkipsSeedHandshake() {
        AtomicInteger calls = new AtomicInteger();
        assertEquals("-77", WorkerSeed.chooseSeed("123", "mc.example.org:25580", "-77", () -> {
            calls.incrementAndGet();
            return 1L;
        }));
        assertEquals(0, calls.get(), "有邀请里的 seed 就不握手");
    }

    @Test
    void dockerWorkerStillHandshakes() {
        // Docker 里的本地 worker 没有邀请：照旧建世界前握手要 seed
        assertEquals("424242", WorkerSeed.chooseSeed("123", "minecraft:25580", null, () -> 424242L));
        assertEquals("123", WorkerSeed.chooseSeed("123", null, "-77", () -> 1L), "没配 coordinator：原样");
    }

    @Test
    void badInviteSeedIsAnError() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> WorkerSeed.chooseSeed("123", "h:1", "not-a-number", () -> 1L));
    }

    @Test
    void welcomeSeedMustMatchWorld() {
        // 不一致就不领任务（WorkerClient 记 ERROR）
        assertTrue(WorkerClient.seedOk(new mcdonate.common.proto.Welcome(mcdonate.common.proto.Protocol.VERSION, 3, -77, 1), -77));
        assertFalse(WorkerClient.seedOk(new mcdonate.common.proto.Welcome(mcdonate.common.proto.Protocol.VERSION, 3, 5, 1), -77));
    }
}
