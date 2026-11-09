package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.UUID;
import mcdonate.common.proto.Hello;
import mcdonate.common.proto.Protocol;
import org.junit.jupiter.api.Test;

class MultiworldAdmissionTest {
    @Test void samePlayerTwoTokensAndDifferentWorldsAreIndependent() {
        UUID player = UUID.randomUUID(), other = UUID.randomUUID();
        TokenRegistry tokens = new TokenRegistry(() -> 1000);
        String a = tokens.issue(player, 0), b = tokens.issue(player, 1);
        var aa = CoordinatorServer.admit(new Hello(Protocol.VERSION, "a", a, "world-a"), tokens, false);
        var bb = CoordinatorServer.admit(new Hello(Protocol.VERSION, "b", b, "world-b"), tokens, false);
        assertTrue(aa.ok()); assertTrue(bb.ok());
        assertEquals(player, aa.player()); assertEquals(player, bb.player());
        assertEquals("world-b", CoordinatorServer.helloWorld("world-b", bb.player(), Map.of("world-a", aa.player())));
        assertEquals("", CoordinatorServer.helloWorld("world-a", other, Map.of("world-a", player)));
        assertFalse(CoordinatorServer.admit(new Hello(Protocol.VERSION, "b", b, "world-b"), tokens, false).ok());
    }

    @Test void invitationRateLimitIsPerSlotAndForgetsAllOnLeave() {
        InviteLimiter limit = new InviteLimiter(10000); UUID p = UUID.randomUUID();
        assertTrue(limit.allow(p, 0, 1000)); assertTrue(limit.allow(p, 1, 1000));
        assertFalse(limit.allow(p, 0, 1001)); assertFalse(limit.allow(p, 1, 1001));
        assertFalse(limit.allow(p, 3, 1001));
        limit.forget(p);
        assertTrue(limit.allow(p, 0, 1001)); assertTrue(limit.allow(p, 1, 1001));
    }
}
