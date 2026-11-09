package mcdonate.bench.flight;

import java.util.function.Consumer;

/**
 * 逐 tick 的视野统计 → 每秒一行（docs/step4-plan.md 2.2）。纯逻辑。
 *
 * <ul>
 *   <li>一个 tick 算在它结束时刻所在的那一秒：(k−1, k] 秒算第 k 秒。
 *   <li>有上限（墙钟模式飞 N 秒）时，结束那个 tick 稍晚于 N 秒也算第 N 秒，正好 N 行。
 *   <li>一个 tick 超过 1 秒时，中间没有 tick 结束的秒也写一行（ticks = 0）：视野状态停在上一个 tick，
 *       MSPT 记那个长 tick 的耗时。这样 t_s 连续，Python 端不用猜。
 *   <li>结束时只写到最后一个有 tick 的秒，不往后补（中途 stop 就少几行）。
 * </ul>
 */
public final class SecondRows {
    private static final long SECOND = 1_000_000_000L;

    /** 一秒的汇总。位置是这一秒最后一个 tick 时的位置；MSPT 单位毫秒。 */
    public record Row(int tS, double x, double z, int view, double voidRate, double voidRateMax,
            double msptMean, double msptMax, int ticks) {
        public String json() {
            return "{\"t_s\":" + tS + ",\"x\":" + x + ",\"z\":" + z + ",\"view_chunks\":" + view
                    + ",\"void_rate\":" + voidRate + ",\"void_rate_max\":" + voidRateMax
                    + ",\"mspt_mean\":" + msptMean + ",\"mspt_max\":" + msptMax + ",\"ticks\":" + ticks + "}";
        }
    }

    private final int cap;
    private final Consumer<Row> out;
    private int second;
    private int ticks;
    private double voidSum, voidMax, msptSum, msptMax, x, z;
    private int view;
    private ViewTracker.Snapshot last;
    private long lastElapsed = Long.MIN_VALUE;
    private boolean finished;
    private int written;

    /** cap：最多几秒（墙钟模式 = 飞行秒数）；0 = 不设上限（tick 模式）。 */
    public SecondRows(int cap, Consumer<Row> out) {
        if (cap < 0) throw new IllegalArgumentException("cap 不能是负数");
        this.cap = cap;
        this.out = out;
    }

    /** 一个 tick 结束：elapsed 是起飞后到这个 tick 结束的墙钟纳秒，tickNanos 是这个 tick 的耗时。 */
    public void tick(long elapsedNanos, long tickNanos, double x, double z, ViewTracker.Snapshot s) {
        if (finished) throw new IllegalStateException("已经结束");
        if (elapsedNanos < lastElapsed) throw new IllegalArgumentException("时间倒退：" + elapsedNanos + " < " + lastElapsed);
        lastElapsed = elapsedNanos;
        int sec = (int) Math.max(1, (elapsedNanos + SECOND - 1) / SECOND);
        if (cap > 0) sec = Math.min(sec, cap);
        if (sec > second) {
            ViewTracker.Snapshot state = last != null ? last : s;
            double gx = second == 0 ? x : this.x, gz = second == 0 ? z : this.z;
            if (second > 0) flush();
            double longTick = tickNanos / 1e6;
            for (int g = second + 1; g < sec; g++)
                emit(new Row(g, gx, gz, state.view(), state.voidRate(), state.voidRate(), longTick, longTick, 0));
            second = sec;
        }
        double v = s.voidRate(), ms = tickNanos / 1e6;
        ticks++;
        voidSum += v;
        voidMax = Math.max(voidMax, v);
        msptSum += ms;
        msptMax = Math.max(msptMax, ms);
        this.x = x;
        this.z = z;
        view = s.view();
        last = s;
    }

    /** 飞行结束：写出最后一秒。 */
    public void finish() {
        if (finished) return;
        if (ticks > 0) flush();
        finished = true;
    }

    /** 已经写出的行数。 */
    public int written() {
        return written;
    }

    private void flush() {
        emit(new Row(second, x, z, view, voidSum / ticks, voidMax, msptSum / ticks, msptMax, ticks));
        ticks = 0;
        voidSum = voidMax = msptSum = msptMax = 0;
    }

    private void emit(Row r) {
        out.accept(r);
        written++;
    }
}
