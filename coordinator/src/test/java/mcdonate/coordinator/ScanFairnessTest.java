package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 11-09（codex-pipe 真客户端发现）：服务器上 2 个玩家，站着的那个排在前面，把找需求每次 500 µs 的预算用完，
 * 飞的那个一个 tile 都没派到。改：移动快的玩家先扫，每个玩家一份预算（站着的用不完也不占别人的）。
 */
class ScanFairnessTest {
    @Test
    void fastPlayersFirst() {
        assertArrayEquals(new int[] {1, 2, 0}, DemandScanner.scanOrder(new double[] {0, 32, 5}));
    }

    @Test
    void eachPlayerGetsASlice() {
        assertEquals(250_000, DemandScanner.sliceNanos(500_000, 2));
        assertEquals(500_000, DemandScanner.sliceNanos(500_000, 1));
        assertEquals(500_000, DemandScanner.sliceNanos(500_000, 0));
    }
}
