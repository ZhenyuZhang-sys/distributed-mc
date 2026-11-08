package mcdonate.coordinator;

import java.util.Collection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongFunction;
import mcdonate.common.TilePos;
import mcdonate.coordinator.RegionRules.ChunkInfo;

/**
 * 找需求的纯逻辑（第 6 步 6.3）：玩家周围"视距 + 4 圈"覆盖哪些 tile、按离玩家远近排序、每个 tile 能不能入队。
 * 查 chunk 情况由调用方传进来（游戏里查缓存，测试里用假的）。
 */
final class Demand {
    private Demand() {}

    static long key(int x, int z) {
        return ((long) z << 32) | (x & 0xffffffffL); // 和 ChunkPos.asLong 一样
    }

    /** chunk (cx, cz) 周围 radius 圈（含）覆盖到的 tile。 */
    static Set<TilePos> tilesAround(int cx, int cz, int radius) {
        TilePos a = TilePos.ofChunk(cx - radius, cz - radius);
        TilePos b = TilePos.ofChunk(cx + radius, cz + radius);
        Set<TilePos> out = new HashSet<>();
        for (int tx = a.x(); tx <= b.x(); tx++) {
            for (int tz = a.z(); tz <= b.z(); tz++) {
                out.add(new TilePos(tx, tz));
            }
        }
        return out;
    }

    /** tile 里离 chunk (cx, cz) 最近的 chunk 有多远（切比雪夫距离，tile 里面是 0）。 */
    static int minChunkDistance(TilePos tile, int cx, int cz) {
        int dx = Math.max(0, Math.max(tile.minChunkX() - cx, cx - tile.maxChunkX()));
        int dz = Math.max(0, Math.max(tile.minChunkZ() - cz, cz - tile.maxChunkZ()));
        return Math.max(dx, dz);
    }

    /** 按 tile 中心到玩家所在 chunk 的距离排序，近的在前。 */
    static List<TilePos> nearestFirst(Collection<TilePos> tiles, int cx, int cz) {
        List<TilePos> out = new ArrayList<>(tiles);
        out.sort(Comparator.comparingDouble((TilePos t) -> {
            double dx = TilePos.center(t.x(), TilePos.SIZE) - cx, dz = TilePos.center(t.z(), TilePos.SIZE) - cz;
            return dx * dx + dz * dz;
        }).thenComparingInt(TilePos::x).thenComparingInt(TilePos::z));
        return out;
    }

    /** 这个 tile 能不能入队、认不认主：看它的区域（tile 和外面一圈，36 个 chunk）。 */
    static RegionRules.Enqueue decide(TilePos tile, LongFunction<ChunkInfo> chunk) {
        List<ChunkInfo> region = new ArrayList<>(36);
        for (int x = tile.minChunkX() - 1; x <= tile.maxChunkX() + 1; x++) {
            for (int z = tile.minChunkZ() - 1; z <= tile.maxChunkZ() + 1; z++) {
                region.add(chunk.apply(key(x, z)));
            }
        }
        return RegionRules.enqueue(region);
    }
}
