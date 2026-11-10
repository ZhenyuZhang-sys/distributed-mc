package mcdonate.bench.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

class ViewTrackerTest {
    record Ev(long t, int x, int z, ViewTracker.Kind kind) {}

    final List<Ev> events = new ArrayList<>();
    final Set<Long> ready = new HashSet<>();
    final ViewTracker.Readiness isReady = (x, z) -> ready.contains(ChunkPos.asLong(x, z));

    ViewTracker tracker() {
        return new ViewTracker(10, (t, x, z, k) -> events.add(new Ev(t, x, z, k)));
    }

    void readyAll(int cx, int cz) {
        ChunkTrackingView.of(new ChunkPos(cx, cz), 10).forEach(p -> ready.add(p.toLong()));
    }

    @Test
    void viewSizeMatchesVanillaSendSet() {
        ViewTracker t = tracker();
        readyAll(0, 0);
        ViewTracker.Snapshot s = t.start(0, 0, isReady);
        assertEquals(453, s.view());
        assertEquals(0, s.notReady());
        assertEquals(List.of(), events);
    }

    @Test
    void initialViewIsSilent() {
        ViewTracker t = tracker();
        assertEquals(453, t.start(0, 0, isReady).notReady());
        readyAll(0, 0);
        assertEquals(0, t.tick(50, 0, 0, isReady).notReady());
        assertEquals(List.of(), events, "初始视野就绪不发事件");
    }

    @Test
    void movingOneChunkEmitsEnterAndLeave() {
        ViewTracker t = tracker();
        readyAll(0, 0);
        t.start(0, 0, isReady);
        ViewTracker.Snapshot s = t.tick(100, 1, 0, isReady);
        long enters = events.stream().filter(e -> e.kind == ViewTracker.Kind.ENTER).count();
        long leaves = events.stream().filter(e -> e.kind == ViewTracker.Kind.LEAVE).count();
        assertTrue(enters > 0);
        assertEquals(0, leaves, "离开的是初始视野，不发 leave");
        assertEquals(enters, s.notReady(), "新进来的都还没就绪");
        assertTrue(events.stream().allMatch(e -> e.t == 100 && e.x >= 1));
    }

    @Test
    void readyLaterEmitsReadyOnce() {
        ViewTracker t = tracker();
        readyAll(0, 0);
        t.start(0, 0, isReady);
        t.tick(100, 1, 0, isReady);
        int entered = events.size();
        readyAll(1, 0);
        t.tick(150, 1, 0, isReady);
        t.tick(200, 1, 0, isReady);
        List<Ev> readies = events.subList(entered, events.size());
        assertEquals(entered, readies.size());
        assertTrue(readies.stream().allMatch(e -> e.kind == ViewTracker.Kind.READY && e.t == 150));
    }

    @Test
    void readyOnEntrySameTimestamp() {
        ViewTracker t = tracker();
        readyAll(0, 0);
        readyAll(1, 0);
        t.start(0, 0, isReady);
        t.tick(100, 1, 0, isReady);
        assertEquals(0, events.size() % 2);
        for (int i = 0; i < events.size(); i += 2) {
            assertEquals(ViewTracker.Kind.ENTER, events.get(i).kind);
            assertEquals(ViewTracker.Kind.READY, events.get(i + 1).kind);
            assertEquals(events.get(i).x, events.get(i + 1).x);
        }
    }

    @Test
    void enteredChunkLeavingEmitsLeaveAndReenterCountsAgain() {
        ViewTracker t = tracker();
        t.start(0, 0, isReady);
        t.tick(100, 1, 0, isReady);                  // 进来一列新的
        Ev first = events.stream().filter(e -> e.kind == ViewTracker.Kind.ENTER).findFirst().orElseThrow();
        t.tick(200, 0, 0, isReady);                  // 退回去：这列离开
        assertTrue(events.contains(new Ev(200, first.x, first.z, ViewTracker.Kind.LEAVE)));
        t.tick(300, 1, 0, isReady);                  // 再进来
        assertTrue(events.contains(new Ev(300, first.x, first.z, ViewTracker.Kind.ENTER)));
    }

    @Test
    void voidRate() {
        assertEquals(0.5, new ViewTracker.Snapshot(10, 5).voidRate(), 1e-9);
        assertEquals(0.0, new ViewTracker.Snapshot(0, 0).voidRate(), 1e-9);
    }

    @Test
    void misuseRejected() {
        ViewTracker t = tracker();
        assertThrows(IllegalStateException.class, () -> t.tick(0, 0, 0, isReady));
        t.start(0, 0, isReady);
        assertThrows(IllegalStateException.class, () -> t.start(0, 0, isReady));
        assertThrows(IllegalArgumentException.class, () -> new ViewTracker(1, (a, b, c, d) -> {}));
    }

    @Test
    void initialStatesAreKeptForTheArchive() {
        // codex-audit K1：初始视野不发事件，逐 chunk 的就绪状态单独存（initial.jsonl），重放时从它开始
        ViewTracker t = tracker();
        readyAll(0, 0);
        ready.remove(ChunkPos.asLong(3, 4));
        t.start(0, 0, isReady);
        t.tick(50, 5, 0, isReady);   // 之后的变化不影响初始记录
        String[] lines = t.initialJsonl().split("\n");
        assertEquals(453, lines.length);
        assertTrue(java.util.Arrays.asList(lines).contains("{\"cx\":3,\"cz\":4,\"ready\":false}"));
        assertTrue(java.util.Arrays.asList(lines).contains("{\"cx\":0,\"cz\":0,\"ready\":true}"));
    }
}
