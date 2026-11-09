package mcdonate.common.net;

import io.netty.buffer.ByteBuf;
import java.util.Objects;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 第 9 步（docs/step9-plan.md 3.1）：玩家进服时，服务器通过游戏连接发给装了客户端 mod 的玩家：
 * coordinator 的公开地址（host、port）、一次性 token、seed。客户端 mod 起 worker 时把 token 交给它，worker 拿 token 直连 coordinator。
 *
 * <ul>
 *   <li>频道 {@code mcdonate:invite}。服务器只在 Fabric 的 canSend(player, TYPE) 为真时发：原版客户端不注册这个频道，收不到。
 *       两边都要在 PayloadTypeRegistry.playS2C() 里登记 {@link #TYPE} 和 {@link #CODEC}。
 *   <li>带格式版本 {@link #FORMAT}。版本不认识、数据坏了、字段不合法：解码不抛异常（抛了会把玩家踢下线），
 *       得到一个 {@link #valid()} 为 false 的邀请，客户端忽略它。
 *   <li>seed 只当参考：worker 握手时以 coordinator 的 Welcome 里的 seed 为准。
 *   <li>token 不进日志：toString 不带它。
 * </ul>
 */
public record DonorInvite(int version, String host, int port, String token, long seed) implements CustomPacketPayload {
    public static final int FORMAT = 1;
    static final int MAX_STRING = 255;
    public static final Type<DonorInvite> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("mcdonate", "invite"));

    private static final StreamCodec<ByteBuf, String> STRING = ByteBufCodecs.stringUtf8(MAX_STRING);

    public static final StreamCodec<ByteBuf, DonorInvite> CODEC = StreamCodec.of(DonorInvite::encode, DonorInvite::decode);

    public DonorInvite {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(token, "token");
    }

    /** 服务器发的：当前格式。 */
    public DonorInvite(String host, int port, String token, long seed) {
        this(FORMAT, host, port, token, seed);
    }

    /** 能不能用：格式认识、地址和 token 合法。 */
    public boolean valid() {
        return version == FORMAT && !host.isEmpty() && host.length() <= MAX_STRING && port > 0 && port <= 65535
                && !token.isEmpty() && token.length() <= MAX_STRING;
    }

    private static void encode(ByteBuf buf, DonorInvite v) {
        ByteBufCodecs.VAR_INT.encode(buf, v.version);
        STRING.encode(buf, v.host);
        ByteBufCodecs.VAR_INT.encode(buf, v.port);
        STRING.encode(buf, v.token);
        ByteBufCodecs.VAR_LONG.encode(buf, v.seed);
    }

    private static DonorInvite decode(ByteBuf buf) {
        int version = -1;
        try {
            version = ByteBufCodecs.VAR_INT.decode(buf);
            if (version != FORMAT) {
                return invalid(buf, version);
            }
            DonorInvite v = new DonorInvite(version, STRING.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                    STRING.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf));
            if (buf.isReadable()) {
                return invalid(buf, version); // 多出来的字节：不是我们发的
            }
            return v;
        } catch (RuntimeException e) {
            return invalid(buf, version); // 数据坏了（截断、字符串超长等）
        }
    }

    /** 读不懂的邀请：把剩下的字节读掉（免得报"多余字节"），返回 valid() 为 false 的。 */
    private static DonorInvite invalid(ByteBuf buf, int version) {
        buf.skipBytes(buf.readableBytes());
        return new DonorInvite(version == FORMAT ? -1 : version, "", 0, "", 0);
    }

    @Override
    public Type<DonorInvite> type() {
        return TYPE;
    }

    @Override
    public String toString() {
        return "DonorInvite[version=" + version + ", host=" + host + ", port=" + port + ", token=***, seed=" + seed + "]";
    }
}
