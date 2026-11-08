package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 第 9 步：一次性 token。 */
class TokenRegistryTest {
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final TokenRegistry r = new TokenRegistry(now::get, new Random(42));
    private final UUID alice = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private final UUID bob = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void onlyOnce() {
        String t = r.issue(alice);
        assertEquals(Optional.of(alice), r.consume(t));
        assertEquals(Optional.empty(), r.consume(t), "第二次（被别人重放）拒绝");
    }

    @Test
    void expiresAfterSixtySeconds() {
        String t = r.issue(alice);
        now.addAndGet(TokenRegistry.TTL_MS + 1);
        assertEquals(Optional.empty(), r.consume(t));
    }

    @Test
    void stillValidJustBeforeExpiry() {
        String t = r.issue(alice);
        now.addAndGet(TokenRegistry.TTL_MS);
        assertEquals(Optional.of(alice), r.consume(t));
    }

    @Test
    void unknownAndEmptyRejected() {
        r.issue(alice);
        assertEquals(Optional.empty(), r.consume("not-a-token"));
        assertEquals(Optional.empty(), r.consume(""));
        assertEquals(Optional.empty(), r.consume(null));
    }

    @Test
    void reissueRevokesOld() {
        String old = r.issue(alice);
        String fresh = r.issue(alice);
        assertNotEquals(old, fresh);
        assertEquals(Optional.empty(), r.consume(old));
        assertEquals(Optional.of(alice), r.consume(fresh));
    }

    @Test
    void revokeOnLeave() {
        String t = r.issue(alice);
        r.revoke(alice);
        assertEquals(Optional.empty(), r.consume(t));
    }

    @Test
    void playersIndependent() {
        String a = r.issue(alice);
        String b = r.issue(bob);
        r.revoke(alice);
        assertEquals(Optional.of(bob), r.consume(b));
        assertEquals(Optional.empty(), r.consume(a));
    }

    @Test
    void tokenIs128BitsUrlSafe() {
        String t = r.issue(alice);
        assertEquals(22, t.length()); // 16 字节 Base64 不带补位
        assertTrue(t.matches("[A-Za-z0-9_-]+"));
    }

    @Test
    void redactHidesToken() {
        String t = r.issue(alice);
        String red = TokenRegistry.redact(t);
        assertFalse(red.contains(t.substring(4)));
        assertTrue(red.endsWith("****"));
    }
}
