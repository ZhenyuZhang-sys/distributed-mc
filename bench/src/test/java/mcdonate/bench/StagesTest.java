package mcdonate.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mcdonate.bench.Stages.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StagesTest {
    @AfterEach
    void cleanUp() {
        Stages.setEnabled(false);
        Stages.reset();
    }

    @Test
    void offRecordsNothing() {
        Stages.reset();
        assertEquals(42, Stages.time(Stage.NOISE, true, () -> 42));
        assertEquals(0, Stage.NOISE.count.sum());
        assertEquals(0, Stage.NOISE.wallNs.sum());
    }

    @Test
    void onRecordsCountAndTime() {
        Stages.reset();
        Stages.setEnabled(true);
        Stages.time(Stage.NOISE, true, StagesTest::burn);
        Stages.time(Stage.NOISE, false, StagesTest::burn); // 后台部分：只加时间
        assertEquals(1, Stage.NOISE.count.sum());
        assertTrue(Stage.NOISE.cpuNs.sum() > 0);
        assertTrue(Stage.NOISE.maxWallNs.get() > 0);
        assertTrue(Stage.NOISE.maxWallNs.get() <= Stage.NOISE.wallNs.sum());
    }

    @Test
    void exceptionStillRecorded() {
        Stages.reset();
        Stages.setEnabled(true);
        try {
            Stages.time(Stage.FULL, true, () -> {
                throw new IllegalStateException();
            });
        } catch (IllegalStateException expected) {
            // 计时要照常记上
        }
        assertEquals(1, Stage.FULL.count.sum());
    }

    @Test
    void jsonListsAllStagesInOrder() {
        Stages.reset();
        String json = Stages.json();
        assertTrue(json.startsWith("{\"timing\":false,\"stages\":{\"structure_starts\":{\"count\":0,"), json);
        assertTrue(json.contains("\"full\":{"), json);
        assertTrue(json.endsWith("\"light_engine\":{\"count\":0,\"cpu_ns\":0,\"wall_ns\":0,\"max_wall_ns\":0}}}"), json);
    }

    private static long burn() {
        long x = 0;
        for (int i = 0; i < 2_000_000; i++) {
            x += i * 31L;
        }
        return x;
    }
}
