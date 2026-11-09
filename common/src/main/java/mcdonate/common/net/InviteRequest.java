package mcdonate.common.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 第 9 步（kexu 11-09 审阅 DonorInvite 的 (a)）：客户端向服务器再要一次邀请。频道 {@code mcdonate:invite_request}，游戏内连接。
 *
 * <p>token 60 秒过期，邀请只在进服时发一次：玩家进服后过了一会儿才同意、才起 worker，或者 coordinator 重启后 worker 要重连，
 * 手上的 token 都不能用了。客户端发这个请求，服务器作废这个玩家的旧 token、重发 {@link DonorInvite}；每个玩家有频率限制。
 *
 * <p>只有格式版本一个字段。解码不抛异常：读不懂的得到 {@link #valid()} 为 false 的，服务器忽略。
 */
public record InviteRequest(int version) implements CustomPacketPayload {
    public static final int FORMAT = 1;
    public static final Type<InviteRequest> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("mcdonate", "invite_request"));
    public static final StreamCodec<ByteBuf, InviteRequest> CODEC = StreamCodec.of(
            (buf, v) -> ByteBufCodecs.VAR_INT.encode(buf, v.version), InviteRequest::decode);

    public InviteRequest() {
        this(FORMAT);
    }

    public boolean valid() {
        return version == FORMAT;
    }

    private static InviteRequest decode(ByteBuf buf) {
        int version;
        try {
            version = ByteBufCodecs.VAR_INT.decode(buf);
        } catch (RuntimeException e) {
            version = -1;
        }
        if (buf.isReadable()) {
            version = -1;
        }
        buf.skipBytes(buf.readableBytes());
        return new InviteRequest(version);
    }

    @Override
    public Type<InviteRequest> type() {
        return TYPE;
    }
}
