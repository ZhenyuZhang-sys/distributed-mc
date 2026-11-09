package mcdonate.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import mcdonate.client.FrameThrottle.Action;
import org.junit.jupiter.api.Test;

/**
 * 第 9 步（docs/step9-plan.md 第 4 节）：按帧时间暂停、恢复 worker。
 * 11-09 改（调度：用户真机上鞘翅进新区域，客户端加载 chunk 必然掉帧，donor 正好在最需要的时候停下）：
 * 看最近 10 秒帧时间的中位数，超过目标（50 ms）持续 5 秒暂停，低于目标的 0.8（40 ms）持续 5 秒恢复。
 * 加载 chunk 那几秒的掉帧只是一部分帧慢，中位数不动；整体都慢（一半以上的帧比目标慢）才暂停。
 */
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
    void sustainedSlowFramesPause() {
        run(16, 10);
        long slowFrom = now;
        run(80, 30);
        assertEquals(1, actions.size(), actions.toString());
        assertTrue(actions.get(0).startsWith("PAUSE"));
        long at = Long.parseLong(actions.get(0).split("@")[1]);
        // 中位数要等慢帧占到窗口里一半以上的帧（80 ms 一帧、16 ms 一帧，约 8 秒），再持续 5 秒
        assertTrue(at - slowFrom >= 8000 && at - slowFrom <= 15000, "暂停在 " + (at - slowFrom) + " ms");
        assertTrue(t.paused());
    }

    @Test
    void loadingDipsDoNotPause() {
        // 鞘翅进新区域：几秒里帧时间 60～100 ms（客户端加载 chunk），然后恢复。donor 这时候最有用，不该停
        run(16, 10);
        run(100, 3);
        run(16, 8);
        run(60, 4);
        run(16, 8);
        run(100, 3);
        run(16, 10);
        assertTrue(actions.isEmpty(), actions.toString());
    }

    @Test
    void resumesAfterFiveGoodSecondsNotBefore() {
        run(80, 20);
        assertTrue(t.paused());
        actions.clear();
        long goodFrom = now;
        run(16, 30);
        assertEquals(1, actions.size(), actions.toString());
        long at = Long.parseLong(actions.get(0).split("@")[1]);
        // 快帧数一多中位数就降下来（约 2 秒），再持续 5 秒
        assertTrue(at - goodFrom >= 5000 && at - goodFrom <= 12000, "恢复在 " + (at - goodFrom) + " ms");
        assertTrue(actions.get(0).startsWith("RESUME"));
        assertFalse(t.paused());
    }

    @Test
    void inBetweenKeepsCurrentState() {
        // 45 ms：不超过 50（不暂停），也不低于 40（暂停了也不恢复）
        run(45, 20);
        assertFalse(t.paused());
        run(80, 20);
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
    void medianOverWindow() {
        for (int i = 1; i <= 101; i++) {
            t.frame(i * 10L, i);  // 1..101 ms，全在 10 秒窗口里
        }
        assertEquals(51.0, t.median(1010), 1e-9);
    }

    @Test
    void p99OverWindow() {
        for (int i = 1; i <= 100; i++) {
            t.frame(i * 10L, i);  // 1..100 ms，全在 5 秒窗口里
        }
        assertEquals(99.0, t.p99(1000), 1e-9);
    }

    @Test
    void raisingTargetLetsAStuckWorkerResume() {
        // 集显、开着光影：平时就 60 ms，目标 50 一直暂停。玩家在设置里把目标调到 100（恢复线 80）：不重启 worker，5 秒后恢复
        run(60, 10);
        assertTrue(t.paused());
        actions.clear();
        t.setTargetMs(100);
        run(60, 10);
        assertEquals(1, actions.size(), actions.toString());
        assertTrue(actions.get(0).startsWith("RESUME"));
        assertEquals(100, t.targetMs());
    }
}
