package mcdonate.coordinator.overlay;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import mcdonate.common.Provenance;
import mcdonate.common.net.OverlaySubscribe;
import mcdonate.common.net.ProvenanceUpdate;
import mcdonate.common.net.ProvenanceUpdate.Donor;
import mcdonate.common.net.ProvenanceUpdate.Entry;
import mcdonate.common.net.ProvenanceUpdate.Source;
import mcdonate.common.net.ProvenanceUpdate.Summary;

/**
 * 游戏内来源上色的服务器端逻辑（docs/overlay-plan.md 第 4 节），纯逻辑，不碰 Minecraft 服务器；{@link OverlaySender} 把它接到网络和 tick 上。
 *
 * <ul>
 *   <li>{@link #onEvent}（任何线程，Provenance 监听）：排进队列。来源一直记着（每个 chunk 约 12 字节），没人订阅时只是不发，
 *       这样打开 overlay 时整片快照里有之前生成的 chunk。超过 {@code maxState}（默认 100 万个，约 16 MB）时，离所有在线玩家都超过
 *       {@code pruneRadius}（默认 64）个 chunk 的删掉。只在内存里，服务器重启后清空。
 *   <li>{@link #tick}（主线程，每秒一次）：取出事件更新来源表；给每个订阅了的玩家算要发的包：订阅后第一次是视距内的整片快照，
 *       之后只发变化了的、和走动时新进入范围的 chunk。donor 表每个 donor 只给每个玩家发一次。hud 开着时带摘要。
 *   <li>donor 的名字在主线程上查（{@code names}，fish 的 Coordinator.workerName：会读玩家列表），按稳定名字分配编号，
 *       worker 重连换了编号也是同一个 donor、同一种颜色。
 *   <li>来源：VANILLA → 服务器自己生成；DONOR → 这个 donor；RESUMED → 原来是 donor 的保留 donor（donor 生成的、服务器接着做完），
 *       否则是"导入后接着做完"；REJECTED → 被打回；LOADED（读盘的成品）不知道是谁生成的，不发。
 * </ul>
 */
public final class OverlayCore {
    /** stable：稳定名字（颜色按它算，比如 player:&lt;uuid&gt;）；display：显示名。 */
    public record Name(String stable, String display) {}

    /** 一个在线玩家：在哪个 chunk、范围多大（服务器视距）。 */
    public record View(UUID player, int cx, int cz, int radius) {}

    private record Event(int cx, int cz, Provenance.Kind kind, int worker) {}

    private static final class Sub {
        int flags;
        boolean needFull = true;
        int lastCx, lastCz, lastRadius = -1;
        long packets;
    }

    public static final int MAX_STATE = 1_000_000;
    public static final int PRUNE_RADIUS = 64;

    private final IntFunction<Optional<Name>> names;
    private final IntSupplier donorsOnline;
    private final int maxState, pruneRadius;
    private final ConcurrentLinkedQueue<Event> queue = new ConcurrentLinkedQueue<>();
    private volatile boolean active;

    // 以下只在主线程上用
    private final Long2IntOpenHashMap state = new Long2IntOpenHashMap();   // chunk → 来源编码，见 code()
    private final LongOpenHashSet dirty = new LongOpenHashSet();
    private final List<Donor> donors = new ArrayList<>();
    private final Map<String, Integer> donorByStable = new HashMap<>();
    private final Map<Integer, Integer> donorByWorker = new HashMap<>();
    private final Map<String, Long> importedBy = new HashMap<>();
    private long importedTotal;
    private final Map<UUID, Sub> subs = new LinkedHashMap<>();
    private final Map<UUID, Long> sentPackets = new HashMap<>();

    public OverlayCore(IntFunction<Optional<Name>> names, IntSupplier donorsOnline) {
        this(names, donorsOnline, MAX_STATE, PRUNE_RADIUS);
    }

    OverlayCore(IntFunction<Optional<Name>> names, IntSupplier donorsOnline, int maxState, int pruneRadius) {
        this.names = names;
        this.donorsOnline = donorsOnline;
        this.maxState = maxState;
        this.pruneRadius = pruneRadius;
        state.defaultReturnValue(-1);
    }

    /** 超过上限：删掉离所有在线玩家都远的。 */
    private void prune(List<View> views) {
        if (state.size() <= maxState) {
            return;
        }
        state.keySet().removeIf(k -> views.stream().noneMatch(v -> inside(k, v.cx(), v.cz(), pruneRadius)));
    }

    // ---- 编码：低 3 位是来源，高位是 donor 编号 + 1（没有 donor 是 0） ----

    private static int code(Source s, int donor) {
        return s.ordinal() | ((donor + 1) << 3);
    }

    private static Source source(int code) {
        return Source.values()[code & 7];
    }

    private static int donorOf(int code) {
        return (code >>> 3) - 1;
    }

    static long key(int cx, int cz) {
        return (long) cx << 32 | (cz & 0xffffffffL);
    }

    // ---- 任何线程 ----

    public void onEvent(int cx, int cz, Provenance.Kind kind, int worker) {
        queue.add(new Event(cx, cz, kind, worker));
    }

    public boolean active() {
        return active;
    }

    // ---- 主线程 ----

    public void subscribe(UUID player, OverlaySubscribe s) {
        if (!s.valid()) {
            return;
        }
        if (!s.overlay() && !s.hud()) {
            remove(player);
            return;
        }
        Sub sub = subs.computeIfAbsent(player, k -> new Sub());
        if (s.overlay() && (sub.flags & OverlaySubscribe.OVERLAY) == 0) {
            sub.needFull = true;   // 新打开 overlay：从整片快照开始
        }
        sub.flags = s.flags();
        active = true;
    }

