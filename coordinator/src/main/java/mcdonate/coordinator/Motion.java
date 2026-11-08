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
}
