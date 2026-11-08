/*
 * 临时实现：kexu 的 mcdonate.common.proto 合进 main 之前，fish 先用它把网络接起来。
 * 接口和 docs/step5-plan.md 第 1 节一致；换成正式版时删掉整个包，改 import 即可。
 */
package mcdonate.common.tempproto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** 增量拆帧。一个连接一个，不是线程安全的。 */
public final class FrameDecoder {
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    public List<Message> feed(byte[] bytes, int offset, int length) {
        pending.write(bytes, offset, length);
        List<Message> out = new ArrayList<>();
        while (true) {
            byte[] buf = pending.toByteArray();
            if (buf.length < 4) {
                return out;
            }
            int n = ByteBuffer.wrap(buf, 0, 4).getInt();
            if (n < 0 || n > Frame.MAX_BYTES) {
                throw new ProtocolException("帧长度不合规：" + n);
            }
            if (buf.length < 4 + n) {
                return out;
            }
            try {
                out.add(MessageCodec.fromTag(NbtIo.readCompressed(new ByteArrayInputStream(buf, 4, n),
                        NbtAccounter.create(Frame.MAX_NBT_BYTES))));
            } catch (IOException | RuntimeException e) {
                if (e instanceof ProtocolException p) {
                    throw p;
                }
                throw new ProtocolException("消息解不开：" + e.getMessage(), e);
            }
            pending.reset();
            pending.write(buf, 4 + n, buf.length - 4 - n);
        }
    }
}
