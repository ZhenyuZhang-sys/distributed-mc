package mcdonate.common.net;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 游戏内来源上色（docs/overlay-plan.md 第 4 节）：服务器 → 订阅了的客户端，玩家附近 chunk 是谁做完的。
 *
 * <ul>
 *   <li>频道 {@code mcdonate:provenance}。服务器只给订阅了、而且 Fabric canSend 为真的玩家发；原版客户端不注册频道，收不到。
 *   <li>{@link #full}：true = 以玩家为中心的整片快照的第一包（客户端先清空）；false = 增量，或快照的后续包。
 *   <li>坐标按 {@link #centerX}、{@link #centerZ} 的差值编（zigzag varint），离中心不超过 {@link #MAX_OFFSET}。
 *   <li>donor 表每人只发一次：增量包里的 DONOR 可以指向之前包里给过的 donor（客户端记着）。
 *   <li>带格式版本；解码不抛异常（抛了会把玩家踢下线），坏数据得到 {@link #valid()} 为 false 的，客户端丢掉。
 * </ul>
 */
public record ProvenanceUpdate(int version, boolean full, int centerX, int centerZ, List<Donor> donors, List<Entry> chunks,
        Summary summary) implements CustomPacketPayload {
    public static final int FORMAT = 1;
    public static final int MAX_CHUNKS = 2048;
    public static final int MAX_DONORS = 64;
    public static final int MAX_OFFSET = 64;   // 服务器视距最多 32，留一倍余量
    static final int MAX_NAME = 32;
    static final int MAX_KEY = 64;
    public static final Type<ProvenanceUpdate> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("mcdonate", "provenance"));
    public static final StreamCodec<ByteBuf, ProvenanceUpdate> CODEC = StreamCodec.of(ProvenanceUpdate::encode, ProvenanceUpdate::decode);

    /** 来源类别。线上是一个字节（{@link #ordinal()}），往后只能加不能改顺序。 */
    public enum Source { SERVER, RESUMED, DONOR, REJECTED, UNKNOWN }

    /** index：这次连接里 donor 的编号（服务器分配）；name：显示名（玩家名或主机名）；key：稳定名字（颜色按它算）。 */
    public record Donor(int index, String name, String key) {
        public Donor {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(key, "key");
        }

        boolean valid() {
            return index >= 0 && !name.isEmpty() && name.length() <= MAX_NAME && !key.isEmpty() && key.length() <= MAX_KEY;
        }
    }

    /** donor：DONOR 时是 donor 编号，其他类别是 -1。 */
    public record Entry(int cx, int cz, Source source, int donor) {
        public Entry {
            Objects.requireNonNull(source, "source");
        }
    }

    /** 服务器整体（hud 开着时才有）：在线 donor 数、这次开服以来导入的 chunk 数、其中这个玩家的 donor 导入的。 */
    public record Summary(int donorsOnline, long importedTotal, long importedByYou) {}

    public ProvenanceUpdate {
        donors = List.copyOf(donors);
        chunks = List.copyOf(chunks);
    }

    public ProvenanceUpdate(boolean full, int centerX, int centerZ, List<Donor> donors, List<Entry> chunks, Summary summary) {
        this(FORMAT, full, centerX, centerZ, donors, chunks, summary);
    }

    public boolean valid() {
        if (version != FORMAT || donors.size() > MAX_DONORS || chunks.size() > MAX_CHUNKS) {
            return false;
        }
        Set<Integer> given = new HashSet<>();
        for (Donor d : donors) {
            if (!d.valid() || !given.add(d.index())) return false;
        }
        for (Entry e : chunks) {
            if (Math.abs((long) e.cx() - centerX) > MAX_OFFSET || Math.abs((long) e.cz() - centerZ) > MAX_OFFSET) return false;
            if (e.source() == Source.DONOR) {
                // 给了 donor 表的包，DONOR 必须指向表里的；没给表的（增量）可以指向之前给过的
                if (e.donor() < 0 || (!donors.isEmpty() && !given.contains(e.donor()))) return false;
            } else if (e.donor() != -1) {
                return false;
            }
        }
        return summary == null || (summary.donorsOnline() >= 0 && summary.importedTotal() >= 0 && summary.importedByYou() >= 0);
    }

    /** 拆成每包不超过 {@link #MAX_CHUNKS} 条：donor 表放第一包，摘要放最后一包，只有第一包带 full。 */
    public static List<ProvenanceUpdate> split(boolean full, int centerX, int centerZ, List<Donor> donors, List<Entry> chunks,
            Summary summary) {
        List<ProvenanceUpdate> out = new ArrayList<>();
        int n = Math.max(1, (chunks.size() + MAX_CHUNKS - 1) / MAX_CHUNKS);
        for (int i = 0; i < n; i++) {
            List<Entry> part = chunks.subList(i * MAX_CHUNKS, Math.min(chunks.size(), (i + 1) * MAX_CHUNKS));
            out.add(new ProvenanceUpdate(full && i == 0, centerX, centerZ, i == 0 ? donors : List.of(), part,
                    i == n - 1 ? summary : null));
        }
        return out;
    }

    private static final StreamCodec<ByteBuf, String> NAME = ByteBufCodecs.stringUtf8(MAX_NAME);
    private static final StreamCodec<ByteBuf, String> KEY = ByteBufCodecs.stringUtf8(MAX_KEY);

    private static void zigzag(ByteBuf buf, int v) {
        ByteBufCodecs.VAR_INT.encode(buf, (v << 1) ^ (v >> 31));
    }

    private static int unzigzag(ByteBuf buf) {
        int v = ByteBufCodecs.VAR_INT.decode(buf);
        return (v >>> 1) ^ -(v & 1);
    }

    private static void encode(ByteBuf buf, ProvenanceUpdate v) {
        ByteBufCodecs.VAR_INT.encode(buf, v.version);
        buf.writeBoolean(v.full);
        zigzag(buf, v.centerX);
        zigzag(buf, v.centerZ);
        ByteBufCodecs.VAR_INT.encode(buf, v.donors.size());
        for (Donor d : v.donors) {
            ByteBufCodecs.VAR_INT.encode(buf, d.index());
            NAME.encode(buf, d.name());
            KEY.encode(buf, d.key());
        }
        ByteBufCodecs.VAR_INT.encode(buf, v.chunks.size());
        for (Entry e : v.chunks) {
            zigzag(buf, e.cx() - v.centerX);
            zigzag(buf, e.cz() - v.centerZ);
            buf.writeByte(e.source().ordinal());
            if (e.source() == Source.DONOR) ByteBufCodecs.VAR_INT.encode(buf, e.donor());
        }
        buf.writeBoolean(v.summary != null);
        if (v.summary != null) {
            ByteBufCodecs.VAR_INT.encode(buf, v.summary.donorsOnline());
            ByteBufCodecs.VAR_LONG.encode(buf, v.summary.importedTotal());
            ByteBufCodecs.VAR_LONG.encode(buf, v.summary.importedByYou());
        }
    }

    private static ProvenanceUpdate decode(ByteBuf buf) {
        try {
            int version = ByteBufCodecs.VAR_INT.decode(buf);
            if (version != FORMAT) return invalid(buf);
            boolean full = buf.readBoolean();
            int cx = unzigzag(buf), cz = unzigzag(buf);
            int nd = ByteBufCodecs.VAR_INT.decode(buf);
            if (nd < 0 || nd > MAX_DONORS) return invalid(buf);
            List<Donor> donors = new ArrayList<>(nd);
            for (int i = 0; i < nd; i++) donors.add(new Donor(ByteBufCodecs.VAR_INT.decode(buf), NAME.decode(buf), KEY.decode(buf)));
            int nc = ByteBufCodecs.VAR_INT.decode(buf);
            if (nc < 0 || nc > MAX_CHUNKS) return invalid(buf);   // 只看长度就拒绝，不去读
            List<Entry> chunks = new ArrayList<>(nc);
            Source[] sources = Source.values();
            for (int i = 0; i < nc; i++) {
                int x = cx + unzigzag(buf), z = cz + unzigzag(buf);
                int s = buf.readUnsignedByte();
                if (s >= sources.length) return invalid(buf);
                chunks.add(new Entry(x, z, sources[s], sources[s] == Source.DONOR ? ByteBufCodecs.VAR_INT.decode(buf) : -1));
            }
            Summary summary = buf.readBoolean()
                    ? new Summary(ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf))
                    : null;
            if (buf.isReadable()) return invalid(buf);   // 多出来的字节：不是我们发的
            ProvenanceUpdate v = new ProvenanceUpdate(version, full, cx, cz, donors, chunks, summary);
            return v.valid() ? v : invalid(buf);
        } catch (RuntimeException e) {
            return invalid(buf);   // 截断、字符串超长等
        }
    }

    /** 读不懂的：把剩下的字节读掉，返回 valid() 为 false 的。 */
    private static ProvenanceUpdate invalid(ByteBuf buf) {
        buf.skipBytes(buf.readableBytes());
        return new ProvenanceUpdate(-1, false, 0, 0, List.of(), List.of(), null);
    }

    @Override
    public Type<ProvenanceUpdate> type() {
        return TYPE;
    }
}
