package mcdonate.common.tempproto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/** 临时协议实现的最小测试（kexu 的 mcdonate.common.proto 有完整的验收测试）。 */
class TempProtoTest {
    private static List<Message> all() {
        CompoundTag tile = new CompoundTag();
        tile.putString("k", "v");
        return List.of(new Hello(1, "w1"), new Welcome(1, 3, 424242L, 3955), new Task(9, -2, 5, 30_000),
                new Result(9, true, "", tile), new Result(10, false, "boom", new CompoundTag()),
                new Cancel(9), new Heartbeat(123L, -1));
    }

    @Test
    void roundTripEveryMessage() {
        for (Message m : all()) {
            assertEquals(m, MessageCodec.fromTag(MessageCodec.toTag(m)));
        }
    }

    @Test
    void splitIntoSingleBytes() {
        List<Message> got = new ArrayList<>();
        FrameDecoder d = new FrameDecoder();
        for (Message m : all()) {
            byte[] f = Frame.encode(m);
            for (int i = 0; i < f.length; i++) {
                got.addAll(d.feed(f, i, 1));
            }
        }
        assertEquals(all(), got);
    }

    @Test
    void twoFramesAtOnce() {
        byte[] a = Frame.encode(new Cancel(1));
        byte[] b = Frame.encode(new Cancel(2));
        byte[] both = ByteBuffer.allocate(a.length + b.length).put(a).put(b).array();
        assertEquals(List.of(new Cancel(1), new Cancel(2)), new FrameDecoder().feed(both, 0, both.length));
    }

    @Test
    void oversizedLengthRejected() {
        byte[] f = ByteBuffer.allocate(4).putInt(Frame.MAX_BYTES + 1).array();
        assertThrows(ProtocolException.class, () -> new FrameDecoder().feed(f, 0, 4));
    }

    @Test
    void unknownTypeRejected() {
        CompoundTag t = new CompoundTag();
        t.putString("type", "nope");
        assertThrows(ProtocolException.class, () -> MessageCodec.fromTag(t));
    }
}
