package mcdonate.common.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 为一个 worker 单独续邀请，不影响同玩家其他 worker 的未用 token。 */
public record WorkerInviteRequest(int version, int slot) implements CustomPacketPayload {
    public static final int FORMAT = 1;
    public static final Type<WorkerInviteRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("mcdonate", "worker_invite_request"));
    public static final StreamCodec<ByteBuf, WorkerInviteRequest> CODEC = StreamCodec.of((buf, v) -> {
        ByteBufCodecs.VAR_INT.encode(buf, v.version);
        ByteBufCodecs.VAR_INT.encode(buf, v.slot);
    }, WorkerInviteRequest::decode);

    public WorkerInviteRequest(int slot) { this(FORMAT, slot); }
    public boolean valid() { return version == FORMAT && slot >= 0 && slot < WorkerInvite.MAX_WORKERS; }
    private static WorkerInviteRequest decode(ByteBuf buf) {
        try {
            WorkerInviteRequest v = new WorkerInviteRequest(ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf));
            if (!buf.isReadable()) return v;
        } catch (RuntimeException ignored) { }
        buf.skipBytes(buf.readableBytes());
        return new WorkerInviteRequest(-1, -1);
    }
    @Override public Type<WorkerInviteRequest> type() { return TYPE; }
}
