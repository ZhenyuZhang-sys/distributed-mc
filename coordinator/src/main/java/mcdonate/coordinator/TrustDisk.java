package mcdonate.coordinator;

import mcdonate.common.Config;
import mcdonate.common.TilePos;
import mcdonate.coordinator.RegionRules.ChunkInfo;

/**
 * 11-10 演示服务器用：-Dmcdonate.importTrustDisk=true（默认关，正式服不开）。
 *
 * <p>导入时不读存档查 36 个 chunk 的旧状态（主线程上每个 read().join()，2 核上一个 tile 的导入大头），只看：
 * <ul>
 *   <li>内存里的（有 holder、等着卸载的）：照样拒收；
 *   <li>导入账本里服务器接手过的：当服务器的（拒收）；
 *   <li>账本里我们导入过的：按"它在不在写它的那个 tile 的核心里"定状态：核心按核心导出的状态（外圈来了 SKIP，不覆盖），
 *       外圈按最低（别的 tile 的核心来了覆盖它）；
 *   <li>账本里没有的：当空的，直接写。
 * </ul>
 * 代价：存档里服务器自己生成、我们没导入过的 chunk 认不出来，可能被覆盖（找需求本来就不给这种区域派活，手动派的才会碰到）。
 */
final class TrustDisk {
    private TrustDisk() {}

    static final boolean ON = "true".equalsIgnoreCase(Config.get("importTrustDisk", "false"));

    /** 不读存档时一个 chunk 的情况。e：账本里的记录（没有是 null）；inMemory：在不在内存里。纯函数。 */
    static ChunkInfo info(ImportLedger.Entry e, boolean inMemory, int cx, int cz, int coreStatus) {
        if (inMemory) {
            return ChunkInfo.loaded();
        }
        if (e == null) {
            return ChunkInfo.empty();
        }
        if (e.serverLoaded()) {
            return ChunkInfo.server();
        }
        TilePos t = new TilePos(e.tileX, e.tileZ);
        boolean core = cx >= t.minChunkX() && cx <= t.maxChunkX() && cz >= t.minChunkZ() && cz <= t.maxChunkZ();
        return ChunkInfo.owned(e.world, core ? coreStatus : 0);
    }
}
