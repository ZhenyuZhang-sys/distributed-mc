package mcdonate.bench;

/**
 * 最近几个 tick 的 MSPT（演示面板，docs/step10-plan.md 7.1）。
 *
 * <p>原版的 {@code getAverageTickTimeNanos()} 是最近 100 个 tick 的平均，按秒看太平滑；这里只取最近 20 个（约 1 秒）。
 * 不读原版的 {@code tickTimesNanos}：它在 tickServer 最后才写，命令在 tick 里执行时（玩家、GameTest）会读到
 * 100 个 tick 以前的值。这里自己在 START_SERVER_TICK / END_SERVER_TICK 之间计时，只记做完的 tick；
 * 比原版的计时少了 tick 前后的一点收尾（自动存档等），口径写在 /bench tick 的说明里。
 */
public final class TickTimes {
    public static final int RECENT = 20;
    private static final Ring RING = new Ring();

    public record Summary(long completed, int ticks, double meanMs, double maxMs) {}

    /** 环形记录最近 100 个做完的 tick 的耗时。只在主线程上用。 */
    public static final class Ring {
        private final long[] nanos = new long[100];
        private long completed;
        private long startedAt = -1;

        public void start(long now) {
            startedAt = now;
        }

        public void end(long now) {
            if (startedAt < 0) {
                return;
            }
            nanos[(int) (completed % nanos.length)] = now - startedAt;
            completed++;
            startedAt = -1;
        }

        public Summary recent(int n) {
            int k = (int) Math.min(n, Math.min(completed, nanos.length));
            long sum = 0, max = 0;
            for (int i = 1; i <= k; i++) {
                long t = nanos[(int) ((completed - i) % nanos.length)];
                sum += t;
                max = Math.max(max, t);
            }
            return new Summary(completed, k, k == 0 ? 0 : sum / 1e6 / k, max / 1e6);
        }
    }

    private TickTimes() {}

    public static void onTickStart() {
        RING.start(System.nanoTime());
    }

    public static void onTickEnd() {
        RING.end(System.nanoTime());
    }

    public static Summary recent() {
        return RING.recent(RECENT);
    }

    /** /bench tick 的一行 JSON：做完的 tick 数、最近几个 tick 的 MSPT 均值和最大值（毫秒）、原版的 100 tick 平均。 */
    public static String json(Summary s, long average100Nanos) {
        return "{\"tick\":" + s.completed() + ",\"recent_ticks\":" + s.ticks() + ",\"mspt_recent_mean\":" + s.meanMs()
                + ",\"mspt_recent_max\":" + s.maxMs() + ",\"mspt_100\":" + average100Nanos / 1e6 + "}";
    }
}
