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
}
