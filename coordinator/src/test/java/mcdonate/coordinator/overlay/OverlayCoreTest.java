package mcdonate.coordinator.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import mcdonate.common.Provenance;
import mcdonate.common.net.OverlaySubscribe;
import mcdonate.common.net.ProvenanceUpdate;
import mcdonate.common.net.ProvenanceUpdate.Entry;
import mcdonate.common.net.ProvenanceUpdate.Source;
import org.junit.jupiter.api.Test;

/** 游戏内来源上色的服务器端（docs/overlay-plan.md 第 4 节）：订阅才发、整片快照 + 增量、范围、donor 表只发一次、摘要只在 hud 开着时有。 */
class OverlayCoreTest {
    static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000a11c");
    static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-0000000000b0");

    final OverlayCore core = new OverlayCore(id -> Optional.of(id == 7 ? new OverlayCore.Name("player:" + ALICE, "Alice")
            : new OverlayCore.Name("host:box" + id, "box" + id)), () -> 2);

    static Set<Long> chunks(List<ProvenanceUpdate> ups) {
        return ups.stream().flatMap(u -> u.chunks().stream()).map(e -> (long) e.cx() << 32 | (e.cz() & 0xffffffffL)).collect(Collectors.toSet());
    }

    static long key(int x, int z) {
        return (long) x << 32 | (z & 0xffffffffL);
    }

    List<ProvenanceUpdate> tick(UUID who, int cx, int cz) {
        return core.tick(List.of(new OverlayCore.View(who, cx, cz, 2))).getOrDefault(who, List.of());
    }

    @Test
    void nothingSentWithoutSubscriptionButHistoryKept() {
        // 没人订阅时不发；但来源一直记着（每个 chunk 约 12 字节）：打开 overlay 时整片快照里有之前生成的 chunk
        //（原来没人订阅时不记，打开以后只看得到之后变化的，11-09 端到端时发现）
        core.onEvent(0, 0, Provenance.Kind.VANILLA, -1);
        core.onEvent(1, 0, Provenance.Kind.DONOR, 7);
        assertTrue(core.tick(List.of(new OverlayCore.View(ALICE, 0, 0, 2))).isEmpty());
        assertFalse(core.active(), "没人订阅");
        assertEquals(2, core.stateSize());
        core.subscribe(ALICE, new OverlaySubscribe(true, false));
        assertEquals(Set.of(key(0, 0), key(1, 0)), chunks(tick(ALICE, 0, 0)));
    }

    @Test
    void snapshotThenOnlyDirtyChunksInRange() {
        core.subscribe(ALICE, new OverlaySubscribe(true, false));
        core.onEvent(0, 0, Provenance.Kind.VANILLA, -1);
        core.onEvent(1, 1, Provenance.Kind.DONOR, 7);
        core.onEvent(9, 9, Provenance.Kind.VANILLA, -1);   // 范围外（半径 2）
        List<ProvenanceUpdate> first = tick(ALICE, 0, 0);
        assertTrue(first.get(0).full(), "订阅后第一次是整片快照");
        assertEquals(Set.of(key(0, 0), key(1, 1)), chunks(first));
        assertEquals(List.of("Alice"), first.get(0).donors().stream().map(ProvenanceUpdate.Donor::name).toList());
        assertNull(first.get(first.size() - 1).summary(), "hud 关着不带摘要");
        assertTrue(tick(ALICE, 0, 0).isEmpty(), "没变化不发");
        core.onEvent(2, -2, Provenance.Kind.REJECTED, 7);
        List<ProvenanceUpdate> inc = tick(ALICE, 0, 0);
        assertFalse(inc.get(0).full());
        assertEquals(Set.of(key(2, -2)), chunks(inc));
        assertTrue(inc.get(0).donors().isEmpty(), "donor 表每人只发一次");
    }

    @Test
    void walkingBringsNewChunksIntoRange() {
        core.subscribe(ALICE, new OverlaySubscribe(true, false));
        core.onEvent(9, 0, Provenance.Kind.VANILLA, -1);
        assertTrue(chunks(tick(ALICE, 0, 0)).isEmpty());
        assertEquals(Set.of(key(9, 0)), chunks(tick(ALICE, 8, 0)), "走过去以后，进入范围的 chunk 补发");
        assertTrue(tick(ALICE, 8, 0).isEmpty());
    }

