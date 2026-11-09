package mcdonate.bench.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 把逐 tick 的视野统计汇总成每秒一行（docs/step4-plan.md 2.2）。 */
class SecondRowsTest {
    static final long MS = 1_000_000L;

    final List<SecondRows.Row> rows = new ArrayList<>();

    SecondRows rows(int cap) {
        return new SecondRows(cap, rows::add);
    }

    static ViewTracker.Snapshot snap(int view, int notReady) {
        return new ViewTracker.Snapshot(view, notReady);
    }

    @Test
    void tickBelongsToTheSecondItEndsIn() {
        SecondRows r = rows(3);
        // 20 TPS，每 tick 50 ms，耗时 10 ms；第 1 秒的最后一个 tick 正好结束在 1000 ms
        for (int i = 1; i <= 60; i++) r.tick(i * 50 * MS, 10 * MS, i * 0.215, 7, snap(453, i <= 20 ? 100 : 0));
        r.finish();
        assertEquals(3, rows.size());
        assertEquals(List.of(1, 2, 3), rows.stream().map(SecondRows.Row::tS).toList());
        SecondRows.Row first = rows.get(0);
        assertEquals(20, first.ticks());
        assertEquals(100.0 / 453, first.voidRate(), 1e-12);
        assertEquals(100.0 / 453, first.voidRateMax(), 1e-12);
        assertEquals(10.0, first.msptMean(), 1e-12);
        assertEquals(4.3, first.x(), 1e-9);  // 这一秒最后一个 tick 结束时的位置
        assertEquals(0.0, rows.get(1).voidRate());
        assertEquals(453, rows.get(2).view());
    }

    @Test
    void voidRateIsMeanOfTicksAndMaxIsWorstTick() {
        SecondRows r = rows(1);
        r.tick(250 * MS, 20 * MS, 0, 0, snap(400, 40));
        r.tick(500 * MS, 40 * MS, 0, 0, snap(400, 0));
        r.tick(1000 * MS, 30 * MS, 0, 0, snap(400, 20));
        r.finish();
        SecondRows.Row row = rows.get(0);
        assertEquals((0.1 + 0 + 0.05) / 3, row.voidRate(), 1e-12);
        assertEquals(0.1, row.voidRateMax(), 1e-12);
        assertEquals(30.0, row.msptMean(), 1e-12);
        assertEquals(40.0, row.msptMax(), 1e-12);
        assertEquals(3, row.ticks());
    }

    @Test
    void longTickLeavesEmptySecondsFilledFromLastState() {
        // 1 核时一个 tick 可能超过 1 秒：中间没 tick 结束的秒也要有一行，t_s 连续（Python 端要求）。
        SecondRows r = rows(4);
        r.tick(500 * MS, 50 * MS, 1, 0, snap(453, 200));
        r.tick(3200 * MS, 2700 * MS, 2, 0, snap(453, 50));  // 卡了 2.7 秒，结束在第 4 秒
        r.finish();
        assertEquals(List.of(1, 2, 3, 4), rows.stream().map(SecondRows.Row::tS).toList());
        SecondRows.Row gap = rows.get(1);
        assertEquals(0, gap.ticks());
        assertEquals(200.0 / 453, gap.voidRate(), 1e-12, "卡住期间视野状态停在上一个 tick");
        assertEquals(2700.0, gap.msptMax(), 1e-12, "卡住期间的 MSPT 记那个长 tick");
        assertEquals(1, rows.get(3).ticks());
        assertEquals(50.0 / 453, rows.get(3).voidRate(), 1e-12);
    }

    @Test
    void capClampsTheFinishingTickIntoTheLastSecond() {
        // 墙钟模式飞满 2 秒：结束那个 tick 落在 2.03 秒，也算第 2 秒，正好 2 行。
        SecondRows r = rows(2);
        r.tick(1000 * MS, 5 * MS, 0, 0, snap(10, 0));
        r.tick(2030 * MS, 5 * MS, 0, 0, snap(10, 0));
        r.finish();
        assertEquals(2, rows.size());
        assertEquals(1, rows.get(1).ticks());
    }

    @Test
    void finishNeverPadsSecondsWithoutTicks() {
        // 没有上限（tick 模式），或中途 stop：只写到最后一个有 tick 结束的秒，不往后补。
        SecondRows r = rows(0);
        r.tick(400 * MS, 5 * MS, 0, 0, snap(10, 1));
        r.tick(1400 * MS, 5 * MS, 0, 0, snap(10, 0));
        r.finish();
        assertEquals(2, rows.size());
    }

    @Test
    void jsonHasPythonColumns() {
        SecondRows r = rows(1);
        r.tick(1000 * MS, 12 * MS, 1.5, -2.25, snap(453, 3));
        r.finish();
        String json = rows.get(0).json();
        for (String key : List.of("\"t_s\":1", "\"x\":1.5", "\"z\":-2.25", "\"view_chunks\":453", "\"void_rate\":", "\"void_rate_max\":",
                "\"mspt_mean\":12.0", "\"mspt_max\":12.0", "\"ticks\":1"))
            assertTrue(json.contains(key), key + " 不在 " + json);
    }

    @Test
    void rejectsTimeGoingBackwardsAndUseAfterFinish() {
        SecondRows r = rows(3);
        r.tick(500 * MS, 1, 0, 0, snap(1, 0));
        assertThrows(IllegalArgumentException.class, () -> r.tick(400 * MS, 1, 0, 0, snap(1, 0)));
        r.finish();
        assertThrows(IllegalStateException.class, () -> r.tick(900 * MS, 1, 0, 0, snap(1, 0)));
    }
}
