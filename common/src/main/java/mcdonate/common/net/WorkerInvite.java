package mcdonate.common.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 独立 worker 槽位的邀请。旧 invite 频道仍用于兼容单 worker 客户端。 */
public record WorkerInvite(int version, int slot, DonorInvite invite) implements CustomPacketPayload {
    public static final int FORMAT = 1;
    public static final int MAX_WORKERS = 3;
    public static final Type<WorkerInvite> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("mcdonate", "worker_invite"));
    public static final StreamCodec<ByteBuf, WorkerInvite> CODEC = StreamCodec.of((buf, v) -> {
        ByteBufCodecs.VAR_INT.encode(buf, v.version);
        ByteBufCodecs.VAR_INT.encode(buf, v.slot);
        DonorInvite.CODEC.encode(buf, v.invite);
    }, WorkerInvite::decode);

    public WorkerInvite(int slot, DonorInvite invite) { this(FORMAT, slot, invite); }

    public boolean valid() {
        return version == FORMAT && slot >= 0 && slot < MAX_WORKERS && invite != null && invite.valid();
    }

    private static WorkerInvite decode(ByteBuf buf) {
        try {
            int version = ByteBufCodecs.VAR_INT.decode(buf);
            int slot = ByteBufCodecs.VAR_INT.decode(buf);
            return new WorkerInvite(version, slot, DonorInvite.CODEC.decode(buf));
        } catch (RuntimeException e) {
            buf.skipBytes(buf.readableBytes());
            return new WorkerInvite(-1, -1, new DonorInvite("", 0, "", 0));
        }
    }

    @Override public Type<WorkerInvite> type() { return TYPE; }
    @Override public String toString() { return "WorkerInvite[slot=" + slot + ", invite=" + invite + "]"; }
}
