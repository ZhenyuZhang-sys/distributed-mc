package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import mcdonate.common.TilePos;
import mcdonate.coordinator.RegionRules.ChunkInfo;
import org.junit.jupiter.api.Test;

/** 第 6 步 6.3：找需求的纯逻辑。 */
class DemandTest {
    @Test
    void tilesAroundPlayer() {
        // 玩家在 chunk (0,0)，半径 14：chunk -14..14 → tile -4..3
        Set<TilePos> t = Demand.tilesAround(0, 0, 14);
        assertEquals(8 * 8, t.size());
        assertTrue(t.contains(new TilePos(-4, -4)) && t.contains(new TilePos(3, 3)));
    }

    @Test
    void tilesAroundNegativePlayer() {
        Set<TilePos> t = Demand.tilesAround(-17, 5, 0);
        assertEquals(Set.of(new TilePos(-5, 1)), t);
    }

    @Test
    void orderNearestFirst() {
        // 离玩家近的 tile 先入队
        List<TilePos> order = Demand.nearestFirst(Set.of(new TilePos(3, 3), new TilePos(0, 0), new TilePos(-2, 0)), 1, 1);
        assertEquals(new TilePos(0, 0), order.get(0));
        assertEquals(new TilePos(3, 3), order.get(2));
    }

    @Test
    void decidePerRegion() {
        // tile (0,0) 的区域是 chunk -1..4；里面有一个服务器的 chunk 就不入队，有同一个世界的归属就认主
        Map<Long, ChunkInfo> world = Map.of(
                Demand.key(4, 4), ChunkInfo.server(),
                Demand.key(9, 0), ChunkInfo.owned("W", 8));
        assertEquals(false, Demand.decide(new TilePos(0, 0), k -> world.getOrDefault(k, ChunkInfo.empty())).enqueue());
        RegionRules.Enqueue e = Demand.decide(new TilePos(2, 0), k -> world.getOrDefault(k, ChunkInfo.empty()));
        assertEquals("W", e.world());
        assertEquals(RegionRules.Enqueue.free(), Demand.decide(new TilePos(5, 5), k -> ChunkInfo.empty()));
    }
}
