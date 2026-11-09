package mcdonate.common.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

/** 第 9 步：客户端再要一次邀请（kexu 11-09 (a)）。 */
class InviteRequestTest {
    @Test
    void roundTrip() {
        ByteBuf buf = Unpooled.buffer();
        InviteRequest.CODEC.encode(buf, new InviteRequest());
        InviteRequest back = InviteRequest.CODEC.decode(buf);
        assertTrue(back.valid());
        assertEquals(0, buf.readableBytes());
        assertEquals("mcdonate:invite_request", InviteRequest.TYPE.id().toString());
    }

    @Test
    void badDataIsInvalidNotThrown() {
        assertFalse(InviteRequest.CODEC.decode(Unpooled.buffer()).valid(), "空的");
        ByteBuf extra = Unpooled.buffer();
        InviteRequest.CODEC.encode(extra, new InviteRequest());
        extra.writeByte(1);
        assertFalse(InviteRequest.CODEC.decode(extra).valid(), "多出字节");
        assertEquals(0, extra.readableBytes());
        ByteBuf v2 = Unpooled.buffer();
        InviteRequest.CODEC.encode(v2, new InviteRequest(2));
        assertFalse(InviteRequest.CODEC.decode(v2).valid(), "不认识的版本");
    }
}
