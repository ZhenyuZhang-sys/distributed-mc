package mcdonate.bench.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FlightTest {
    @Test
    void wallClockIgnoresTicks() {
        Flight f = Flight.eastward(100, 50, 200, 30, 120, Flight.Mode.WALL);
        double t = f.elapsed(2_000_000_000L, 20);   // 2 秒墙钟，但只跑了 20 tick（服务器卡）
        assertEquals(2.0, t, 1e-9);
        assertEquals(160.0, f.x(t), 1e-9);
        assertEquals(50.0, f.z(t), 1e-9);
    }

    @Test
    void tickModeFollowsTicks() {
        Flight f = Flight.eastward(0, 0, 200, 30, 120, Flight.Mode.TICK);
        assertEquals(1.0, f.elapsed(2_000_000_000L, 20), 1e-9);
    }

    @Test
    void clampedToDuration() {
        Flight f = Flight.eastward(0, 0, 200, 4.3, 120, Flight.Mode.WALL);
        double t = f.elapsed(500_000_000_000L, 0);
        assertEquals(120.0, t, 1e-9);
        assertEquals(516.0, f.x(t), 1e-9);
        assertTrue(f.finished(t));
        assertFalse(f.finished(f.elapsed(-5, 0)));
        assertEquals(0.0, f.elapsed(-5, 0));
    }

    @Test
    void directionNormalised() {
        Flight f = new Flight(0, 0, 200, 3, 4, 10, 1, Flight.Mode.WALL);
        assertEquals(6.0, f.x(1), 1e-9);
        assertEquals(8.0, f.z(1), 1e-9);
    }

    @Test
    void invalidRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Flight(0, 0, 0, 0, 0, 1, 1, Flight.Mode.WALL));
        assertThrows(IllegalArgumentException.class, () -> Flight.eastward(0, 0, 0, 0, 1, Flight.Mode.WALL));
        assertThrows(IllegalArgumentException.class, () -> Flight.eastward(0, 0, 0, 1, 0, Flight.Mode.WALL));
    }

    // ---- 像真实客户端一样：只走进已就绪的 chunk（11-09，看门狗问题） ----

    static Flight.Ready readyBelow(int maxCx) {
        return (cx, cz) -> cx <= maxCx;
    }

    @Test
    void advancesBySpeedTimesDtWhenEverythingReady() {
        Flight f = Flight.eastward(0, 8, 200, 30, 120, Flight.Mode.WALL);
        Flight.Step s = f.advance(0.5, 8, 0.05, (cx, cz) -> true);
        assertEquals(0.5 + 1.5, s.x(), 1e-9);
        assertEquals(8.0, s.z(), 1e-9);
        assertFalse(s.stalled());
        assertEquals(1.5, s.moved(), 1e-9);
    }

    @Test
    void stopsBeforeFirstUnreadyChunk() {
        // 在 chunk 0（x 0～15）里，chunk 1 没就绪：停在 chunk 0 的边上，不进 chunk 1
        Flight f = Flight.eastward(0, 8, 200, 30, 120, Flight.Mode.WALL);
        // 离边界 1 格，这一 tick 要走 1.5 格：会跨进 chunk 1。
        // 停的位置让碰撞箱前沿（中心 + 0.31，玩家半宽 0.3）也不进 chunk 1：实体 tick 查流体是按碰撞箱查的（11-09 测量里抓到）
        Flight.Step s = f.advance(15.0, 8, 0.05, readyBelow(0));
        assertTrue(s.stalled());
        assertTrue(s.x() + Flight.REACH < 16 && s.x() > 15.6, "x=" + s.x());
        assertEquals(0, Math.floorDiv((int) Math.floor(s.x()), 16));
        // 已经贴着边：一步都走不了，仍然卡住
        Flight.Step again = f.advance(s.x(), 8, 0.05, readyBelow(0));
        assertTrue(again.stalled());
        assertEquals(s.x(), again.x(), 1e-9);
    }

    @Test
    void longTickCrossesReadyChunksButNotTheUnreadyOne() {
        // 服务器卡了 3 秒：客户端按自己的帧率走，穿过已就绪的 chunk 0～2，停在 chunk 3 前面
        Flight f = Flight.eastward(0, 8, 200, 30, 120, Flight.Mode.WALL);
        Flight.Step s = f.advance(1.0, 8, 3.0, readyBelow(2));
        assertTrue(s.stalled());
        assertTrue(s.x() + Flight.REACH < 48 && s.x() > 47.6, "x=" + s.x());
    }

    @Test
    void neverEndsInsideUnreadyChunk() {
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 2000; i++) {
            double speed = 0.5 + r.nextDouble() * 100, dt = r.nextDouble() * 2;
            int edge = r.nextInt(6);
            Flight f = Flight.eastward(0, 8, 200, speed, 120, Flight.Mode.WALL);
            double x = r.nextDouble() * (16 * (edge + 1) - Flight.REACH - 0.01);
            Flight.Step s = f.advance(x, 8, dt, readyBelow(edge));
            assertTrue(Math.floorDiv((long) Math.floor(s.x() + Flight.REACH), 16) <= edge, "碰撞箱进了没就绪的 chunk：" + s.x());
            assertTrue(s.x() >= x - 1e-9 && s.x() <= x + speed * dt + 1e-9);
        }
    }

    @Test
    void diagonalFlightChecksEveryChunkOnTheWay() {
        // 斜着飞：x、z 都会跨边界；z 方向的下一个 chunk 没就绪也要停
        Flight f = new Flight(0, 0, 200, 1, 1, 20, 120, Flight.Mode.WALL);
        Flight.Step s = f.advance(10, 10, 1.0, (cx, cz) -> cz <= 0);
        assertTrue(s.stalled() && s.z() < 16, "z=" + s.z());
    }
}
