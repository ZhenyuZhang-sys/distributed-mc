/*
 * 临时实现：kexu 的 mcdonate.common.proto 合进 main 之前，fish 先用它把网络接起来。
 * 接口和 docs/step5-plan.md 第 1 节一致；换成正式版时删掉整个包，改 import 即可。
 */
package mcdonate.common.tempproto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import net.minecraft.nbt.NbtIo;

/** 一帧 = 4 字节大端长度 + gzip 压缩的 NBT。 */
public final class Frame {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    /** 解压后的 NBT 上限，防压缩炸弹。 */
    static final long MAX_NBT_BYTES = 64L * 1024 * 1024;

    private Frame() {}

    public static byte[] encode(Message m) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            NbtIo.writeCompressed(MessageCodec.toTag(m), body);
        } catch (IOException e) {
            throw new ProtocolException("编码失败", e);
        }
        if (body.size() > MAX_BYTES) {
            throw new ProtocolException("消息太大：" + body.size() + " 字节");
        }
        return ByteBuffer.allocate(4 + body.size()).putInt(body.size()).put(body.toByteArray()).array();
    }
}
