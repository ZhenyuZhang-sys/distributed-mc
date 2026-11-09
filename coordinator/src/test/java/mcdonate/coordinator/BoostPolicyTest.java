package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(1, BoostPolicy.backgroundSlots(1));
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
}
