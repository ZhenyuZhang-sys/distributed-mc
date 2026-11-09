package mcdonate.common.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import mcdonate.common.net.ProvenanceUpdate.Donor;
import mcdonate.common.net.ProvenanceUpdate.Entry;
import mcdonate.common.net.ProvenanceUpdate.Source;
import mcdonate.common.net.ProvenanceUpdate.Summary;
import net.minecraft.network.codec.ByteBufCodecs;
import org.junit.jupiter.api.Test;

/**
 * 游戏内来源上色（docs/overlay-plan.md 第 4 节）：客户端订阅（C2S mcdonate:overlay）和服务器发的来源更新（S2C mcdonate:provenance）。
 * 和 DonorInvite 一样：带格式版本；解码不抛异常（抛了会把玩家踢下线），坏数据得到 valid() 为 false 的对象。
 */
class OverlayPayloadTest {
    static ByteBuf enc(ProvenanceUpdate v) {
        ByteBuf b = Unpooled.buffer();
        ProvenanceUpdate.CODEC.encode(b, v);
        return b;
    }

    static ProvenanceUpdate sample() {
        return new ProvenanceUpdate(true, -1000, 2000,
                List.of(new Donor(0, "Alice", "9b5c2f1e-0000-4000-8000-000000000001"), new Donor(1, "box-7", "world-abc")),
                List.of(new Entry(-1000, 2000, Source.SERVER, -1), new Entry(-990, 2005, Source.DONOR, 1),
                        new Entry(-1032, 1968, Source.RESUMED, -1), new Entry(-968, 2032, Source.REJECTED, -1)),
                new Summary(3, 1200, 340));
    }

    @Test
    void subscribeRoundTripAndFlags() {
        for (boolean o : new boolean[] {false, true}) {
            for (boolean h : new boolean[] {false, true}) {
                ByteBuf b = Unpooled.buffer();
                OverlaySubscribe.CODEC.encode(b, new OverlaySubscribe(o, h));
                OverlaySubscribe back = OverlaySubscribe.CODEC.decode(b);
                assertTrue(back.valid());
                assertEquals(o, back.overlay());
                assertEquals(h, back.hud());
                assertEquals(0, b.readableBytes());
            }
        }
        assertEquals("mcdonate:overlay", OverlaySubscribe.TYPE.id().toString());
    }

    @Test
    void subscribeBadDataIsInvalidNotThrown() {
        ByteBuf b = Unpooled.buffer();
        ByteBufCodecs.VAR_INT.encode(b, 9);
        ByteBufCodecs.VAR_INT.encode(b, 1);
        assertFalse(OverlaySubscribe.CODEC.decode(b).valid());     // 版本不认识
        ByteBuf c = Unpooled.buffer();
        ByteBufCodecs.VAR_INT.encode(c, OverlaySubscribe.FORMAT);
        ByteBufCodecs.VAR_INT.encode(c, 8);
        assertFalse(OverlaySubscribe.CODEC.decode(c).valid());     // 不认识的位
        assertFalse(OverlaySubscribe.CODEC.decode(Unpooled.buffer().writeByte(0xff)).valid());   // 截断
    }

    @Test
    void updateRoundTrip() {
        ProvenanceUpdate v = sample();
        ByteBuf b = enc(v);
        ProvenanceUpdate back = ProvenanceUpdate.CODEC.decode(b);
        assertTrue(back.valid());
        assertEquals(v, back);
        assertEquals(0, b.readableBytes());
        assertEquals("mcdonate:provenance", ProvenanceUpdate.TYPE.id().toString());
        // 没有摘要（hud 关着）也能往返
        ProvenanceUpdate noSummary = new ProvenanceUpdate(false, 0, 0, List.of(), List.of(new Entry(1, -1, Source.SERVER, -1)), null);
        ProvenanceUpdate back2 = ProvenanceUpdate.CODEC.decode(enc(noSummary));
        assertTrue(back2.valid());
        assertNull(back2.summary());
        assertFalse(back2.full());
    }

