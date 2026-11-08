package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
