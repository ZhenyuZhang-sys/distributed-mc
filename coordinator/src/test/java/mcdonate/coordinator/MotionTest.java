package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mcdonate.common.TilePos;
import org.junit.jupiter.api.Test;

/** 第 7 步 7.2：运动估计和 deadline。 */
class MotionTest {
    @Test
    void velocityFromSamples() {
        Motion m = new Motion();
        m.record(0, 0, 0);
        m.record(1000, 30, 0); // 鞘翅 30 格/秒，沿 +x
        double[] v = m.velocity();
        assertEquals(30, v[0], 1e-9);
        assertEquals(0, v[1], 1e-9);
    }

    @Test
    void oldSamplesDropped() {
        Motion m = new Motion();
        m.record(0, 0, 0);
        m.record(1000, 100, 0);
        m.record(5000, 100, 0); // 停下来了，3 秒前的样本丢掉
        m.record(6000, 100, 0);
        assertEquals(0, m.velocity()[0], 1e-9);
    }

    @Test
    void insideViewIsZero() {
        Motion m = new Motion();
        m.record(0, 8, 8);
        assertEquals(0, m.deadlineSeconds(new TilePos(1, 0), 10)); // tile 在 chunk 4..7，视距 10 内
    }

    @Test
    void aheadArrivesInTime() {
        // 玩家在 chunk 0、沿 +x 以 32 格/秒（2 chunk/秒）走；tile (8,0) 从 chunk 32 开始，
        // 进入视距要到 chunk 22 → 22 个 chunk = 11 秒
        Motion m = new Motion();
        m.record(0, 0, 8);
        m.record(1000, 32, 8);
        double t = m.deadlineSeconds(new TilePos(8, 0), 10);
        assertTrue(t > 9.5 && t < 10.5, Double.toString(t)); // 现在已在 chunk 2，还差 20 个 chunk
    }

    @Test
    void behindNeverArrives() {
        Motion m = new Motion();
        m.record(0, 0, 8);
        m.record(1000, 32, 8);
        assertEquals(Double.POSITIVE_INFINITY, m.deadlineSeconds(new TilePos(-10, 0), 10));
    }

    @Test
    void standingStillIsInfinity() {
        Motion m = new Motion();
        m.record(0, 0, 0);
        m.record(1000, 0, 0);
        assertEquals(Double.POSITIVE_INFINITY, m.deadlineSeconds(new TilePos(8, 0), 10));
    }

    @Test
    void fastMatchesStepwise() {
        // 解析解和逐步模拟（步长 0.25 秒）比：随机位置、速度、tile，差不超过一步
        java.util.Random rng = new java.util.Random(424242);
        for (int k = 0; k < 2000; k++) {
            Motion m = new Motion();
            double x = rng.nextInt(4000) - 2000, z = rng.nextInt(4000) - 2000;
            double vx = rng.nextInt(61) - 30, vz = rng.nextInt(61) - 30;
            m.record(0, x, z);
            m.record(1000, x + vx, z + vz);
            mcdonate.common.TilePos tile = new mcdonate.common.TilePos(rng.nextInt(200) - 100, rng.nextInt(200) - 100);
            double slow = m.deadlineSeconds(tile, 10), fast = m.deadlineSecondsFast(tile, 10);
            if (Double.isInfinite(slow)) {
                // 逐步模拟只看到 HORIZON_S；解析解在边上可能刚好进得去
                org.junit.jupiter.api.Assertions.assertTrue(Double.isInfinite(fast) || fast > Motion.HORIZON_S - 0.25,
                        k + ": " + slow + " vs " + fast);
            } else {
                org.junit.jupiter.api.Assertions.assertEquals(slow, fast, 0.26, k + ": tile " + tile);
            }
        }
    }

    @Test
    void lateralDistanceFromFlightLine() {
        // 玩家在原点沿 +x 飞：tile (8, 2) 的中心在 chunk (33.5, 9.5)，离飞行线 9.5 个 chunk；正前方的 tile (8, 0) 中心离线 1.5
        assertEquals(9.5, Motion.lateralChunks(new mcdonate.common.TilePos(8, 2), 0, 0, 30, 0), 1e-9);
        assertEquals(1.5, Motion.lateralChunks(new mcdonate.common.TilePos(8, 0), 0, 0, 30, 0), 1e-9);
        assertEquals(Double.POSITIVE_INFINITY, Motion.lateralChunks(new mcdonate.common.TilePos(8, 0), 0, 0, 0, 0), "不动：没有飞行线");
    }
}