    @Test
    void entriesAreSmallOnTheWire() {
        // 坐标按中心的差值编：视距 10 的整片快照（441 个，没有 donor）不到 2 KB
        List<Entry> all = new ArrayList<>();
        for (int dx = -10; dx <= 10; dx++) for (int dz = -10; dz <= 10; dz++) all.add(new Entry(5000 + dx, -7000 + dz, Source.SERVER, -1));
        int bytes = enc(new ProvenanceUpdate(true, 5000, -7000, List.of(), all, null)).readableBytes();
        assertTrue(bytes < 2048, bytes + " 字节");
    }

    @Test
    void splitKeepsEveryPacketUnderTheLimit() {
        List<Entry> many = new ArrayList<>();
        for (int i = 0; i < 5000; i++) many.add(new Entry(i % 65 - 32, i / 65 - 32, Source.SERVER, -1));
        List<Donor> donors = List.of(new Donor(0, "Alice", "k"));
        Summary s = new Summary(1, 10, 5);
        List<ProvenanceUpdate> parts = ProvenanceUpdate.split(true, 0, 0, donors, many, s);
        assertEquals(3, parts.size());
        assertEquals(5000, parts.stream().mapToInt(p -> p.chunks().size()).sum());
        for (ProvenanceUpdate p : parts) {
            assertTrue(p.chunks().size() <= ProvenanceUpdate.MAX_CHUNKS);
            assertTrue(ProvenanceUpdate.CODEC.decode(enc(p)).valid());
        }
        assertTrue(parts.get(0).full() && !parts.get(1).full());    // 只有第一包是"整片快照开始"，后面的是接着补
        assertEquals(donors, parts.get(0).donors());
        assertEquals(s, parts.get(2).summary());
        assertNull(parts.get(0).summary());
    }

    @Test
    void badUpdatesAreInvalidNotThrown() {
        ByteBuf good = enc(sample());
        ByteBuf cut = good.copy(0, good.readableBytes() - 4);
        assertFalse(ProvenanceUpdate.CODEC.decode(cut).valid());
        assertEquals(0, cut.readableBytes());
        assertFalse(ProvenanceUpdate.CODEC.decode(enc(sample()).writeByte(1)).valid());   // 多余字节
        ByteBuf ver = Unpooled.buffer();
        ByteBufCodecs.VAR_INT.encode(ver, 7);
        ver.writeBytes(new byte[] {1, 2, 3});
        assertFalse(ProvenanceUpdate.CODEC.decode(ver).valid());
        // 条数超过上限：只看长度就拒绝，不去读
        ByteBuf huge = Unpooled.buffer();
        ByteBufCodecs.VAR_INT.encode(huge, ProvenanceUpdate.FORMAT);
        huge.writeBoolean(true);
        ByteBufCodecs.VAR_INT.encode(huge, 0);
        ByteBufCodecs.VAR_INT.encode(huge, 0);
        ByteBufCodecs.VAR_INT.encode(huge, 0);
        ByteBufCodecs.VAR_INT.encode(huge, ProvenanceUpdate.MAX_CHUNKS + 1);
        assertFalse(ProvenanceUpdate.CODEC.decode(huge).valid());
        // 来源类别不认识、DONOR 指向没给的 donor、离中心太远：都不合法
        // 给了 donor 表的包里，DONOR 必须指向表里的（没给表的包可以指向之前给过的，见下一条）
        assertFalse(new ProvenanceUpdate(true, 0, 0, List.of(new Donor(0, "A", "k")), List.of(new Entry(0, 0, Source.DONOR, 3)), null).valid());
        assertFalse(new ProvenanceUpdate(true, 0, 0, List.of(), List.of(new Entry(500, 0, Source.SERVER, -1)), null).valid());
        assertFalse(new ProvenanceUpdate(true, 0, 0, List.of(new Donor(0, "", "k")), List.of(), null).valid());
    }

    @Test
    void donorIndexMayReferToAnEarlierPacket() {
        // donor 表每人只发一次：增量包里的 DONOR 可以指向之前包里给过的 donor（客户端记着），所以只在给了表时检查
        ProvenanceUpdate inc = new ProvenanceUpdate(false, 0, 0, List.of(), List.of(new Entry(0, 0, Source.DONOR, 5)), null);
        assertTrue(ProvenanceUpdate.CODEC.decode(enc(inc)).valid());
    }
}
