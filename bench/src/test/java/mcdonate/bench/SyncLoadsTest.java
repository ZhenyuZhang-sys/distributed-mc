package mcdonate.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 守护计数只算"调用时 chunk 还没在内存里"的 getChunk（11-09 fish 第 7 步对比里抓到误报）：
 * 2 核容器上主线程可能在 getChunk 里被调度走几毫秒，chunk 早就就绪了也会超过 1 毫秒，那不是同步加载。
 */
class SyncLoadsTest {
    final List<String> seen = new ArrayList<>();
    final SyncLoads.Listener l = (cx, cz, nanos, stack, player) -> seen.add(cx + "," + cz + (player ? " player" : " world"));

    static void busy(long millis) {
        long end = System.nanoTime() + millis * 1_000_000;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    @Test
    void slowCallOnAlreadyLoadedChunkIsNotCounted() {
        SyncLoads.addListener(l);
        try {
            SyncLoads.onTickStart();
            SyncLoads.enter(true);   // 进来时 chunk 已经在内存里
            busy(3);
            SyncLoads.exit(1, 2);
            assertTrue(seen.isEmpty(), seen.toString());
        } finally {
            SyncLoads.removeListener(l);
        }
    }

    @Test
    void slowCallOnMissingChunkIsCountedAndAttributed() {
        SyncLoads.addListener(l);
        try {
            SyncLoads.onTickStart();
            SyncLoads.enter(false);
            busy(3);
            SyncLoads.exit(3, 4);
            SyncLoads.enterPlayerTick();
            SyncLoads.enter(false);
            busy(3);
            SyncLoads.exit(5, 6);
            SyncLoads.exitPlayerTick();
            assertEquals(List.of("3,4 world", "5,6 player"), seen);
        } finally {
            SyncLoads.removeListener(l);
        }
    }

    @Test
    void nestedCallsOnlyOuterDecides() {
        SyncLoads.addListener(l);
        try {
            SyncLoads.onTickStart();
            SyncLoads.enter(true);
            SyncLoads.enter(false);  // 里层的不单独算
            busy(3);
            SyncLoads.exit(7, 8);
            SyncLoads.exit(7, 8);
            assertTrue(seen.isEmpty(), seen.toString());
        } finally {
            SyncLoads.removeListener(l);
        }
    }
}
