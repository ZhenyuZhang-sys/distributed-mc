package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/** 11-09（codex-audit 第十五节）：一个 worker 的在途数随时间的记录：手上有活的时间、在途数 × 时间。 */
class LoadMeterTest {
    @Test
    void integratesWhileBusy() {
        LoadMeter m = new LoadMeter();
        assertArrayEquals(new long[] {0, 0}, m.read(1_000));
        m.start(1_000);
        m.start(1_500);
        m.end(2_000);           // 1000～1500 一个，1500～2000 两个
        assertArrayEquals(new long[] {1_000, 1_500}, m.read(2_000));
        m.end(2_500);           // 2000～2500 一个
        assertArrayEquals(new long[] {1_500, 2_000}, m.read(9_000), "空着的时间不算");
        m.start(10_000);
        assertArrayEquals(new long[] {2_500, 3_000}, m.read(11_000));
    }

    @Test
    void neverNegative() {
        LoadMeter m = new LoadMeter();
        m.end(1_000);           // 多记了一次结束（防御）：不变成负数
        m.start(2_000);
        assertArrayEquals(new long[] {1_000, 1_000}, m.read(3_000));
    }
}