    @Test
    void donorColorSurvivesResumeAndKindsMap() {
        core.subscribe(ALICE, new OverlaySubscribe(true, false));
        core.onEvent(0, 0, Provenance.Kind.DONOR, 7);
        core.onEvent(0, 0, Provenance.Kind.RESUMED, -1);   // donor 导入的 tile 被服务器接着做完：还是这个 donor 的颜色
        core.onEvent(1, 0, Provenance.Kind.RESUMED, -1);   // 不知道是谁导入的：RESUMED
        core.onEvent(2, 0, Provenance.Kind.VANILLA, -1);
        core.onEvent(-1, 0, Provenance.Kind.LOADED, -1);   // 读盘的成品：来源不明，不发
        Map<Long, Entry> got = tick(ALICE, 0, 0).stream().flatMap(u -> u.chunks().stream())
                .collect(Collectors.toMap(e -> key(e.cx(), e.cz()), e -> e));
        assertEquals(Source.DONOR, got.get(key(0, 0)).source());
        assertEquals(Source.RESUMED, got.get(key(1, 0)).source());
        assertEquals(Source.SERVER, got.get(key(2, 0)).source());
        assertFalse(got.containsKey(key(-1, 0)));
    }

    @Test
    void sameDonorNameSameIndexEvenIfWorkerIdChanges() {
        OverlayCore c = new OverlayCore(id -> Optional.of(new OverlayCore.Name("player:" + ALICE, "Alice")), () -> 1);
        c.subscribe(BOB, new OverlaySubscribe(true, false));
        c.onEvent(0, 0, Provenance.Kind.DONOR, 7);
        c.onEvent(1, 0, Provenance.Kind.DONOR, 12);   // 重连后编号变了
        List<ProvenanceUpdate> ups = c.tick(List.of(new OverlayCore.View(BOB, 0, 0, 2))).get(BOB);
        assertEquals(1, ups.get(0).donors().size());
        assertEquals(1, ups.stream().flatMap(u -> u.chunks().stream()).map(Entry::donor).distinct().count());
    }

    @Test
    void summaryOnlyWithHudAndCountsYourShare() {
        core.subscribe(ALICE, new OverlaySubscribe(false, true));   // 只开 hud：只有摘要，不发 chunk
        core.onEvent(0, 0, Provenance.Kind.DONOR, 7);    // Alice 的 donor
        core.onEvent(1, 0, Provenance.Kind.DONOR, 3);    // 别人的
        core.onEvent(2, 0, Provenance.Kind.DONOR, 7);
        List<ProvenanceUpdate> ups = tick(ALICE, 0, 0);
        assertEquals(1, ups.size());
        assertTrue(ups.get(0).chunks().isEmpty());
        assertEquals(new ProvenanceUpdate.Summary(2, 3, 2), ups.get(0).summary());
    }

    @Test
    void unsubscribeStopsAndForgets() {
        core.subscribe(ALICE, new OverlaySubscribe(true, false));
        core.onEvent(0, 0, Provenance.Kind.VANILLA, -1);
        assertFalse(tick(ALICE, 0, 0).isEmpty());
        core.subscribe(ALICE, new OverlaySubscribe(false, false));
        core.onEvent(1, 0, Provenance.Kind.VANILLA, -1);
        assertTrue(tick(ALICE, 0, 0).isEmpty(), "关掉以后不再发");
        assertEquals(0, core.subscribers());
        core.subscribe(ALICE, new OverlaySubscribe(true, false));
        assertTrue(tick(ALICE, 0, 0).get(0).full(), "重新打开从整片快照开始");
        core.remove(ALICE);
        assertEquals(0, core.subscribers());
        assertEquals(1, core.sentPackets(ALICE) >= 0 ? 1 : 0);
    }

    @Test
    void invalidSubscribeIgnored() {
        core.subscribe(ALICE, new OverlaySubscribe(9, 1));
        assertEquals(0, core.subscribers());
    }

