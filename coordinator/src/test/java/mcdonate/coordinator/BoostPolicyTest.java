package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mcdonate.common.TilePos;
import org.junit.jupiter.api.Test;

/** 11-09 加速模式：开了加速的 donor 在途上限按它的线程数；后台 tile 的候选顺序（像 Chunky：一圈一圈往外）。 */
class BoostPolicyTest {
    @Test
    void inFlightFollowsThreadsWhenBoosted() {
        assertEquals(4, BoostPolicy.maxInFlight(4, false, 8), "不开加速照旧");
        assertEquals(8, BoostPolicy.maxInFlight(4, true, 8), "8 个线程：8 个在途");
        assertEquals(4, BoostPolicy.maxInFlight(4, true, 2), "线程少时不比原来少");
        assertEquals(4, BoostPolicy.maxInFlight(4, true, 0), "不知道线程数：照旧");
        assertEquals(BoostPolicy.MAX_IN_FLIGHT, BoostPolicy.maxInFlight(4, true, 64), "有上限");
    }

    @Test
    void backgroundSlotsHalf() {
        assertEquals(2, BoostPolicy.backgroundSlots(4));
        assertEquals(4, BoostPolicy.backgroundSlots(8));
        assertEquals(1, BoostPolicy.backgroundSlots(3));
        // codex-audit 第十七节 B2：只有 1 个名额时不做后台（做了就把唯一的名额占了，前方的急活进不来）
        assertEquals(0, BoostPolicy.backgroundSlots(1));
    }

    @Test
    void backgroundImportGate() {
        // B1：后台结果的导入只在主线程闲时做：这个 tick 没导前方的、前方队里没有、平均 tick 不超过 30 ms、离上一个后台至少 4 tick
        assertTrue(BoostPolicy.backgroundImportAllowed(false, false, 20, 4));
        assertFalse(BoostPolicy.backgroundImportAllowed(true, false, 20, 4), "这个 tick 已经导过前方的");
        assertFalse(BoostPolicy.backgroundImportAllowed(false, true, 20, 4), "前方还有排着的");
        assertFalse(BoostPolicy.backgroundImportAllowed(false, false, 45, 4), "服务器忙");
        assertFalse(BoostPolicy.backgroundImportAllowed(false, false, 20, 2), "刚导过一个后台");
    }

    @Test
    void ringsOutwardFromStart() {
        // 玩家在 chunk (0, 0)，从 12 圈以外开始（找需求的 outer），到 24 圈为止
        List<TilePos> c = BoostPolicy.backgroundOrder(new int[][] {{0, 0, 12}}, 24);
        assertTrue(!c.isEmpty());
        int last = -1;
        Set<TilePos> seen = new HashSet<>();
        for (TilePos t : c) {
            int d = Demand.minChunkDistance(t, 0, 0);
            assertTrue(d > 12 && d <= 24, t + " 距离 " + d);
            assertTrue(d >= last, "一圈一圈往外：" + t);
            assertTrue(seen.add(t), "不重复：" + t);
            last = d;
        }
    }

    @Test
    void twoCentersInterleaveAndDedupe() {
        // 两个玩家（chunk 0,0 和 16,0）：在谁的起始圈以内都不要（那是服务器自己的范围）；
        // 按"离最近的玩家几圈"一圈一圈往外，同一圈里两人的都排在下一圈前面；重叠的只出现一次
        List<TilePos> c = BoostPolicy.backgroundOrder(new int[][] {{0, 0, 4}, {16, 0, 4}}, 12);
        assertEquals(new HashSet<>(c).size(), c.size(), "不重复");
        int last = -1;
        for (TilePos t : c) {
            int a = Demand.minChunkDistance(t, 0, 0), b = Demand.minChunkDistance(t, 16, 0);
            assertTrue(a > 4 && b > 4, t + " 在某个玩家的起始圈以内");
            int ring = Math.min(a, b);
            assertTrue(ring <= 12 && ring >= last, t + " 圈 " + ring);
            last = ring;
        }
    }

