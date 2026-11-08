package mcdonate.bench.flight;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.ChunkPos;

/**
 * 维护玩家视野里每个 chunk 的"进视野 / 就绪 / 出视野"事件。纯逻辑：就绪与否由调用方给的函数判断。
 *
 * <ul>
 *   <li>视野 = 原版 {@code ChunkTrackingView.of(center, 视距).contains(x, z)}，和原版发包范围一致（视距 10 时 453 个）。
 *   <li>起飞前的初始视野不发事件，之后离开也不发 leave，避免统计里出现"没进就出"。
 *   <li>进视野时已就绪：同一时刻连发 enter 和 ready。
 * </ul>
 */
public final class ViewTracker {
    public enum Kind { ENTER, READY, LEAVE }

    @FunctionalInterface
    public interface Readiness { boolean ready(int cx, int cz); }

    @FunctionalInterface
    public interface Sink { void event(long tMs, int cx, int cz, Kind kind); }

    /** 一个 tick 的视野统计。 */
    public record Snapshot(int view, int notReady) {
        public double voidRate() {
            return view == 0 ? 0 : (double) notReady / view;
        }
    }

    private final int viewDistance;
    private final Sink sink;
    /** 视野里的 chunk → 是否已就绪。 */
    private final Map<Long, Boolean> inView = new HashMap<>();
    private final Set<Long> initial = new HashSet<>();
    private ChunkTrackingView view;

    public ViewTracker(int viewDistance, Sink sink) {
        if (viewDistance < 2) {
            throw new IllegalArgumentException("视距至少 2");
        }
        this.viewDistance = viewDistance;
        this.sink = sink;
    }

    /** 起飞前：记下初始视野，不发事件。返回初始视野的统计。 */
    public Snapshot start(int centerX, int centerZ, Readiness readiness) {
        if (view != null) {
            throw new IllegalStateException("已经开始了");
        }
        view = ChunkTrackingView.of(new ChunkPos(centerX, centerZ), viewDistance);
        view.forEach(p -> {
            inView.put(p.toLong(), readiness.ready(p.x, p.z));
            initial.add(p.toLong());
        });
        return snapshot();
    }

    /** 每 tick 调一次：先处理进出视野，再查未就绪的有没有就绪。 */
    public Snapshot tick(long tMs, int centerX, int centerZ, Readiness readiness) {
        if (view == null) {
            throw new IllegalStateException("先调 start");
        }
        ChunkTrackingView next = ChunkTrackingView.of(new ChunkPos(centerX, centerZ), viewDistance);
        if (!next.equals(view)) {
            ChunkTrackingView old = view;
            old.forEach(p -> {
                if (!next.contains(p.x, p.z)) {
                    long k = p.toLong();
                    inView.remove(k);
                    if (!initial.remove(k)) {
                        sink.event(tMs, p.x, p.z, Kind.LEAVE);
                    }
                }
            });
            next.forEach(p -> {
                if (!old.contains(p.x, p.z)) {
                    boolean ready = readiness.ready(p.x, p.z);
                    inView.put(p.toLong(), ready);
                    sink.event(tMs, p.x, p.z, Kind.ENTER);
                    if (ready) {
                        sink.event(tMs, p.x, p.z, Kind.READY);
                    }
                }
            });
            view = next;
        }
        for (Map.Entry<Long, Boolean> e : inView.entrySet()) {
            if (!e.getValue()) {
                ChunkPos p = new ChunkPos(e.getKey());
                if (readiness.ready(p.x, p.z)) {
                    e.setValue(true);
                    if (!initial.contains(e.getKey())) {
                        sink.event(tMs, p.x, p.z, Kind.READY);
                    }
                }
            }
        }
        return snapshot();
    }

    public Snapshot snapshot() {
        int notReady = 0;
        for (boolean r : inView.values()) {
            if (!r) {
                notReady++;
            }
        }
        return new Snapshot(inView.size(), notReady);
    }
}