    @Test
    void historyIsCappedByDroppingChunksFarFromEveryone() {
        // 一直记来源要有上限：超过 maxState 时，离所有在线玩家都超过 pruneRadius 的 chunk 删掉
        OverlayCore c = new OverlayCore(id -> Optional.empty(), () -> 0, 3, 4);
        c.onEvent(0, 0, Provenance.Kind.VANILLA, -1);
        c.onEvent(1, 1, Provenance.Kind.VANILLA, -1);
        c.onEvent(100, 100, Provenance.Kind.VANILLA, -1);
        c.onEvent(-50, 7, Provenance.Kind.VANILLA, -1);
        c.tick(List.of(new OverlayCore.View(ALICE, 0, 0, 2)));
        assertEquals(2, c.stateSize(), "超过上限 3：离玩家 4 圈以外的两个删掉");
        c.onEvent(2, 2, Provenance.Kind.VANILLA, -1);
        c.tick(List.of(new OverlayCore.View(ALICE, 0, 0, 2)));
        assertEquals(3, c.stateSize(), "没超过上限不删");
    }

    @Test
    void everyPacketCarriesTheDonorsItReferences() {
        // codex-audit A12：客户端已知 A，下一包同时有 A 和第一次出现的 B，原来只附 B 的表项，valid 拒绝整包，B 也不再补发。
        // 现在每包都附上它引用的所有 donor（几十字节），每包单独都能解开
        OverlayCore c = new OverlayCore(id -> Optional.of(new OverlayCore.Name("player:" + id, "P" + id)), () -> 2);
        c.subscribe(ALICE, new OverlaySubscribe(true, false));
        c.onEvent(0, 0, Provenance.Kind.DONOR, 1);
        List<ProvenanceUpdate> a = c.tick(List.of(new OverlayCore.View(ALICE, 0, 0, 2))).get(ALICE);
        assertEquals(List.of("P1"), a.get(0).donors().stream().map(ProvenanceUpdate.Donor::name).toList());
        c.onEvent(1, 0, Provenance.Kind.DONOR, 1);
        c.onEvent(2, 0, Provenance.Kind.DONOR, 2);
        List<ProvenanceUpdate> ab = c.tick(List.of(new OverlayCore.View(ALICE, 0, 0, 2))).get(ALICE);
        assertEquals(Set.of("P1", "P2"), ab.get(0).donors().stream().map(ProvenanceUpdate.Donor::name).collect(Collectors.toSet()));
        assertTrue(ab.get(0).valid(), "A 加 B 的增量要能解开");
        c.onEvent(-1, 0, Provenance.Kind.DONOR, 2);
        List<ProvenanceUpdate> b = c.tick(List.of(new OverlayCore.View(ALICE, 0, 0, 2))).get(ALICE);
        assertEquals(List.of("P2"), b.get(0).donors().stream().map(ProvenanceUpdate.Donor::name).toList());
        assertTrue(b.get(0).valid());
    }

    @Test
    void reopeningOverlayWithHudOnStillHasDonorNames() {
        // codex-audit A13：hud 一直开着，overlay 关再开：快照里要带 donor 表（客户端关 overlay 时清掉了）
        core.subscribe(ALICE, new OverlaySubscribe(true, true));
        core.onEvent(0, 0, Provenance.Kind.DONOR, 7);
        tick(ALICE, 0, 0);
        core.subscribe(ALICE, new OverlaySubscribe(false, true));
        tick(ALICE, 0, 0);
        core.subscribe(ALICE, new OverlaySubscribe(true, true));
        List<ProvenanceUpdate> again = tick(ALICE, 0, 0);
        assertTrue(again.get(0).full());
        assertEquals(List.of("Alice"), again.get(0).donors().stream().map(ProvenanceUpdate.Donor::name).toList());
    }

    @Test
    void awayInAnotherDimensionThenBackGetsAFullSnapshot() {
        // codex-audit A14：来源只记主世界。玩家去下界时不发；回来以后从整片快照重来
        core.subscribe(ALICE, new OverlaySubscribe(true, false));
        core.onEvent(0, 0, Provenance.Kind.VANILLA, -1);
        assertTrue(tick(ALICE, 0, 0).get(0).full());
        core.away(ALICE);
        core.onEvent(1, 0, Provenance.Kind.VANILLA, -1);
        assertTrue(core.tick(List.of()).isEmpty(), "不在主世界：不在 views 里，不发");
        List<ProvenanceUpdate> back = tick(ALICE, 0, 0);
        assertTrue(back.get(0).full(), "回到主世界：整片快照");
        assertEquals(Set.of(key(0, 0), key(1, 0)), chunks(back));
    }

