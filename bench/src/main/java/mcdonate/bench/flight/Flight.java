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
}
