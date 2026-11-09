package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 第 9 步：每个玩家再要邀请的频率限制（默认 10 秒一次）。 */
class InviteLimiterTest {
    private final UUID alice = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private final UUID bob = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void oncePerWindowPerPlayer() {
        InviteLimiter l = new InviteLimiter(10_000);
        assertTrue(l.allow(alice, 1_000));
        assertFalse(l.allow(alice, 5_000), "刷请求被限流");
        assertTrue(l.allow(bob, 5_000), "别的玩家不受影响");
        assertFalse(l.allow(alice, 10_999));
        assertTrue(l.allow(alice, 11_000));
    }

    @Test
    void rejectedRequestsDoNotExtendTheWindow() {
        InviteLimiter l = new InviteLimiter(10_000);
        assertTrue(l.allow(alice, 0));
        assertFalse(l.allow(alice, 9_000));
        assertTrue(l.allow(alice, 10_000), "被拒的请求不推迟下一次");
    }

    @Test
    void forgetOnQuit() {
        InviteLimiter l = new InviteLimiter(10_000);
        assertTrue(l.allow(alice, 0));
        l.forget(alice);
        assertTrue(l.allow(alice, 1));
    }
}