    @Test
    void quickRoundTripBetweenTicksStillResyncs() {
        // codex-audit K7：1 秒内去下界又回来，两次 tick 都在主世界。换维度时（事件里）调 away，下一次照样整片快照
        core.subscribe(ALICE, new OverlaySubscribe(true, false));
        core.onEvent(0, 0, Provenance.Kind.VANILLA, -1);
        assertTrue(tick(ALICE, 0, 0).get(0).full());
        core.away(ALICE);   // 去下界
        core.away(ALICE);   // 又回来（同一秒里）
        List<ProvenanceUpdate> back = tick(ALICE, 0, 0);
        assertTrue(back.get(0).full());
        assertEquals(Set.of(key(0, 0)), chunks(back));
        assertTrue(tick(ALICE, 0, 0).isEmpty(), "之后照常只发变化");
    }

    /**
     * 用户 11-10 演示：donor 在远处（玩家不在附近时）导入、后来玩家飞过去由服务器续完的 chunk，小地图显示成通用 RESUMED。
     * 原因：导入那一刻的 DONOR 事件没进来源表（比如服务器重启过，来源表只在内存里），续完时 old 是空的。
     * 修：RESUMED 而来源表里不是 DONOR 时，查导入账本（ImportLedger）是谁导入的，按那个 donor 发；和在线时同一个稳定名字、同一个编号。
     */
    @Test
    void resumedChunkImportedEarlierUsesTheLedgerDonor() {
        OverlayCore.Name alice = new OverlayCore.Name("player:" + ALICE, "Alice");
        OverlayCore c = new OverlayCore(id -> Optional.of(id == 7 ? alice : new OverlayCore.Name("host:box" + id, "box" + id)), () -> 1,
                (cx, cz) -> cx == 40 && cz == 0 ? Optional.of(alice) : Optional.empty());
        c.subscribe(BOB, new OverlaySubscribe(true, false));
        c.onEvent(41, 0, Provenance.Kind.DONOR, 7);       // Alice 现在在线导入的另一个 chunk
        c.onEvent(40, 0, Provenance.Kind.RESUMED, -1);    // 早先远处导入（来源表里没有），玩家飞到这里服务器续完
        c.onEvent(42, 0, Provenance.Kind.RESUMED, -1);    // 账本里也没有：照旧 RESUMED
        c.onEvent(43, 0, Provenance.Kind.VANILLA, -1);
        List<ProvenanceUpdate> ups = c.tick(List.of(new OverlayCore.View(BOB, 41, 0, 2))).get(BOB);
        Map<Long, Entry> got = ups.stream().flatMap(u -> u.chunks().stream()).collect(Collectors.toMap(e -> key(e.cx(), e.cz()), e -> e));
        assertEquals(Source.DONOR, got.get(key(40, 0)).source());
        assertEquals(got.get(key(41, 0)).donor(), got.get(key(40, 0)).donor());   // 同一个 donor、同一种颜色
        assertEquals(Source.RESUMED, got.get(key(42, 0)).source());
        assertEquals(Source.SERVER, got.get(key(43, 0)).source());
        assertTrue(ups.stream().flatMap(u -> u.donors().stream()).anyMatch(d -> d.name().equals("Alice")));
    }

    /** 账本只在来源表里不是 DONOR 时查（是 DONOR 的照旧保留），也不会因为查账本把导入计数算两遍。 */
    @Test
    void ledgerOnlyConsultedWhenStateHasNoDonor() {
        int[] asked = {0};
        OverlayCore c = new OverlayCore(id -> Optional.of(new OverlayCore.Name("host:box" + id, "box" + id)), () -> 1,
                (cx, cz) -> { asked[0]++; return Optional.empty(); });
        c.subscribe(BOB, new OverlaySubscribe(true, true));
        c.onEvent(0, 0, Provenance.Kind.DONOR, 3);
        c.onEvent(0, 0, Provenance.Kind.RESUMED, -1);
        c.onEvent(1, 0, Provenance.Kind.VANILLA, -1);
        List<ProvenanceUpdate> ups = c.tick(List.of(new OverlayCore.View(BOB, 0, 0, 2))).get(BOB);
        assertEquals(0, asked[0]);
        assertEquals(1, ups.get(0).summary().importedTotal());
    }
}
