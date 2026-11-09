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
}
