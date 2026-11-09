package mcdonate.common.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.codec.ByteBufCodecs;
import org.junit.jupiter.api.Test;

/** 第 9 步：服务器发给装了 mod 的客户端的邀请（coordinator 的 host、port，一次性 token，seed）。kexu 11-09 的五条检查。 */
class DonorInviteTest {
    private final DonorInvite good = new DonorInvite("mc.example.org", 25580, "tok_abc-123", -4242424242L);

    private static ByteBuf encode(DonorInvite v) {
        ByteBuf buf = Unpooled.buffer();
        DonorInvite.CODEC.encode(buf, v);
        return buf;
    }

    @Test
    void roundTrip() {
        ByteBuf buf = encode(good);
        DonorInvite back = DonorInvite.CODEC.decode(buf);
        assertEquals(good, back);
        assertTrue(back.valid());
        assertEquals(DonorInvite.FORMAT, back.version());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void channelIsOurs() {
        assertEquals("mcdonate:invite", DonorInvite.TYPE.id().toString());
    }

    @Test
    void toStringHidesToken() {
        assertFalse(good.toString().contains("tok_abc-123"), good.toString());
    }

    @Test
    void unknownVersionIsInvalidNotThrown() {
        ByteBuf buf = encode(new DonorInvite(2, "h", 1, "t", 1));
        DonorInvite v = DonorInvite.CODEC.decode(buf);
        assertFalse(v.valid());
        assertEquals(2, v.version());
        assertEquals(0, buf.readableBytes(), "剩下的字节读掉");
    }

    @Test
    void truncatedOrTrailingBytesAreInvalidNotThrown() {
        ByteBuf full = encode(good);
        ByteBuf cut = full.copy(0, full.readableBytes() - 3);
        assertFalse(DonorInvite.CODEC.decode(cut).valid());
        assertEquals(0, cut.readableBytes());
        ByteBuf extra = encode(good).writeByte(7);
        assertFalse(DonorInvite.CODEC.decode(extra).valid());
        ByteBuf garbage = Unpooled.buffer().writeBytes(new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 1});
        assertFalse(DonorInvite.CODEC.decode(garbage).valid());
    }

    @Test
    void badFieldsAreInvalid() {
        assertFalse(new DonorInvite("", 25580, "t", 1).valid());
        assertFalse(new DonorInvite("h", 0, "t", 1).valid());
        assertFalse(new DonorInvite("h", 70000, "t", 1).valid());
        assertFalse(new DonorInvite("h", 25580, "", 1).valid());
        // 解码出来的也一样判
        ByteBuf buf = Unpooled.buffer();
        ByteBufCodecs.VAR_INT.encode(buf, DonorInvite.FORMAT);
        ByteBufCodecs.stringUtf8(255).encode(buf, "h");
        ByteBufCodecs.VAR_INT.encode(buf, -5);
        ByteBufCodecs.stringUtf8(255).encode(buf, "t");
        ByteBufCodecs.VAR_LONG.encode(buf, 1L);
        assertFalse(DonorInvite.CODEC.decode(buf).valid());
    }

    @Test
    void rejectsNullFields() {
        assertThrows(NullPointerException.class, () -> new DonorInvite(null, 1, "t", 1));
        assertThrows(NullPointerException.class, () -> new DonorInvite("h", 1, null, 1));
    }
}
