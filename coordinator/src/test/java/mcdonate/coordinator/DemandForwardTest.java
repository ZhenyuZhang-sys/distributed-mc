package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mcdonate.common.TilePos;
import org.junit.jupiter.api.Test;

/**
 * 11-09 找需求先看正前方（demandForwardFirst）：直线飞时前方空白大多在 chunk 自己 FULL 之前，待齐邻居里 39% 的 tile
 * 整轮没派过（codex-pipe）。扫描按离玩家远近、预算用完就停，正前方远处的轮不到。
 * 开着时每个玩家的候选先扫前方走廊里的（按远近），再扫其余的（按远近）。不记游标，每次从头来。
 */
class DemandForwardTest {
    /** 沿 +x 飞（30 格/秒），在方块 (8, 8)，即 chunk (0, 0)。 */
    private static Motion flyingEast() {
        Motion m = new Motion();
        m.record(0, -52, 8);
        m.record(1000, -22, 8);
        m.record(2000, 8, 8);
        return m;
    }

    @Test
    void forwardIsAheadAndInsideCorridor() {
        Motion m = flyingEast();
        assertTrue(m.forward(new TilePos(5, 0), 6), "正前方");
        assertTrue(m.forward(new TilePos(5, 1), 6), "前方、离线 5 个 chunk");
        assertFalse(m.forward(new TilePos(5, 2), 6), "前方、离线 9 个 chunk：走廊外");
        assertFalse(m.forward(new TilePos(-5, 0), 6), "正后方：在线上但在身后");
        assertFalse(m.forward(new TilePos(0, 5), 6), "正侧面");
    }

    @Test
    void notMovingHasNoForward() {
        Motion m = new Motion();
        m.record(0, 8, 8);
        m.record(1000, 8, 8);
        assertFalse(m.forward(new TilePos(5, 0), 6));
    }

    @Test
    void forwardUsesBothWindowsWhenTurning() {
        // 先沿 +x，最后 0.75 秒转向 +z：新旧两个方向前方的都算
        Motion m = new Motion();
        m.record(0, -82, 8);
        m.record(1000, -52, 8);
        m.record(2000, -22, 8);
        m.record(3000, 8, 8);
        m.record(3500, 8, 23);
        double[] p = m.position();
        assertTrue(m.forward(new TilePos((int) Math.floor(p[0] / 64) + 5, (int) Math.floor(p[1] / 64)), 6), "旧方向（+x）前方");
        assertTrue(m.forward(new TilePos((int) Math.floor(p[0] / 64), (int) Math.floor(p[1] / 64) + 5), 6), "新方向（+z）前方");
    }

    @Test
    void orderPutsForwardFirstThenByDistance() {
        // 候选：0 号侧面近，1 号前方远，2 号前方近，3 号侧面远
        int[] tx = {0, 9, 4, 0}, tz = {3, 0, 0, 8};
        double[] d2 = {9, 81, 16, 64};
        boolean[] fwd = {false, true, true, false};
        assertArrayEquals(new int[] {2, 1, 0, 3}, Demand.scanOrder(4, tx, tz, d2, fwd, true));
    }

    @Test
    void orderOffIsDistanceOnly() {
        int[] tx = {0, 9, 4, 0}, tz = {3, 0, 0, 8};
        double[] d2 = {9, 81, 16, 64};
        boolean[] fwd = {false, true, true, false};
        assertArrayEquals(new int[] {0, 2, 3, 1}, Demand.scanOrder(4, tx, tz, d2, fwd, false), "开关关着：和原来一样只按远近");
    }

    @Test
    void orderTiesByCoordinates() {
        int[] tx = {3, 1, 2}, tz = {0, 0, 0};
        double[] d2 = {4, 4, 4};
        boolean[] fwd = {true, true, true};
        assertArrayEquals(new int[] {1, 2, 0}, Demand.scanOrder(3, tx, tz, d2, fwd, true));
    }

    @Test
    void forwardBudgetSplitAcrossPlayers() {
        // 前方组的时间片和读盘额度按玩家平分（S3 每个玩家保底）：一共只给前方组一半，第 i 个玩家到 (i+1)/(2P) 为止
        assertEquals(250, DemandScanner.forwardSliceEnd(1000, 0, 2));
        assertEquals(500, DemandScanner.forwardSliceEnd(1000, 1, 2));
        assertEquals(500, DemandScanner.forwardSliceEnd(1000, 0, 1));
        assertEquals(64, DemandScanner.forwardReadShare(256, 2));
        assertEquals(1, DemandScanner.forwardReadShare(1, 4), "至少给 1 次");
    }
}
