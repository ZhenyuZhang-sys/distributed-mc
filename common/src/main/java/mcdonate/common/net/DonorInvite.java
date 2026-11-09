package mcdonate.common.net;

import io.netty.buffer.ByteBuf;
import java.util.Objects;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 第 9 步（docs/step9-plan.md 3.1）：玩家进服时，服务器通过游戏连接发给装了客户端 mod 的玩家：
 * coordinator 的公开地址（host:port）、一次性 token、seed。客户端 mod 起 worker 时把 token 交给它，worker 拿 token 直连 coordinator。
 *
 * <p>频道 {@code mcdonate:invite}。服务器只发给注册了这个频道的客户端（Fabric 的 canSend），原版客户端收不到、不受影响。
 * 两边都要在 Fabric 的 PayloadTypeRegistry.playS2C() 里登记 {@link #TYPE} 和 {@link #CODEC}。toString 不带 token。
 */
public record DonorInvite(String coordinator, String token, long seed) implements CustomPacketPayload {
    public static final Type<DonorInvite> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("mcdonate", "invite"));
    public static final StreamCodec<ByteBuf, DonorInvite> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(255), DonorInvite::coordinator,
            ByteBufCodecs.stringUtf8(255), DonorInvite::token,
            ByteBufCodecs.VAR_LONG, DonorInvite::seed,
            DonorInvite::new);

    public DonorInvite {
        Objects.requireNonNull(coordinator, "coordinator");
        Objects.requireNonNull(token, "token");
    }

    @Override
    public Type<DonorInvite> type() {
        return TYPE;
    }

    @Override
    public String toString() {
        return "DonorInvite[coordinator=" + coordinator + ", token=***, seed=" + seed + "]";
    }
}
