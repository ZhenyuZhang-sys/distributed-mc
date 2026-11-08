package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mcdonate.coordinator.RegionRules.Action;
import mcdonate.coordinator.RegionRules.ChunkInfo;
import mcdonate.coordinator.RegionRules.Enqueue;
import mcdonate.coordinator.RegionRules.ImportPlan;
import org.junit.jupiter.api.Test;

/** docs/step6-plan.md 0.3 的三张规则（入队、导入；派任务在 TaskQueueAffinityTest）。 */
class RegionRulesTest {
    private static final int CARVERS = 6;
    private static final int INIT_LIGHT = 8;

    private static List<ChunkInfo> region(ChunkInfo... special) {
        // 36 个 chunk，默认全空，前几个换成给定的
        ChunkInfo[] all = new ChunkInfo[36];
        for (int i = 0; i < 36; i++) {
            all[i] = i < special.length ? special[i] : ChunkInfo.empty();
        }
        return List.of(all);
    }

    // ---- 入队 ----

    @Test
    void allEmptyEnqueuesFree() {
        assertEquals(Enqueue.free(), RegionRules.enqueue(region()));
    }

    @Test
    void ownedBySameWorldEnqueuesWithAffinity() {
        assertEquals(Enqueue.affinity("W"),
                RegionRules.enqueue(region(ChunkInfo.owned("W", CARVERS), ChunkInfo.owned("W", INIT_LIGHT))));
    }

    @Test
    void serverChunkBlocksEnqueue() {
        assertEquals(Enqueue.no("服务器的 chunk"), RegionRules.enqueue(region(ChunkInfo.server())));
    }

    @Test
    void loadedChunkBlocksEnqueue() {
        assertEquals(Enqueue.no("在内存里"), RegionRules.enqueue(region(ChunkInfo.loaded())));
    }

    @Test
    void twoWorldsBlockEnqueue() {
        assertEquals(Enqueue.no("两个 donor 世界的交界"),
                RegionRules.enqueue(region(ChunkInfo.owned("W", CARVERS), ChunkInfo.owned("V", CARVERS))));
    }

    // ---- 导入 ----

    private static Map<Integer, Integer> incoming(int status) {
        Map<Integer, Integer> m = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            m.put(i, status);
        }
        return m;
    }

    private static Map<Integer, ChunkInfo> current(ChunkInfo... special) {
        Map<Integer, ChunkInfo> m = new HashMap<>();
        List<ChunkInfo> r = region(special);
        for (int i = 0; i < 36; i++) {
            m.put(i, r.get(i));
        }
        return m;
    }

    @Test
    void emptyChunksAreWritten() {
        ImportPlan<Integer> p = RegionRules.importPlan(current(), "W", incoming(CARVERS));
        assertTrue(p.accepted());
        assertTrue(p.actions().values().stream().allMatch(a -> a == Action.WRITE));
    }

    @Test
    void sameWorldNotLaterIsOverwritten() {
        // 同一个 donor 世界后导出的是超集：状态相同或更靠前就覆盖
        ImportPlan<Integer> p = RegionRules.importPlan(
                current(ChunkInfo.owned("W", CARVERS), ChunkInfo.owned("W", INIT_LIGHT)), "W", incoming(INIT_LIGHT));
        assertTrue(p.accepted());
        assertEquals(Action.OVERWRITE, p.actions().get(0));
        assertEquals(Action.OVERWRITE, p.actions().get(1));
        assertEquals(Action.WRITE, p.actions().get(2));
    }

    @Test
    void sameWorldLaterIsSkipped() {
        ImportPlan<Integer> p = RegionRules.importPlan(current(ChunkInfo.owned("W", INIT_LIGHT)), "W", incoming(CARVERS));
        assertTrue(p.accepted());
        assertEquals(Action.SKIP, p.actions().get(0));
    }

    @Test
    void otherWorldDiscardsWholeResult() {
        ImportPlan<Integer> p = RegionRules.importPlan(current(ChunkInfo.owned("V", CARVERS)), "W", incoming(INIT_LIGHT));
        assertEquals(false, p.accepted());
        assertEquals(RegionRules.Discard.OTHER_WORLD, p.discard());
        assertTrue(p.reason().contains("V"), p.reason());
    }

    @Test
    void serverChunkDiscardsWholeResult() {
        // 包括服务器重启后没有归属记录的导入 chunk：一律当服务器的
        assertEquals(false, RegionRules.importPlan(current(ChunkInfo.server()), "W", incoming(INIT_LIGHT)).accepted());
    }

    @Test
    void loadedChunkDiscardsWholeResult() {
        ImportPlan<Integer> p = RegionRules.importPlan(current(ChunkInfo.loaded()), "W", incoming(INIT_LIGHT));
        assertEquals(false, p.accepted());
        assertEquals(RegionRules.Discard.LOADED, p.discard(), "来晚了：区域已经在服务器内存里");
    }
}
