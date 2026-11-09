package mcdonate.bench;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import mcdonate.common.Provenance;

/**
 * chunk 变成完整状态时：计数（Counters）和来源事件（Provenance）一起发（docs/step4-plan.md 3.3、4.3）。
 *
 * <p>生成线程上报时拿读锁，计数加一和发事件在同一把读锁里；开始 / 停止监听时拿写锁并读计数快照。
 * 所以一个监听器在 listen 和 unlisten 之间收到的每类事件数，严格等于两个快照之间对应计数的增量。
 * 三类一一对应（fish 11-09）：GENERATED → VANILLA，RESUMED → RESUMED（导入的半成品被接着做完），LOADED → LOADED。
 */
public final class Generation {
    /** 三个完整状态计数的快照。 */
    public record Snapshot(long generated, long loaded, long resumed) {}

    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();

    private Generation() {}

    public static void onFull(Counters.FullKind kind, int cx, int cz) {
        LOCK.readLock().lock();
        try {
            Counters.onFull(kind);
            Provenance.report(cx, cz, switch (kind) {
                case GENERATED -> Provenance.Kind.VANILLA;
                case RESUMED -> Provenance.Kind.RESUMED;
                case LOADED -> Provenance.Kind.LOADED;
            }, -1);
        } finally {
            LOCK.readLock().unlock();
        }
    }

    public static Snapshot listen(Provenance.Listener l) {
        LOCK.writeLock().lock();
        try {
            Provenance.addListener(l);
            return read();
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public static Snapshot unlisten(Provenance.Listener l) {
        LOCK.writeLock().lock();
        try {
            Provenance.removeListener(l);
            return read();
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public static Snapshot snapshot() {
        LOCK.writeLock().lock();
        try {
            return read();
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    private static Snapshot read() {
        return new Snapshot(Counters.fullGenerated(), Counters.fullLoaded(), Counters.fullResumed());
    }
}