    @Test
    void presetCenterStartsAtZero() {
        List<TilePos> c = BoostPolicy.backgroundOrder(new int[][] {{100, 100, -1}}, 4);
        assertEquals(TilePos.ofChunk(100, 100), c.get(0), "预设中心从中心那个 tile 开始");
    }

    @Test
    void boostDonorKeepsRoomForOneBackground() {
        // 11-10 f2 端到端：worker 之前一直一个一个派（lanes 1），开了加速：在途上限 1、后台名额 0，后台排了 64 个一个都不派。
        // 开了加速的至少给 2 个（1 个后台、1 个留给前方；前方可能在 worker 上排在那 1 个后台后面，这是开加速的代价）
        assertEquals(2, BoostPolicy.dispatchCap(1, true, 3));
        assertEquals(1, BoostPolicy.dispatchCap(1, false, 3), "不开加速照旧");
        assertEquals(1, BoostPolicy.dispatchCap(1, true, 1), "只有 1 个线程：照旧");
        assertEquals(4, BoostPolicy.dispatchCap(4, true, 8));
        assertEquals(1, BoostPolicy.backgroundSlots(BoostPolicy.dispatchCap(1, true, 3)));
    }

    // ---- 11-10 演示：后台先沿玩家朝向往前铺（站着看视线方向、走着看移动方向），再做两侧 ----

    @Test
    void forwardCorridorFirst() {
        // 玩家在 chunk (0,0)、朝 +x，起始圈 4、半径 32、走廊半宽 10 chunk：先把正前方一条铺到 32 圈，再做两侧
        List<TilePos> c = BoostPolicy.backgroundOrder(new int[][] {{0, 0, 4}}, new double[][] {{1, 0}}, 32, 10);
        boolean sides = false;
        int lastAhead = -1;
        for (TilePos t : c) {
            boolean corridor = BoostPolicy.inCorridor(t, 0, 0, 1, 0, 10);
            if (!corridor) {
                sides = true;
                continue;
            }
            assertTrue(!sides, "走廊里的都排在两侧前面：" + t);
            int d = Demand.minChunkDistance(t, 0, 0);
            assertTrue(d >= lastAhead, "走廊里按远近往前：" + t);
            lastAhead = d;
        }
        assertTrue(sides, "走廊铺完以后两侧照样做");
        for (TilePos t : c.subList(0, 10)) {
            assertTrue(BoostPolicy.inCorridor(t, 0, 0, 1, 0, 10), "最先做的都在前方走廊里：" + t);
        }
        assertTrue(lastAhead >= 28, "走廊一直铺到半径附近：" + lastAhead);
    }

    @Test
    void corridorIsAheadOnly() {
        assertTrue(BoostPolicy.inCorridor(new TilePos(5, 0), 0, 0, 1, 0, 10));
        assertTrue(!BoostPolicy.inCorridor(new TilePos(-5, 0), 0, 0, 1, 0, 10), "身后的不算");
        assertTrue(!BoostPolicy.inCorridor(new TilePos(5, 5), 0, 0, 1, 0, 10), "离线 20 chunk：两侧");
        assertTrue(!BoostPolicy.inCorridor(new TilePos(5, 0), 0, 0, 0, 0, 10), "没有方向：没有走廊");
    }

    @Test
    void noDirectionKeepsRings() {
        List<TilePos> a = BoostPolicy.backgroundOrder(new int[][] {{0, 0, 12}}, 24);
        List<TilePos> b = BoostPolicy.backgroundOrder(new int[][] {{0, 0, 12}}, new double[][] {{0, 0}}, 24, 10);
        assertEquals(a, b, "没有方向（预设中心、不知道朝向）：照旧一圈一圈");
    }

    @Test
    void directionFromYaw() {
        // Minecraft 的 yaw：0 朝 +z（南），90 朝 -x（西），-90 朝 +x（东）
        double[] east = BoostPolicy.directionFromYaw(-90);
        assertEquals(1, east[0], 1e-9);
        assertEquals(0, east[1], 1e-9);
        double[] south = BoostPolicy.directionFromYaw(0);
        assertEquals(0, south[0], 1e-9);
        assertEquals(1, south[1], 1e-9);
    }
}
