package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** codex-audit 第二十节（审 fee5c7f）：退了以后单独量一次的三个问题。 */
class DonorCalibrationTest {
    /** 做出 4 lanes，再让两个一起做的慢样本触发回退；返回 now。 */
    private static long backedOff(DonorThroughput t, LoadMeter m) {
        long now = 10_000;
        for (int k = 0; k < 6; k++) {
            for (int i = 0; i < 4; i++) {
                m.start(now);
            }
            now += 700;
            for (int i = 0; i < 4; i++) {
                long[] r = m.read(now);
                t.record(1, 700, now, r[0], r[1], i + 1);
                m.end(now);
            }
        }
        for (int i = 0; i < 2; i++) {
            m.start(now);
            now += 1_400;
            long[] r = m.read(now);
            t.record(1, 1_400, now, r[0], r[1], 4);
            m.end(now);
        }
        assertTrue(t.calibrating(1));
        return now;
    }

    @Test
    void calibrationExpiresWithoutAnyResult() {
        // Z1：退了以后一直没有前方结果回来（只有后台、没人飞）：10 秒到了也要结束量，不能一直只派 1 个
        DonorThroughput t = new DonorThroughput();
        long now = backedOff(t, new LoadMeter());
        t.expireCalibration(1, now + 5_000);
        assertTrue(t.calibrating(1), "还没到 10 秒");
        t.expireCalibration(1, now + DonorThroughput.CALIBRATE_MS + 1);
        assertTrue(!t.calibrating(1), "到了就不量了");
        assertTrue(t.inFlightCap(1, 8) > 1);
    }

    @Test
    void oldSoloTaskIsNotTheMeasurement() {
        // Z2：回退之前就派出去的一个 k=1 任务，回退之后才交回：它不是"退了以后单独做的那个"，不能拿来判断
        DonorThroughput t = new DonorThroughput();
        LoadMeter m = new LoadMeter();
        long now = backedOff(t, m);
        int lanes = t.lanes(1);
        long[] r = m.read(now);
        t.record(1, 3_000, now + 500, r[0], r[1], 1);   // 派出时间 now - 2500，在回退之前
        assertTrue(t.calibrating(1), "旧任务不算");
        assertEquals(lanes, t.lanes(1));
    }

    @Test
    void recalibrationThenClimbsFromCurrentLanes() {
        // Z3：单独做也慢（整台变慢）：基线换新、冷却取消，从现在的 lanes 往上探，几轮内回到 4
        DonorThroughput t = new DonorThroughput();
        LoadMeter m = new LoadMeter();
        long now = backedOff(t, m);
        m.start(now);
        now += 1_400;
        long[] r = m.read(now);
        t.record(1, 1_400, now, r[0], r[1], 1);
        m.end(now);
        assertTrue(!t.calibrating(1));
        assertTrue(t.lanes(1) < 4, "不直接跳回 4");
        for (int round = 0; round < 12 && t.lanes(1) < 4; round++) {
            int n = t.lanes(1);
            for (int i = 0; i < n; i++) {
                m.start(now);
            }
            now += 1_400;
            for (int i = 0; i < n; i++) {
                r = m.read(now);
                t.record(1, 1_400, now, r[0], r[1], n);
                m.end(now);
            }
        }
        assertEquals(4, t.lanes(1), "按新的基线往上探回 4");
    }
}
