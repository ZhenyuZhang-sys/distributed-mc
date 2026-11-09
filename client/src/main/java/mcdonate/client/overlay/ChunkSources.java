package mcdonate.client.overlay;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import mcdonate.common.net.ProvenanceUpdate;
import mcdonate.common.net.ProvenanceUpdate.Donor;
import mcdonate.common.net.ProvenanceUpdate.Entry;
import mcdonate.common.net.ProvenanceUpdate.Source;
import mcdonate.common.net.ProvenanceUpdate.Summary;
import mcdonate.common.overlay.DonorColors;

/**
 * 客户端收到的来源数据（docs/overlay-plan.md 第 5 节）：玩家附近 chunk → 颜色。在渲染线程上用（收包后 client.execute 过来）。
 * 整片快照（full）先清空 chunk 表；donor 表在这次连接里一直记着（服务器每个 donor 只发一次）。坏数据丢掉。
 */
public final class ChunkSources {
    private final Long2IntOpenHashMap colors = new Long2IntOpenHashMap();
    private final Map<Integer, Donor> donors = new TreeMap<>();
    private Summary summary;
    private long updates;
    private int version;   // 每次内容变化加一，小地图据此决定要不要重画

    public ChunkSources() {
        colors.defaultReturnValue(-1);
    }

    static long key(int cx, int cz) {
        return (long) cx << 32 | (cz & 0xffffffffL);
    }

    public void apply(ProvenanceUpdate u) {
        if (!u.valid()) {
            return;
        }
        if (u.full()) {
            colors.clear();
        }
        for (Donor d : u.donors()) {
            donors.put(d.index(), d);
        }
        for (Entry e : u.chunks()) {
            int c = e.source() == Source.DONOR
                    ? (donors.containsKey(e.donor()) ? DonorColors.donor(donors.get(e.donor()).key()) : DonorColors.donor("#" + e.donor()))
                    : DonorColors.of(e.source());
            if (c == -1) {
                colors.remove(key(e.cx(), e.cz()));
            } else {
                colors.put(key(e.cx(), e.cz()), c);
            }
        }
        if (u.summary() != null) {
            summary = u.summary();
        }
        updates++;
        version++;
    }

    /** RGB；没数据或来源不明是 -1（不画）。 */
    public int color(int cx, int cz) {
        return colors.get(key(cx, cz));
    }

    public int size() {
        return colors.size();
    }

    public Summary summary() {
        return summary;
    }

    public long updates() {
        return updates;
    }

    public int version() {
        return version;
    }

    /** 图例：donor 名字和颜色，按编号。 */
    public List<String> donorNames() {
        List<String> out = new ArrayList<>();
        for (Donor d : donors.values()) out.add(d.name());
        return out;
    }

    public List<Donor> donors() {
        return new ArrayList<>(donors.values());
    }

    public Iterable<Long2IntMap.Entry> entries() {
        return colors.long2IntEntrySet();
    }

    /** 超过 max 个时，删掉离 (cx, cz) 超过 keep 圈的（小地图只画附近，飞一小时约 7 万个 chunk，不能只增不减）。 */
    public void prune(int cx, int cz, int keep, int max) {
        if (colors.size() <= max) {
            return;
        }
        colors.keySet().removeIf(k -> Math.abs((int) (k >> 32) - cx) > keep || Math.abs((int) (long) k - cz) > keep);
        version++;
    }

    /** 关掉 overlay、退出世界：都清掉。 */
    public void clear() {
        colors.clear();
        donors.clear();
        summary = null;
        version++;
    }
}