    public void remove(UUID player) {
        subs.remove(player);
        if (subs.isEmpty()) {
            active = false;
        }
    }

    /** 玩家去了别的维度（来源只记主世界）：不在 tick 的 views 里，回来以后从整片快照重来（codex-audit A14）。 */
    public void away(UUID player) {
        Sub sub = subs.get(player);
        if (sub != null) {
            sub.needFull = true;
        }
    }

    public int subscribers() {
        return subs.size();
    }

    public int stateSize() {
        return state.size();
    }

    /** 这次开服以来给这个玩家发了多少包（日志和端到端测试用）。 */
    public long sentPackets(UUID player) {
        return sentPackets.getOrDefault(player, 0L);
    }

    private int donorIndex(int worker) {
        Integer cached = donorByWorker.get(worker);
        if (cached != null) {
            return cached;
        }
        Name n = names.apply(worker).orElse(new Name("worker:" + worker, "#" + worker));
        int idx = donorByStable.computeIfAbsent(n.stable(), k -> {
            donors.add(new Donor(donors.size(), clip(n.display(), 32), clip(n.stable(), 64)));
            return donors.size() - 1;
        });
        donorByWorker.put(worker, idx);
        return idx;
    }

    private static String clip(String s, int max) {
        String t = s == null || s.isEmpty() ? "?" : s;
        return t.length() <= max ? t : t.substring(0, max);
    }

    private void apply(Event e) {
        long k = key(e.cx(), e.cz());
        int old = state.get(k);
        int now = switch (e.kind()) {
            case VANILLA -> code(Source.SERVER, -1);
            case DONOR -> {
                int d = donorIndex(e.worker());
                importedTotal++;
                importedBy.merge(donors.get(d).key(), 1L, Long::sum);
                yield code(Source.DONOR, d);
            }
            case RESUMED -> old != -1 && source(old) == Source.DONOR ? old : code(Source.RESUMED, -1);
            case REJECTED -> code(Source.REJECTED, -1);
            case LOADED -> old;   // 读盘的成品：不知道是谁生成的，有记录就保留
        };
        if (now != old && now != -1) {
            state.put(k, now);
            dirty.add(k);
        }
    }

    private Entry entry(long k, int code) {
        Source s = source(code);
        return new Entry((int) (k >> 32), (int) k, s, s == Source.DONOR ? donorOf(code) : -1);
    }

    private static boolean inside(long k, int cx, int cz, int r) {
        int x = (int) (k >> 32), z = (int) k;
        return Math.abs(x - cx) <= r && Math.abs(z - cz) <= r;
    }

    /** 每秒一次：取出事件，给每个订阅了的在线玩家算要发的包。返回 玩家 → 包（没东西发的玩家不在里面）。 */
    public Map<UUID, List<ProvenanceUpdate>> tick(List<View> views) {
        Event e;
        while ((e = queue.poll()) != null) {
            apply(e);
        }
        prune(views);
        Map<UUID, List<ProvenanceUpdate>> out = new LinkedHashMap<>();
        for (View v : views) {
            Sub sub = subs.get(v.player());
            if (sub == null) {
                continue;
            }
            int r = Math.min(v.radius(), ProvenanceUpdate.MAX_OFFSET);
            boolean overlay = (sub.flags & OverlaySubscribe.OVERLAY) != 0;
            boolean full = false;
            List<Entry> entries = new ArrayList<>();
            if (overlay) {
                full = sub.needFull || sub.lastRadius != r;
                for (int x = v.cx() - r; x <= v.cx() + r; x++) {
                    for (int z = v.cz() - r; z <= v.cz() + r; z++) {
                        long k = key(x, z);
                        int c = state.get(k);
                        if (c == -1) continue;
                        // 整片快照：全发；增量：变了的，或者走动后新进入范围的
                        if (full || dirty.contains(k) || !inside(k, sub.lastCx, sub.lastCz, sub.lastRadius)) {
                            entries.add(entry(k, c));
                        }
                    }
                }
                sub.needFull = false;
                sub.lastCx = v.cx();
                sub.lastCz = v.cz();
                sub.lastRadius = r;
            }
            // 每包附上它引用的所有 donor（codex-audit A12、A13：原来每个 donor 只发一次，客户端已知 A、新包有 A 和 B 时
            // 只附 B，valid 拒绝整包；客户端关 overlay 时清了 donor 表，再开也拿不回名字）。donor 少，几十字节
            java.util.TreeSet<Integer> referenced = new java.util.TreeSet<>();
            for (Entry en : entries) {
                if (en.source() == Source.DONOR) {
                    referenced.add(en.donor());
                }
            }
            List<Donor> newDonors = new ArrayList<>();
            for (int d : referenced) {
                newDonors.add(donors.get(d));
            }
            Summary summary = (sub.flags & OverlaySubscribe.HUD) != 0
                    ? new Summary(Math.max(0, donorsOnline.getAsInt()), importedTotal, importedBy.getOrDefault("player:" + v.player(), 0L))
                    : null;
            if (!full && entries.isEmpty() && summary == null) {
                continue;
            }
            List<ProvenanceUpdate> ups = ProvenanceUpdate.split(full, v.cx(), v.cz(), newDonors, entries, summary);
            sub.packets += ups.size();
            sentPackets.merge(v.player(), (long) ups.size(), Long::sum);
            out.put(v.player(), ups);
        }
        dirty.clear();
        return out;
    }
}
