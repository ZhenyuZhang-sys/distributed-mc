package mcdonate.common.proto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 增量拆帧：收到多少字节就喂多少，凑齐一帧吐一条消息。
 * 一个连接一个，不是线程安全的。抛过一次 ProtocolException 后不能再用，调用方应断开连接。
 */
public final class FrameDecoder {
    /** body 缓冲的初始大小。按实际到达的数据翻倍增长，对方只报长度不发数据时不会占满 16 MiB。 */
    private static final int INITIAL_BODY = 64 * 1024;

    private final byte[] header = new byte[Frame.HEADER_BYTES];
    private int headerFilled;
    private int bodyLength = -1;
    private byte[] body = new byte[0];
    private int bodyFilled;
    private boolean failed;

    /** 返回这次凑齐的消息（0 条或多条）。长度超限、消息解不开抛 ProtocolException。 */
    public List<Message> feed(byte[] bytes, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (failed) {
            throw new ProtocolException("解码器已出错，连接应断开");
        }
        try {
            return feedUnchecked(bytes, offset, length);
        } catch (RuntimeException e) {
            failed = true;
            throw e;
        }
    }

    private List<Message> feedUnchecked(byte[] bytes, int offset, int length) {
        List<Message> out = new ArrayList<>();
        int pos = offset;
        int end = offset + length;
        while (pos < end) {
            if (bodyLength < 0) {
                int take = Math.min(Frame.HEADER_BYTES - headerFilled, end - pos);
                System.arraycopy(bytes, pos, header, headerFilled, take);
                headerFilled += take;
                pos += take;
                if (headerFilled < Frame.HEADER_BYTES) {
                    break;
                }
                int n = ((header[0] & 0xff) << 24) | ((header[1] & 0xff) << 16)
                        | ((header[2] & 0xff) << 8) | (header[3] & 0xff);
                if (n <= 0 || n > Frame.MAX_BYTES) {
                    throw new ProtocolException("帧长度不合规：" + n + "，上限 " + Frame.MAX_BYTES);
                }
                bodyLength = n;
                bodyFilled = 0;
                if (body.length < Math.min(n, INITIAL_BODY)) {
                    body = new byte[Math.min(n, INITIAL_BODY)];
                }
            }
            int take = Math.min(bodyLength - bodyFilled, end - pos);
            if (bodyFilled + take > body.length) {
                body = Arrays.copyOf(body, (int) Math.min(bodyLength, Math.max(2L * body.length, bodyFilled + take)));
            }
            System.arraycopy(bytes, pos, body, bodyFilled, take);
            bodyFilled += take;
            pos += take;
            if (bodyFilled == bodyLength) {
                out.add(Frame.decodeBody(body, 0, bodyLength));
                headerFilled = 0;
                bodyLength = -1;
                if (body.length > INITIAL_BODY) {
                    body = new byte[0];
                }
            }
        }
        return out;
    }
}
