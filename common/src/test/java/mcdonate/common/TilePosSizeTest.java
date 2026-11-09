package mcdonate.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 7.5：tile 大小 1、2、4、8 的区域计算，含负坐标。 */
class TilePosSizeTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8})
    void everyChunkInExactlyOneTile(int size) {
        for (int c = -3 * size - 1; c <= 3 * size + 1; c++) {
            TilePos t = TilePos.ofChunk(c, -c, size);
            int minX = TilePos.minChunk(t.x(), size), maxX = TilePos.maxChunk(t.x(), size);
            assertEquals(size - 1, maxX - minX);
            assertEquals(true, minX <= c && c <= maxX, size + " " + c);
            int minZ = TilePos.minChunk(t.z(), size), maxZ = TilePos.maxChunk(t.z(), size);
            assertEquals(true, minZ <= -c && -c <= maxZ, size + " " + c);
        }
    }

    @Test
    void negativeCoordinatesFloor() {
        assertEquals(new TilePos(-1, -1), TilePos.ofChunk(-1, -1, 4));
        assertEquals(new TilePos(-1, -2), TilePos.ofChunk(-8, -9, 8));
        assertEquals(new TilePos(-5, 3), TilePos.ofChunk(-5, 3, 1));
        assertEquals(new TilePos(-2, 1), TilePos.ofChunk(-3, 2, 2));
    }

    @Test
    void centers() {
        assertEquals(1.5, TilePos.center(0, 4));
        assertEquals(-2.5, TilePos.center(-1, 4));
        assertEquals(0.0, TilePos.center(0, 1));
        assertEquals(-4.5, TilePos.center(-1, 8));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8})
    void exportOverlapRatio(int size) {
        // 导出带外圈 1 圈：(n+2)²/n² − 1，计划里 1：800%，2：300%，4：125%，8：56%
        double overlap = Math.pow(size + 2, 2) / (size * size) - 1;
        double expected = switch (size) { case 1 -> 8.0; case 2 -> 3.0; case 4 -> 1.25; default -> 0.5625; };
        assertEquals(expected, overlap, 1e-9);
    }
}
