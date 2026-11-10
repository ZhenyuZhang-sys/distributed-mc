package mcdonate.client.overlay;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * 小地图"探索"模式（用户 11-10）：提前算好的航线不在小地图上剧透，只给客户端真正加载过的 chunk 上色。纯逻辑，只在客户端主线程上用。
 *
 * <p>加载过的记住（飞远了 chunk 卸掉也照样画）；重连、退出世界清空。超过上限时和来源表、服务器的 A14 一样，离玩家超过 keep 个 chunk 的删掉。
 */
public final class ExploredChunks {
    /** 上限和保留半径：和客户端来源表（ChunkSources.prune）一样。 */
    public static final int MAX = 20_000, KEEP = 64;

    private final LongOpenHashSet seen = new LongOpenHashSet();

    /**
     * 这一格画什么颜色：sourceColor 是来源表里的颜色（-1 = 不知道来源，不画）。explore 关：照来源画。
     * explore 开：加载过的才画；loadedNow（客户端现在有这个 chunk）就记下来。
     */
    public int color(int sourceColor, boolean explore, int cx, int cz, boolean loadedNow) {
        if (sourceColor == -1 || !explore) {
            return sourceColor;
        }
        long k = key(cx, cz);
        if (seen.contains(k)) {
            return sourceColor;
        }
        if (loadedNow) {
            seen.add(k);
            return sourceColor;
        }
        return -1;
    }

    /** 超过 max 个时删掉离 (cx, cz) 超过 keep 个 chunk 的（方形距离）。 */
    public void prune(int cx, int cz, int keep, int max) {
        if (seen.size() <= max) {
            return;
        }
        seen.removeIf(k -> Math.abs((int) (k >> 32) - cx) > keep || Math.abs((int) k - cz) > keep);
    }

    public void clear() {
        seen.clear();
    }

    public int size() {
        return seen.size();
    }

    private static long key(int cx, int cz) {
        return (long) cx << 32 | (cz & 0xffffffffL);
    }
}
