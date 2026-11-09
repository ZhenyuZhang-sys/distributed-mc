package mcdonate.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 演示面板（第 10 步 7.1）：最近 20 个 tick 的 MSPT。
 * 自己在 START_SERVER_TICK / END_SERVER_TICK 计时，只算做完的 tick：命令不论在 tick 里（玩家、GameTest）
 * 还是在两个 tick 之间（RCON）执行，读到的都是最近做完的那些。原版的 tickTimesNanos 在 tickServer 最后才写，
 * tick 里读会读到 100 个 tick 以前的值。
 */
class TickTimesTest {
    static TickTimes.Ring ring(long... durationsOldestFirst) {
        TickTimes.Ring r = new TickTimes.Ring();
        long t = 1_000_000_000L;
        for (long d : durationsOldestFirst) {
            r.start(t);
            r.end(t + d);
            t += d + 50_000_000L;
        }
        return r;
    }

    @Test
    void lastTwentyTicksMeanAndMax() {
        long[] d = new long[25];
        for (int i = 0; i < 25; i++) d[i] = (25 - i) * 1_000_000L; // 最早 25 ms……最新 1 ms
        TickTimes.Summary s = ring(d).recent(20);
        assertEquals(25, s.completed());
        assertEquals(20, s.ticks());
        assertEquals(10.5, s.meanMs(), 1e-9);  // 最新的 1～20 ms
        assertEquals(20.0, s.maxMs(), 1e-9);   // 更早的 21～25 ms 不算
    }

    @Test
    void wrapsAroundTheRing() {
        long[] d = new long[205];
        java.util.Arrays.fill(d, 2_000_000L);
        d[204] = 50_000_000L;
        TickTimes.Summary s = ring(d).recent(20);
        assertEquals(50.0, s.maxMs(), 1e-9);
        assertEquals((50.0 + 19 * 2.0) / 20, s.meanMs(), 1e-9);
    }

    @Test
    void tickInProgressIsNotCounted() {
        TickTimes.Ring r = ring(4_000_000L, 2_000_000L, 6_000_000L);
        r.start(9_000_000_000L);  // 这个 tick 还没做完（命令就在它里面执行）
        TickTimes.Summary s = r.recent(20);
        assertEquals(3, s.completed());
        assertEquals(3, s.ticks());
        assertEquals(4.0, s.meanMs(), 1e-9);
        assertEquals(0, new TickTimes.Ring().recent(20).ticks());
    }

    @Test
    void endWithoutStartIgnored() {
        TickTimes.Ring r = new TickTimes.Ring();
        r.end(5);  // mod 在 tick 中间才注册上
        assertEquals(0, r.recent(20).completed());
    }

    @Test
    void jsonHasAgreedFields() {
        String j = TickTimes.json(ring(3_000_000L).recent(20), 7_500_000L);
        assertEquals("{\"tick\":1,\"recent_ticks\":1,\"mspt_recent_mean\":3.0,\"mspt_recent_max\":3.0,\"mspt_100\":7.5}", j);
    }
}
