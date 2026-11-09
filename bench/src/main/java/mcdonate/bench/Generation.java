package mcdonate.bench;

import mcdonate.common.Provenance;
import mcdonate.common.gen.FullStatus;

/**
 * chunk 变成完整状态时：计数（Counters）和来源事件（Provenance）一起发（docs/step4-plan.md 3.3、4.3）。
 *
 * <p>同一把锁和钩子在 common 的 {@link FullStatus}（overlay 计划 2.1：部署时没有 bench 也要有来源事件）；
 * 这里把 Counters 注册成它的计数，并按 Counters 的口径给出快照。一个监听器在 listen 和 unlisten 之间收到的每类事件数，
 * 严格等于两个快照之间对应计数的增量：GENERATED → VANILLA，RESUMED → RESUMED，LOADED → LOADED。
 */
public final class Generation {
    /** 三个完整状态计数的快照。 */
    public record Snapshot(long generated, long loaded, long resumed) {}

    static {
        FullStatus.setCounter(k -> Counters.onFull(switch (k) {
            case GENERATED -> Counters.FullKind.GENERATED;
            case LOADED -> Counters.FullKind.LOADED;
            case RESUMED -> Counters.FullKind.RESUMED;
        }));
    }

    private Generation() {}

    /** bench 初始化时调一次：注册计数（类初始化时完成）。 */
    public static void install() {}

    public static void onFull(Counters.FullKind kind, int cx, int cz) {
        FullStatus.onFull(switch (kind) {
            case GENERATED -> FullStatus.Kind.GENERATED;
            case LOADED -> FullStatus.Kind.LOADED;
            case RESUMED -> FullStatus.Kind.RESUMED;
        }, cx, cz);
    }

    public static Snapshot listen(Provenance.Listener l) {
        return FullStatus.listen(l, Generation::read);
    }

    public static Snapshot unlisten(Provenance.Listener l) {
        return FullStatus.unlisten(l, Generation::read);
    }

    public static Snapshot snapshot() {
        return FullStatus.snapshot(Generation::read);
    }

    private static Snapshot read() {
        return new Snapshot(Counters.fullGenerated(), Counters.fullLoaded(), Counters.fullResumed());
    }
}
