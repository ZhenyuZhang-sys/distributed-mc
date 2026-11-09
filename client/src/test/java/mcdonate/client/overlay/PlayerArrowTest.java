package mcdonate.client.overlay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 小地图上玩家的朝向箭头（用户 11-09：鞘翅飞的时候要看出自己往哪飞，对照前方的颜色）。
 * 小地图北朝上、不跟着转：屏幕 x 向右是东（+X），屏幕 y 向下是南（+Z）。Minecraft 的 yaw：0 是南，90 是西，180 是北，-90 是东。
 */
class PlayerArrowTest {
    static void dir(float yaw, double dx, double dy) {
        double[] d = PlayerArrow.direction(yaw);
        assertArrayEquals(new double[] {dx, dy}, d, 1e-9, "yaw " + yaw);
    }

    @Test
    void yawToScreenDirection() {
        dir(0, 0, 1);       // 南：屏幕向下
        dir(90, -1, 0);     // 西：屏幕向左
        dir(180, 0, -1);    // 北：屏幕向上
        dir(-90, 1, 0);     // 东：屏幕向右
        dir(-180, 0, -1);   // -180 和 180 一样
        dir(450, -1, 0);    // 超过 360 取模：450 = 90
        dir(-450, 1, 0);    // -450 = -90
        double[] d = PlayerArrow.direction(45);   // 西南
        assertEquals(-Math.sqrt(.5), d[0], 1e-9);
        assertEquals(Math.sqrt(.5), d[1], 1e-9);
    }

    @Test
    void triangleTipPointsWhereThePlayerLooks() {
        float[] t = PlayerArrow.triangle(180, 100, 50, 7);   // 朝北：尖在中心正上方
        assertEquals(100, t[0], 1e-4);
        assertTrue(t[1] < 50, "尖在上面：" + t[1]);
        // 两个底角在中心下方、左右对称
        assertEquals(t[2] - 100, -(t[4] - 100), 1e-4);
        assertTrue(t[3] > 50 && t[5] > 50);
        float[] east = PlayerArrow.triangle(-90, 0, 0, 7);
        assertTrue(east[0] > 0 && Math.abs(east[1]) < 1e-4, "朝东：尖在右边");
    }

    @Test
    void arrowAtLeastSevenPixels() {
        assertEquals(7f, PlayerArrow.size(2), 1e-6);      // 格子很小的时候至少约 7 像素
        assertEquals(9f, PlayerArrow.size(6), 1e-6);      // 格子大一些就是 1.5 格
        float[] t = PlayerArrow.triangle(0, 0, 0, PlayerArrow.size(2));
        double tipToBase = Math.hypot(t[0] - (t[2] + t[4]) / 2, t[1] - (t[3] + t[5]) / 2);
        assertEquals(7, tipToBase, 0.01, "尖到底边中点就是箭头的长度");
    }

    @Test
    void outlineIsSlightlyBiggerAroundTheSameCenter() {
        float[] in = PlayerArrow.triangle(30, 10, 10, 7), out = PlayerArrow.outline(30, 10, 10, 7);
        double cin = (in[1] + in[3] + in[5]) / 3, cout = (out[1] + out[3] + out[5]) / 3;
        assertEquals(cin, cout, 1e-3);
        assertTrue(Math.hypot(out[0] - 10, out[1] - 10) > Math.hypot(in[0] - 10, in[1] - 10));
    }
}
