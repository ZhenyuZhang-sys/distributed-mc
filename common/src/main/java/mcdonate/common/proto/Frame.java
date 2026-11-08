package mcdonate.common.proto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** 一帧 = 4 字节大端长度 N + N 字节 gzip 压缩的 NBT（NbtIo.writeCompressed 的格式）。 */
public final class Frame {
    /** 压缩后消息体的上限。 */
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    /** 解压后 NBT 的上限（NbtAccounter 计数），防压缩炸弹。 */
    public static final long MAX_NBT_BYTES = 64L * 1024 * 1024;
    /** 长度前缀的字节数。 */
    public static final int HEADER_BYTES = 4;

    private Frame() {}

    /** 消息编码成一帧（含长度前缀）。压缩后超过上限抛 ProtocolException。 */
    public static byte[] encode(Message m) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[HEADER_BYTES]);
        try {
            NbtIo.writeCompressed(MessageCodec.toTag(m), out);
        } catch (IOException e) {
            throw new ProtocolException("编码失败：" + e.getMessage(), e);
        }
        byte[] frame = out.toByteArray();
        int n = frame.length - HEADER_BYTES;
        if (n > MAX_BYTES) {
            throw new ProtocolException("消息太大：压缩后 " + n + " 字节，上限 " + MAX_BYTES);
        }
        ByteBuffer.wrap(frame).putInt(0, n);
        return frame;
    }

    /** 解一个消息体（不含长度前缀）。 */
    static Message decodeBody(byte[] body, int offset, int length) {
        try {
            return MessageCodec.fromTag(NbtIo.readCompressed(new ByteArrayInputStream(body, offset, length),
                    NbtAccounter.create(MAX_NBT_BYTES)));
        } catch (ProtocolException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ProtocolException("消息体解不开：" + e, e);
        }
    }
}
