package mcdonate.common.net;

import static org.junit.jupiter.api.Assertions.*;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class WorkerInviteTest {
    @Test void roundTripAndRedacts() {
        var v = new WorkerInvite(1, new DonorInvite("localhost", 25580, "fixture-secret", 42));
        var b = Unpooled.buffer();
        try {
            WorkerInvite.CODEC.encode(b, v);
            assertEquals(v, WorkerInvite.CODEC.decode(b));
            assertFalse(v.toString().contains("fixture-secret"));
            assertFalse(new WorkerInvite(3, v.invite()).valid());
        } finally { b.release(); }
    }
    @Test void requestRoundTripAndRejectsTruncation() {
        var b = Unpooled.buffer();
        try {
            WorkerInviteRequest.CODEC.encode(b, new WorkerInviteRequest(2));
            assertEquals(new WorkerInviteRequest(2), WorkerInviteRequest.CODEC.decode(b));
            assertFalse(WorkerInviteRequest.CODEC.decode(b).valid());
            assertFalse(WorkerInvite.CODEC.decode(b).valid());
        } finally { b.release(); }
    }
}
