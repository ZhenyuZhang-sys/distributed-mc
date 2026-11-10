package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import mcdonate.common.proto.Hello;
import mcdonate.common.proto.Protocol;
import mcdonate.common.proto.Welcome;
import org.junit.jupiter.api.Test;

class HandshakeTest {
    @Test
    void sameVersionGetsIdAndSeed() {
        assertEquals(new Welcome(Protocol.VERSION, 4, 424242L, 3955),
                CoordinatorServer.handshake(new Hello(Protocol.VERSION, "w"), 4, 424242L, 3955));
    }

    @Test
    void otherVersionRejected() {
        assertEquals(-1, CoordinatorServer.handshake(new Hello(Protocol.VERSION + 1, "w"), 4, 424242L, 3955).workerId());
    }

    // ---- 第 9 步：token（docs/step9-plan.md 3.1） ----

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final TokenRegistry tokens = new TokenRegistry(now::get, new Random(7));
    private final UUID alice = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @Test
    void validTokenAdmitsAsPlayerOnce() {
        String t = tokens.issue(alice);
        CoordinatorServer.Admission a = CoordinatorServer.admit(new Hello(Protocol.VERSION, "w", t), tokens, false);
        assertTrue(a.ok());
        assertEquals(alice, a.player());
        assertFalse(CoordinatorServer.admit(new Hello(Protocol.VERSION, "w", t), tokens, true).ok(), "用过的 token 不能再用，开不开免 token 都一样");
    }

    @Test
    void tokenlessIsOffByDefault() {
        // 正式默认：没设 mcdonate.allowTokenless 时是关的（只有测试的 compose 设 true）
        assertNull(System.getProperty("mcdonate.allowTokenless"), "单元测试的 JVM 不该设它");
        assertFalse(CoordinatorServer.ALLOW_TOKENLESS);
        assertFalse(CoordinatorServer.admit(new Hello(Protocol.VERSION, "w"), tokens, CoordinatorServer.ALLOW_TOKENLESS).ok());
    }

    @Test
    void noTokenOnlyWhenTokenlessAllowed() {
        assertFalse(CoordinatorServer.admit(new Hello(Protocol.VERSION, "w"), tokens, false).ok(), "正式默认：没有 token 拒绝");
        CoordinatorServer.Admission a = CoordinatorServer.admit(new Hello(Protocol.VERSION, "w"), tokens, true);
        assertTrue(a.ok(), "测试里的本地 worker（mcdonate.allowTokenless=true）");
        assertNull(a.player());
    }

    @Test
    void badOrExpiredTokenRejected() {
        assertFalse(CoordinatorServer.admit(new Hello(Protocol.VERSION, "w", "nope"), tokens, true).ok());
        String t = tokens.issue(alice);
        now.addAndGet(TokenRegistry.TTL_MS + 1);
        assertFalse(CoordinatorServer.admit(new Hello(Protocol.VERSION, "w", t), tokens, false).ok());
    }

    @Test
    void rejectedTokenGetsMinusTwoButVersionWins() {
        assertEquals(Welcome.BAD_TOKEN, CoordinatorServer.handshake(new Hello(Protocol.VERSION, "w"), 4, 1L, 2, false).workerId());
        assertEquals(4, CoordinatorServer.handshake(new Hello(Protocol.VERSION, "w"), 4, 1L, 2, true).workerId());
        assertEquals(Welcome.BAD_VERSION,
                CoordinatorServer.handshake(new Hello(Protocol.VERSION + 1, "w"), 4, 1L, 2, false).workerId());
    }

    // ---- 11-09 kexu 审 hello.worldId：格式、冒认 ----

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void helloWorldValidated() {
        assertEquals("49a89f99-0fa6-4a70-ae24-0b6ec4aa23cb",
                CoordinatorServer.helloWorld("49a89f99-0fa6-4a70-ae24-0b6ec4aa23cb", ALICE, java.util.Map.of()));
        assertEquals("", CoordinatorServer.helloWorld("", ALICE, java.util.Map.of()), "没报");
        assertEquals("", CoordinatorServer.helloWorld("a b\n", ALICE, java.util.Map.of()), "有空白、换行：不收");
        assertEquals("", CoordinatorServer.helloWorld("x".repeat(65), ALICE, java.util.Map.of()), "超过 64 个字符：不收");
    }

    @Test
    void helloWorldCannotClaimAnotherPlayersOnlineWorld() {
        // 这个世界已经绑在一个在线的、别的玩家的 worker 上：hello 里报的不算数（防冒认，拿走别人认主的任务）
        java.util.Map<String, UUID> online = new java.util.HashMap<>();
        online.put("w1", ALICE);
        assertEquals("", CoordinatorServer.helloWorld("w1", BOB, online));
        assertEquals("w1", CoordinatorServer.helloWorld("w1", ALICE, online), "同一个玩家的（重连）可以");
        online.put("w2", null);   // 本地 worker（没有玩家）占着
        assertEquals("", CoordinatorServer.helloWorld("w2", null, online), "两个都没有玩家：也不算数，等结果");
    }
}
