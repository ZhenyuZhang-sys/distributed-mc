package mcdonate.bench.flight;

/**
 * 一次直线飞行：给定起飞后的时间，算玩家该在哪。纯逻辑。
 * 按墙钟（WALL）还是按 tick（TICK）由协议决定，默认墙钟，理由见 docs/step4-plan.md 2.4。
 */
public record Flight(double startX, double startZ, double y, double dirX, double dirZ,
        double blocksPerSecond, double seconds, Mode mode) {

    public enum Mode { WALL, TICK }

    public Flight {
        double len = Math.hypot(dirX, dirZ);
        if (len == 0 || !(blocksPerSecond > 0) || !(seconds > 0)) {
            throw new IllegalArgumentException("方向、速度、时长必须有效");
        }
        dirX /= len;
        dirZ /= len;
    }

    /** 沿 +x 飞。 */
    public static Flight eastward(double x, double z, double y, double speed, double seconds, Mode mode) {
        return new Flight(x, z, y, 1, 0, speed, seconds, mode);
    }

    /** 起飞后经过的秒数：墙钟模式用 nanos，tick 模式用 ticks / 20。 */
    public double elapsed(long nanosSinceStart, long ticksSinceStart) {
        double t = mode == Mode.WALL ? nanosSinceStart / 1e9 : ticksSinceStart / 20.0;
        return Math.max(0, Math.min(seconds, t));
    }

    public double x(double elapsed) {
        return startX + dirX * blocksPerSecond * elapsed;
    }

    public double z(double elapsed) {
        return startZ + dirZ * blocksPerSecond * elapsed;
    }

    public boolean finished(double elapsed) {
        return elapsed >= seconds;
    }

    // ---- 像真实客户端一样前进（11-09） ----
    // 客户端 LocalPlayer.tick 先查 Level.hasChunkAt：自己所在的 chunk 没收到就不动。所以真实玩家不会走进服务器还没发的 chunk，
    // 实体 tick 查脚下流体时也就不会在主线程上同步生成。假玩家要照这个走，否则会把服务器卡死（fish 6.6 看门狗）。

    /** 这个 chunk 能不能走进去（就绪 = 原版会发给客户端）。 */
    @FunctionalInterface
    public interface Ready { boolean ready(int cx, int cz); }

    /** 一步的结果：新位置；stalled = 被没就绪的 chunk 挡住了；moved = 实际走了几格。 */
    public record Step(double x, double z, boolean stalled, double moved) {}

    private static final double EDGE = 1e-6;   // 停在边界前面这么远
    private static final double PAST = 1e-7;   // 过边界时多走这么一点，确定进了哪个 chunk

    /**
     * 从 (x, z) 沿航线走 速度 × dt 格，但不走进没就绪的 chunk：挡住就停在边界前面。
     * dt 很大（服务器卡了）也一样：客户端按自己的帧率走，只能穿过已就绪的 chunk。
     */
    public Step advance(double x, double z, double dt, Ready ready) {
        double remaining = blocksPerSecond * Math.max(0, dt), moved = 0;
        while (remaining > 0) {
            double t = Math.min(toBoundary(x, dirX), toBoundary(z, dirZ));
            if (t >= remaining) {
                return new Step(x + dirX * remaining, z + dirZ * remaining, false, moved + remaining);
            }
            double px = x + dirX * (t + PAST), pz = z + dirZ * (t + PAST);
            if (!ready.ready(chunk(px), chunk(pz))) {
                double stop = Math.max(0, t - EDGE);
                return new Step(x + dirX * stop, z + dirZ * stop, true, moved + stop);
            }
            x = px;
            z = pz;
            moved += t + PAST;
            remaining -= t + PAST;
        }
        return new Step(x, z, false, moved);
    }

    private static int chunk(double v) {
        return (int) Math.floorDiv((long) Math.floor(v), 16L);
    }

    /** 沿一个坐标轴，到下一个 chunk 边界还要走多远（换算成沿航线的距离）。 */
    private static double toBoundary(double v, double dir) {
        if (dir == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double base = Math.floor(v / 16) * 16;
        return dir > 0 ? (base + 16 - v) / dir : (v - base) / -dir;
    }
}
