package mcdonate.coordinator;

import mcdonate.common.Config;
import java.util.ArrayDeque;
import java.util.Deque;
import mcdonate.common.TilePos;

/**
 * 第 7 步 7.2：一个玩家的运动估计和 tile 的 deadline。纯逻辑。
 *
 * <p>记最近 3 秒的位置（方块坐标），用最早和最新两点算速度。deadline = 按直线运动，
 * tile 里最近的 chunk 进入视距（切比雪夫距离 ≤ 视距）的预计秒数；已经在视距里是 0；
 * 不动、或者永远到不了（背向走）是正无穷，这时按距离排。
 */
final class Motion {
    static final long WINDOW_MS = Config.getLong("motionWindowMs", 3_000);
    /** 最远预测多少秒。 */
    static final double HORIZON_S = Config.getDouble("motionHorizonS", 120);
    private static final double STEP_S = 0.25;

    private record Sample(long t, double x, double z) {}

    private final Deque<Sample> samples = new ArrayDeque<>();

    void record(long nowMs, double x, double z) {
        samples.addLast(new Sample(nowMs, x, z));
        while (samples.size() > 2 && nowMs - samples.peekFirst().t() > WINDOW_MS) {
            samples.pollFirst();
        }
    }

    /** 速度（格/秒），{vx, vz}；样本不够时是 0。 */
    double[] velocity() {
        if (samples.size() < 2) {
            return new double[] {0, 0};
        }
        Sample a = samples.peekFirst(), b = samples.peekLast();
        double dt = (b.t() - a.t()) / 1000.0;
        if (dt <= 0) {
            return new double[] {0, 0};
        }
        return new double[] {(b.x() - a.x()) / dt, (b.z() - a.z()) / dt};
    }

    double[] position() {
        Sample b = samples.peekLast();
        return b == null ? new double[] {0, 0} : new double[] {b.x(), b.z()};
    }

    /** tile 到"视距边上"的切比雪夫 chunk 距离：<= 0 表示已经在视距里。 */
    static int chunkDistanceBeyondView(TilePos tile, double x, double z, int view) {
        int cx = (int) Math.floor(x / 16), cz = (int) Math.floor(z / 16);
        int dx = Math.max(0, Math.max(tile.minChunkX() - cx, cx - tile.maxChunkX()));
        int dz = Math.max(0, Math.max(tile.minChunkZ() - cz, cz - tile.maxChunkZ()));
        return Math.max(dx, dz) - view;
    }

    /**
     * tile 中心离飞行线（过玩家、沿速度方向的直线）多少个 chunk（11-09：派活先做前方走廊）。不动时是正无穷。纯函数。
     *
     * @param x 玩家的方块坐标
     */
    static double lateralChunks(TilePos tile, double x, double z, double vx, double vz) {
        double len = Math.hypot(vx, vz);
        if (len == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double cx = tile.minChunkX() + (TilePos.SIZE - 1) / 2.0 - x / 16, cz = tile.minChunkZ() + (TilePos.SIZE - 1) / 2.0 - z / 16;
        return Math.abs(cx * vz - cz * vx) / len;
    }

    /** tile 离这个玩家飞行线多少个 chunk（见上）。 */
    double lateralChunks(TilePos tile) {
        double[] p = position(), v = velocity();
        return lateralChunks(tile, p[0], p[1], v[0], v[1]);
    }

    /** 按现在的速度直线走，多少秒后 tile 进入视距。 */
    double deadlineSeconds(TilePos tile, int view) {
        double[] p = position(), v = velocity();
        if (chunkDistanceBeyondView(tile, p[0], p[1], view) <= 0) {
            return 0;
        }
        if (v[0] == 0 && v[1] == 0) {
            return Double.POSITIVE_INFINITY;
        }
        for (double t = STEP_S; t <= HORIZON_S; t += STEP_S) {
            if (chunkDistanceBeyondView(tile, p[0] + v[0] * t, p[1] + v[1] * t, view) <= 0) {
                return t;
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    /**
     * 和 {@link #deadlineSeconds} 一样，但直接解出来（不逐步模拟），找需求每次扫描都要对很多 tile 算。
     * tile 进入视距 ⇔ 玩家所在 chunk 落进 tile 四周各扩 view 圈的方框，即玩家方块坐标落进
     * [16(min−view), 16(max+view+1)) 这个区间（两个方向都要），求直线运动第一次同时落进两个区间的时间。
     */
    double deadlineSecondsFast(TilePos tile, int view) {
        double[] p = position(), v = velocity();
        if (chunkDistanceBeyondView(tile, p[0], p[1], view) <= 0) {
            return 0;
        }
        double[] tx = enter(p[0], v[0], 16.0 * (tile.minChunkX() - view), 16.0 * (tile.maxChunkX() + view + 1));
        double[] tz = enter(p[1], v[1], 16.0 * (tile.minChunkZ() - view), 16.0 * (tile.maxChunkZ() + view + 1));
        // 只看以后：已经离开（进出都在过去）的不算
        double t0 = Math.max(0, Math.max(tx[0], tz[0])), t1 = Math.min(tx[1], tz[1]);
        if (t0 >= t1 || t0 > HORIZON_S) {
            return Double.POSITIVE_INFINITY;
        }
        return t0;
    }

    /** 一维：x(t) = x0 + vt 落在 [lo, hi) 里的时间段 [进, 出)；永远不在里面返回空段。 */
    private static double[] enter(double x0, double v, double lo, double hi) {
        if (v == 0) {
            return x0 >= lo && x0 < hi ? new double[] {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}
                    : new double[] {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        }
        double a = (lo - x0) / v, b = (hi - x0) / v;
        return new double[] {Math.min(a, b), Math.max(a, b)};
    }
}
