package mcdonate.common.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 游戏内来源上色（docs/overlay-plan.md 第 4 节）：客户端 → 服务器，"我要 / 不要来源数据"。
 * 客户端开关 overlay、hud 时发一次，进服时按设置发一次。两样都关时服务器什么都不发，也不记这个玩家。
 * 频道 {@code mcdonate:overlay}，两边都要在 PayloadTypeRegistry.playC2S() 里登记。解码不抛异常，坏数据得到 valid() 为 false 的。
 */
public record OverlaySubscribe(int version, int flags) implements CustomPacketPayload {
    public static final int FORMAT = 1;
    public static final int OVERLAY = 1;
    public static final int HUD = 2;
    public static final Type<OverlaySubscribe> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("mcdonate", "overlay"));
    public static final StreamCodec<ByteBuf, OverlaySubscribe> CODEC = StreamCodec.of(OverlaySubscribe::encode, OverlaySubscribe::decode);

    public OverlaySubscribe(boolean overlay, boolean hud) {
        this(FORMAT, (overlay ? OVERLAY : 0) | (hud ? HUD : 0));
    }

    public boolean valid() {
        return version == FORMAT && (flags & ~(OVERLAY | HUD)) == 0;
    }

    public boolean overlay() {
        return valid() && (flags & OVERLAY) != 0;
    }

    public boolean hud() {
        return valid() && (flags & HUD) != 0;
    }

    private static void encode(ByteBuf buf, OverlaySubscribe v) {
        ByteBufCodecs.VAR_INT.encode(buf, v.version);
        ByteBufCodecs.VAR_INT.encode(buf, v.flags);
    }

    private static OverlaySubscribe decode(ByteBuf buf) {
        try {
            OverlaySubscribe v = new OverlaySubscribe(ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf));
            if (buf.isReadable()) {
                buf.skipBytes(buf.readableBytes());
                return new OverlaySubscribe(-1, 0);
            }
            return v;
        } catch (RuntimeException e) {
            buf.skipBytes(buf.readableBytes());
            return new OverlaySubscribe(-1, 0);
        }
    }

    @Override
    public Type<OverlaySubscribe> type() {
        return TYPE;
    }
}
