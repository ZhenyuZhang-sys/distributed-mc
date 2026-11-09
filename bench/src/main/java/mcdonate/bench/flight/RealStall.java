package mcdonate.bench.flight;

/**
 * 跟踪真人玩家（docs/step10-plan.md 7.2）时的"卡住"：近似判定，纯逻辑。
 *
 * <p>真人客户端不会走进自己还没收到的 chunk，会停在边缘。一个 tick 算卡住 =
 * 这个 tick 几乎没动（{@link #STILL} 格以内），而且沿视线的水平方向、{@link #EDGE} 格以内就要进的那个 chunk 没就绪。
 * 站着看风景（前方就绪）、离边缘还远、在动，都不算。玩家也可能因为别的原因停在边缘，所以这是近似，报告里照写。
 */
public final class RealStall {
    public static final double STILL = 0.01;
    public static final double EDGE = 2.0;
    private static final double STEP = 0.25;

    private RealStall() {}

    /** moved：这个 tick 的水平位移（格）；yRot：原版朝向（0 朝 +z，90 朝 -x，-90 朝 +x）。 */
    public static boolean stalled(double moved, double x, double z, float yRot, Flight.Ready ready) {
        if (moved >= STILL) {
            return false;
        }
        double rad = Math.toRadians(yRot);
        double dx = -Math.sin(rad), dz = Math.cos(rad);
        int cx = Math.floorDiv((int) Math.floor(x), 16), cz = Math.floorDiv((int) Math.floor(z), 16);
        for (double s = STEP; s <= EDGE + 1e-9; s += STEP) {
            int nx = Math.floorDiv((int) Math.floor(x + dx * s), 16), nz = Math.floorDiv((int) Math.floor(z + dz * s), 16);
            if (nx != cx || nz != cz) {
                return !ready.ready(nx, nz);
            }
        }
        return false;
    }
}
