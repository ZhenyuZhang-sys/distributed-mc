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

    @Test
    void turnCoversBothOldAndNewDirection() {
        // 11-09（kexu 第二张表：转弯时 donor 没帮上）：沿 +x 飞 3 秒，然后转向 +z 飞 0.75 秒。
        // 短窗口（0.75 秒）看到的是新方向，长窗口（3 秒）还偏旧方向；截止时间、离飞行线距离取两者里小的（并集）
        Motion m = new Motion();
        for (long t = 0; t <= 3_000; t += 250) {
            m.record(t, 8 * t / 250.0, 0);                    // 沿 +x，32 格/秒，到 t=3000 时在 x=96
        }
        for (long t = 3_250; t <= 3_750; t += 250) {
            m.record(t, 96, 8 * (t - 3_000) / 250.0);         // 转向 +z
        }
        mcdonate.common.TilePos aheadNew = new mcdonate.common.TilePos(1, 6);   // chunk x 4～7（玩家在 6）、z 24～27：新方向（+z）前方
        mcdonate.common.TilePos aheadOld = new mcdonate.common.TilePos(7, 0);   // chunk x 28～31：旧方向（+x）前方
        double[] vs = m.velocity(Motion.SHORT_WINDOW_MS), vl = m.velocity(Motion.LONG_WINDOW_MS);
        assertTrue(vs[1] > 1.5 * Math.abs(vs[0]), "短窗口：主要是新方向 " + vs[0] + "," + vs[1]);
        assertTrue(vl[0] > vl[1], "长窗口：还偏旧方向");
        assertTrue(Double.isFinite(m.deadlineSecondsUnion(aheadNew, 10)), "新方向前方的 tile 有截止时间");
        assertTrue(Double.isFinite(m.deadlineSecondsUnion(aheadOld, 10)), "转弯刚开始，旧方向前方的也还算");
        assertTrue(m.lateralChunksUnion(aheadNew) <= 6, "新方向前方的在走廊里");
    }

    @Test
    void shortWindowWorksWithSparseSamples() {
        // codex-audit（3132230）：找需求约每秒记一次位置，0.75 秒的短窗口里只有最新一个样本，速度成了 0，并集形同虚设。
        // 窗口里只有一个样本时用前一个样本
        Motion m = new Motion();
        m.record(0, 0, 0);
        m.record(1_000, 32, 0);
        double[] v = m.velocity(Motion.SHORT_WINDOW_MS);
        assertEquals(32, v[0], 1e-9);
    }
}
