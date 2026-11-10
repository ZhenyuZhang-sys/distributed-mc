package mcdonate.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 控制通道 STATUS 的 JSON（kexu overlay 计划第 6 节定的字段名，客户端每秒取一次）。 */
class WorkerStatusTest {
    @Test
    void allFields() {
        WorkerStatus s = new WorkerStatus();
        s.tileDone(36, 120_000);
        s.tileDone(36, 80_000);
        s.sent(5_000);
        String json = s.json(WorkerStatus.State.RUNNING, 7, true, false,
                new WorkerStatus.Load(3, 0.25, 4, 512, 2048));
        assertEquals("{\"paused\":false,\"task\":7,\"connected\":true,\"needToken\":false,"
                + "\"state\":\"running\",\"threads\":3,\"cpuLoad\":0.25,\"cpus\":4,\"heapUsedMb\":512,\"heapMaxMb\":2048,"
                + "\"tiles\":2,\"chunks\":72,\"bytes\":205000}", json);
    }

    @Test
    void pausedAndStopping() {
        WorkerStatus s = new WorkerStatus();
        WorkerStatus.Load l = new WorkerStatus.Load(1, 0, 1, 1, 1);
        assertTrue(s.json(WorkerStatus.State.PAUSED, -1, true, false, l).contains("\"paused\":true,"));
        assertTrue(s.json(WorkerStatus.State.PAUSED, -1, true, false, l).contains("\"state\":\"paused\""));
        assertTrue(s.json(WorkerStatus.State.STOPPING, -1, false, false, l).contains("\"state\":\"stopping\""));
    }

    @Test
    void unknownCpuLoadIsMinusOne() {
        // getProcessCpuLoad 刚启动时可能返回负数或 NaN：统一报 -1，客户端显示"未知"
        assertEquals(-1, new WorkerStatus.Load(1, Double.NaN, 1, 1, 1).cpuLoad());
        assertEquals(-1, new WorkerStatus.Load(1, -0.5, 1, 1, 1).cpuLoad());
    }

    @Test
    void currentLoadIsSane() {
        WorkerStatus.Load l = WorkerStatus.Load.current();
        assertTrue(l.cpus() >= 1 && l.threads() >= 1 && l.heapMaxMb() >= l.heapUsedMb() && l.heapUsedMb() >= 0, l.toString());
        assertTrue(l.cpuLoad() == -1 || (l.cpuLoad() >= 0 && l.cpuLoad() <= 1), l.toString());
    }
}
