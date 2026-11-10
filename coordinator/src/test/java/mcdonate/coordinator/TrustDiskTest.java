package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import mcdonate.coordinator.RegionRules.ChunkInfo;
import org.junit.jupiter.api.Test;

/**
 * 11-10 演示服务器用（-Dmcdonate.importTrustDisk=true，默认关，正式服不开）：导入时不读存档查旧状态（主线程上 36 次 read().join()），
 * 只看内存和导入账本：在内存里的照样拒收；账本里服务器接手过的当服务器的；账本里我们导入过的按"是不是写它的那个 tile 的核心"
 * 定状态（核心不被外圈覆盖）；账本里没有的当空的直接写。
 */
class TrustDiskTest {
    private static final int CORE = 8;   // INITIALIZE_LIGHT 的序号（测试里不碰 ChunkStatus）

    private static ImportLedger.Entry entry(String world, int tx, int tz, boolean serverLoaded) {
        return new ImportLedger.Entry(world, tx, tz, 1, "", 0, serverLoaded);
    }

    @Test
    void offByDefault() {
        assertFalse(TrustDisk.ON, "默认关：正式服照旧读存档");
    }

    @Test
    void inMemoryStillRejected() {
        assertEquals(ChunkInfo.loaded(), TrustDisk.info(null, true, 0, 0, CORE));
        assertEquals(ChunkInfo.loaded(), TrustDisk.info(entry("W", 0, 0, false), true, 0, 0, CORE));
    }

    @Test
    void unknownChunkIsEmpty() {
        assertEquals(ChunkInfo.empty(), TrustDisk.info(null, false, 5, 5, CORE), "账本里没有：当空的（不读存档）");
    }

    @Test
    void serverTakenOverIsServer() {
        assertEquals(ChunkInfo.server(), TrustDisk.info(entry("W", 0, 0, true), false, 1, 1, CORE));
    }

    @Test
    void ourCoreIsNotOverwrittenByRings() {
        // tile (0,0) 的核心 chunk (1,1)：按核心的状态；外圈来的状态低，importPlan 会 SKIP
        assertEquals(ChunkInfo.owned("W", CORE), TrustDisk.info(entry("W", 0, 0, false), false, 1, 1, CORE));
        java.util.Map<Integer, ChunkInfo> cur = java.util.Map.of(0, TrustDisk.info(entry("W", 0, 0, false), false, 1, 1, CORE));
        RegionRules.ImportPlan<Integer> p = RegionRules.importPlan(cur, "W", java.util.Map.of(0, 6));
        assertEquals(RegionRules.Action.SKIP, p.actions().get(0), "外圈（状态 6）不覆盖我们写的核心");
    }

    @Test
    void ourRingIsLowSoCoreOverwritesIt() {
        // tile (0,0) 写的外圈 chunk (4,1)（在 tile (1,0) 里）：状态按最低的，tile (1,0) 的核心来了覆盖它
        ChunkInfo ring = TrustDisk.info(entry("W", 0, 0, false), false, 4, 1, CORE);
        assertEquals(ChunkInfo.owned("W", 0), ring);
        RegionRules.ImportPlan<Integer> p = RegionRules.importPlan(java.util.Map.of(0, ring), "W", java.util.Map.of(0, CORE));
        assertEquals(RegionRules.Action.OVERWRITE, p.actions().get(0));
    }

    @Test
    void otherWorldStillDiscarded() {
        ChunkInfo other = TrustDisk.info(entry("V", 0, 0, false), false, 1, 1, CORE);
        assertFalse(RegionRules.importPlan(java.util.Map.of(0, other), "W", java.util.Map.of(0, CORE)).accepted());
    }
}
