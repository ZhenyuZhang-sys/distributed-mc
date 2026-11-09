package mcdonate.common.overlay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import mcdonate.common.net.ProvenanceUpdate.Source;

/**
 * 游戏内来源上色的颜色（docs/overlay-plan.md 第 3 节），纯函数，客户端和服务器都能算。
 *
 * <ul>
 *   <li>四类底色沿用实时地图（bench/map/server.py）：服务器自己生成灰绿、导入后接着做完灰紫、被打回红；来源不明不画（-1）。
 *   <li>每个 donor 的颜色只由稳定名字（玩家 UUID，没有就用 donor 世界编号或主机名）决定，worker 重连换了编号也不变：
 *       名字 SHA-256 的前 4 字节 × 黄金角 → 色相，饱和度 0.75、亮度 0.55（和实时地图的 donor 一样）；
 *       离四类底色的色相太近（&lt; {@link #MIN_HUE_GAP} 度）就再转一个黄金角。
 * </ul>
 */
public final class DonorColors {
    public static final float MIN_HUE_GAP = 20f;
    private static final double GOLDEN = 0.618033988749895;
    private static final int[] BASE = {0x5b8c5a, 0x7d6b9e, 0xd62728};

    private DonorColors() {}

    /** 四类的底色（RGB）；DONOR 没有统一底色、UNKNOWN 不画，都返回 -1。 */
    public static int of(Source s) {
        return switch (s) {
            case SERVER -> BASE[0];
            case RESUMED -> BASE[1];
            case REJECTED -> BASE[2];
            case DONOR, UNKNOWN -> -1;
        };
    }

    public static int donor(String stableKey) {
        long v;
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(stableKey.getBytes(StandardCharsets.UTF_8));
            v = ((h[0] & 0xffL) << 24) | ((h[1] & 0xffL) << 16) | ((h[2] & 0xffL) << 8) | (h[3] & 0xffL);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // 每个 JVM 都有 SHA-256
        }
        double hue = (v * GOLDEN) % 1.0;
        for (int i = 0; i < 16 && tooClose((float) (hue * 360)); i++) {
            hue = (hue + GOLDEN) % 1.0;
        }
        return hsl(hue, 0.75, 0.55);
    }

    private static boolean tooClose(float hue) {
        for (int b : BASE) {
            float d = Math.abs(hue - hue(b));
            if (Math.min(d, 360 - d) < MIN_HUE_GAP) return true;
        }
        return false;
    }

    /** RGB 的色相（度）。 */
    public static float hue(int rgb) {
        float r = ((rgb >> 16) & 0xff) / 255f, g = ((rgb >> 8) & 0xff) / 255f, b = (rgb & 0xff) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        if (d == 0) return 0;
        float h = max == r ? ((g - b) / d) % 6 : max == g ? (b - r) / d + 2 : (r - g) / d + 4;
        h *= 60;
        return h < 0 ? h + 360 : h;
    }

    private static int hsl(double h, double s, double l) {
        double c = (1 - Math.abs(2 * l - 1)) * s, hp = h * 6, x = c * (1 - Math.abs(hp % 2 - 1)), m = l - c / 2;
        double r = 0, g = 0, b = 0;
        switch ((int) Math.floor(hp) % 6) {
            case 0 -> { r = c; g = x; }
            case 1 -> { r = x; g = c; }
            case 2 -> { g = c; b = x; }
            case 3 -> { g = x; b = c; }
            case 4 -> { r = x; b = c; }
            default -> { r = c; b = x; }
        }
        return ((int) Math.round((r + m) * 255) << 16) | ((int) Math.round((g + m) * 255) << 8) | (int) Math.round((b + m) * 255);
    }
}
