package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 一个任务从入队到导入完的分段耗时（调度 11-09：派出到交回 7～9 秒，拆开看时间去哪了）。 */
class TaskTimelineTest {
    @Test
    void segments() {
        // 入队 0、派出 500、worker 收到 520、开始生成 3520（排队 3 秒）、生成完 4720、打包完 4800、
        // coordinator 收到 5000、主线程开始导入 5100、导入完 5130
        long[] worker = {520, 3520, 4720, 4800};
        assertEquals("{\"queue\":500,\"to_worker\":20,\"wait\":3000,\"gen\":1200,\"pack\":80,\"back\":200,"
                + "\"main_wait\":100,\"import\":30}", TaskTimeline.json(0, 500, worker, 5000, 5100, 5130));
    }

    @Test
    void withoutWorkerStamps() {
        // 旧 worker 不带时间点：worker 上的几段不知道（-1），派出到收到合成一段 back
        assertEquals("{\"queue\":500,\"to_worker\":-1,\"wait\":-1,\"gen\":-1,\"pack\":-1,\"back\":4500,"
                + "\"main_wait\":100,\"import\":30}", TaskTimeline.json(0, 500, null, 5000, 5100, 5130));
    }

    @Test
    void unknownEnqueue() {
        // 手动 /donor assign 的、或者重启前入队的：入队时间不知道
        assertEquals(-1, TaskTimeline.parse(TaskTimeline.json(-1, 500, null, 5000, 5100, 5130))[0]);
    }
}
