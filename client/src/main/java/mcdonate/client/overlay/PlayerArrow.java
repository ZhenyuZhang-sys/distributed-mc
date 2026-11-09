package mcdonate.client.overlay;

/**
 * 小地图上玩家的朝向箭头（用户 11-09），纯逻辑。小地图北朝上、不跟着转：屏幕 x 向右是东（+X），y 向下是南（+Z）。
 * Minecraft 的 yaw：0 是南，90 是西，180 是北，-90 是东；朝向 = (-sin yaw, cos yaw)。
 *
 * <p>三角形以重心为中心：尖在前方 2/3 个长度，两个底角在后方 1/3、左右各半个宽度；尖到底边中点正好是长度。
 * 描边是同一个三角形放大 {@link #OUTLINE} 倍，先画深色描边、再画白色箭头，在浅色格子上也看得清。
 */
public final class PlayerArrow {
    public static final float MIN_PX = 7f;
    static final float WIDTH = 0.9f;     // 底边宽 / 长度
    static final float OUTLINE = 1.45f;

    private PlayerArrow() {}

    /** 屏幕上的单位方向 (dx, dy)。 */
    public static double[] direction(float yaw) {
        double y = Math.toRadians(((yaw % 360) + 360) % 360);
        return new double[] {-Math.sin(y), Math.cos(y)};
    }

    /** 箭头长度：1.5 个格子，至少 {@link #MIN_PX} 像素。 */
    public static float size(int cellPx) {
        return Math.max(MIN_PX, 1.5f * cellPx);
    }

    /** {尖 x, 尖 y, 左底角 x, y, 右底角 x, y}，以 (cx, cy) 为重心。 */
    public static float[] triangle(float yaw, float cx, float cy, float length) {
        double[] d = direction(yaw);
        double px = -d[1], py = d[0];                 // 垂直方向
        double tip = length * 2.0 / 3, back = length / 3.0, half = length * WIDTH / 2;
        return new float[] {
            (float) (cx + d[0] * tip), (float) (cy + d[1] * tip),
            (float) (cx - d[0] * back + px * half), (float) (cy - d[1] * back + py * half),
            (float) (cx - d[0] * back - px * half), (float) (cy - d[1] * back - py * half),
        };
    }

    /** 描边：同一个三角形放大，重心不变。 */
    public static float[] outline(float yaw, float cx, float cy, float length) {
        return triangle(yaw, cx, cy, length * OUTLINE);
    }
}
