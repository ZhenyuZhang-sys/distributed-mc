package mcdonate.bench.flight;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 跟踪真人玩家（第 10 步 7.2）：真人客户端自己会停在没收到的 chunk 边缘。一个 tick 算卡住 =
 * 这个 tick 几乎没动（< 0.01 格），而且视线水平方向上、2 格以内就要进的那个 chunk 没就绪。
 * 站着看风景（前方就绪）、离边缘还远、在动，都不算。这是近似，报告里照写。
 */
class RealStallTest {
    // 原版朝向：yaw 0 朝 +z，90 朝 -x，-90（270）朝 +x，180 朝 -z
    static final float EAST = -90f, SOUTH = 0f, NORTH = 180f;

    static Flight.Ready unreadyAt(int ux, int uz) {
        return (cx, cz) -> !(cx == ux && cz == uz);
    }

    @Test
    void standingAtEdgeFacingUnreadyChunkIsStalled() {
        // x = 15.5 在 chunk 0 的东边缘，前方（+x）是 chunk 1，没就绪
        assertTrue(RealStall.stalled(0.0, 15.5, 8.0, EAST, unreadyAt(1, 0)));
    }

    @Test
    void readyAheadIsNotStalled() {
        assertFalse(RealStall.stalled(0.0, 15.5, 8.0, EAST, (cx, cz) -> true));
    }

    @Test
    void movingIsNotStalled() {
        assertFalse(RealStall.stalled(0.3, 15.5, 8.0, EAST, unreadyAt(1, 0)));
    }

    @Test
    void farFromEdgeIsNotStalled() {
        // chunk 中间看着远处没就绪的 chunk：不算卡住（玩家只是站着）
        assertFalse(RealStall.stalled(0.0, 8.0, 8.0, EAST, unreadyAt(1, 0)));
    }

    @Test
    void lookDirectionDecidesWhichChunkIsAhead() {
        // 在 chunk 0 的东南角附近：朝东看前方是 (1,0)，朝南看前方是 (0,1)
        assertTrue(RealStall.stalled(0.0, 15.5, 15.5, SOUTH, unreadyAt(0, 1)));
        assertFalse(RealStall.stalled(0.0, 15.5, 15.5, SOUTH, unreadyAt(1, 0)));
        assertFalse(RealStall.stalled(0.0, 15.5, 15.5, NORTH, unreadyAt(0, 1)));
    }

    @Test
    void negativeCoordinates() {
        // x = -16.5 在 chunk -2（方块 -32～-17）的东边缘，朝东前方是 chunk -1
        assertTrue(RealStall.stalled(0.0, -16.5, 8.0, EAST, unreadyAt(-1, 0)));
        // 朝西（yaw 90）：-0.5 在 chunk -1 的东边缘，西边 2 格内还是 chunk -1 自己，不算
        assertFalse(RealStall.stalled(0.0, -0.5, 8.0, 90f, unreadyAt(-2, 0)));
    }
}
