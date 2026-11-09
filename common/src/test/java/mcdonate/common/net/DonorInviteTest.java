package mcdonate.common.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

/** 第 9 步：服务器发给装了 mod 的客户端的邀请（coordinator 地址、一次性 token、seed）。 */
class DonorInviteTest {
    @Test
    void roundTrip() {
        DonorInvite in = new DonorInvite("mc.example.org:25580", "tok_abc-123", -4242424242L);
        ByteBuf buf = Unpooled.buffer();
        DonorInvite.CODEC.encode(buf, in);
        assertEquals(in, DonorInvite.CODEC.decode(buf));
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void channelIsOurs() {
        assertEquals("mcdonate:invite", DonorInvite.TYPE.id().toString());
    }

    @Test
    void toStringHidesToken() {
        String s = new DonorInvite("h:1", "secret-token-value", 1).toString();
        assertFalse(s.contains("secret-token-value"), s);
    }

    @Test
    void rejectsMissingFields() {
        assertThrows(NullPointerException.class, () -> new DonorInvite(null, "t", 1));
        assertThrows(NullPointerException.class, () -> new DonorInvite("h:1", null, 1));
    }
}
