package mcdonate.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import mcdonate.client.FrameThrottle.Action;
import org.junit.jupiter.api.Test;

/** 第 9 步（docs/step9-plan.md 第 4 节）：按帧时间暂停、恢复 worker。目标 50 ms：p99 超过持续 2 秒暂停，低于 40 ms 持续 5 秒恢复。 */
class FrameThrottleTest {
    private final FrameThrottle t = new FrameThrottle(50);
    private long now = 0;
    private final List<String> actions = new ArrayList<>();

    /** 以每帧 ms 毫秒的速度跑 seconds 秒，记下发出的动作和时间。 */
    private void run(double ms, double seconds) {
        long end = now + (long) (seconds * 1000);
        while (now < end) {
            now += (long) Math.max(1, ms);
            Action a = t.frame(now, ms);
            if (a != Action.NONE) {
                actions.add(a + "@" + now);
            }
        }
    }

    @Test
    void smoothFramesNeverPause() {
        run(16, 30);
        assertTrue(actions.isEmpty(), actions.toString());
        assertFalse(t.paused());
    }

    @Test
    void slowFramesPauseAfterTwoSeconds() {
        run(16, 10);
        long slowFrom = now;
        run(80, 10);
        assertEquals(1, actions.size(), actions.toString());
        assertTrue(actions.get(0).startsWith("PAUSE"));
        long at = Long.parseLong(actions.get(0).split("@")[1]);
        // p99 要等慢帧占到窗口的 1% 以上才超（很快），再持续 2 秒
        assertTrue(at - slowFrom >= 2000 && at - slowFrom <= 3000, "暂停在 " + (at - slowFrom) + " ms");
        assertTrue(t.paused());
    }

    @Test
    void resumesAfterFiveGoodSecondsNotBefore() {
        run(80, 5);
        assertTrue(t.paused());
        actions.clear();
        long goodFrom = now;
        run(16, 20);
        assertEquals(1, actions.size(), actions.toString());
        long at = Long.parseLong(actions.get(0).split("@")[1]);
        // 慢帧要先滑出 5 秒的窗口，p99 才降下来，再持续 5 秒
        assertTrue(at - goodFrom >= 5000 && at - goodFrom <= 11000, "恢复在 " + (at - goodFrom) + " ms");
        assertTrue(actions.get(0).startsWith("RESUME"));
        assertFalse(t.paused());
    }

    @Test
    void inBetweenKeepsCurrentState() {
        // 45 ms：不超过 50（不暂停），也不低于 40（暂停了也不恢复）
        run(45, 20);
        assertFalse(t.paused());
        run(80, 5);
        assertTrue(t.paused());
        actions.clear();
        run(45, 30);
        assertTrue(actions.isEmpty(), actions.toString());
        assertTrue(t.paused());
    }

    @Test
    void briefSpikeDoesNotPause() {
        run(16, 10);
        run(200, 0.4); // 两帧卡顿：不到窗口里帧数的 1%，p99 不动（卡满 1 秒的话 p99 会高 5 秒，照规则会暂停）
        run(16, 10);
        assertTrue(actions.stream().noneMatch(a -> a.startsWith("PAUSE")), actions.toString());
    }

    @Test
    void p99OverWindow() {
        for (int i = 1; i <= 100; i++) {
            t.frame(i * 10L, i);  // 1..100 ms，全在 5 秒窗口里
        }
        assertEquals(99.0, t.p99(1000), 1e-9);
    }
}
