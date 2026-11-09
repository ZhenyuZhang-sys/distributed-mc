package mcdonate.common.gen;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import mcdonate.common.Provenance;

/**
 * chunk 变成完整状态时（docs/overlay-plan.md 2.1）：计数和来源事件一起发。
 *
 * <p>原来是 bench 的 Generation；挪进 common（coordinator、bench、worker、客户端都内嵌、Loader 只加载一份），
 * 部署时没有 bench 也有 VANILLA、RESUMED、LOADED 事件，游戏内来源上色才看得到"服务器自己生成"和"导入后接着做完"。
 * 钩子在 common 的 ChunkStatusTasksFullStatusMixin（ChunkStatusTasks.full 开头，只算主世界）。
 *
 * <p>上报时拿读锁，计数（bench 注册的 {@link Counter}）和发事件在同一把读锁里；{@link #listen}、{@link #unlisten} 拿写锁读快照。
 * 所以一个监听器在 listen 和 unlisten 之间收到的每类事件数，严格等于两个快照之间对应计数的增量（第 4 步 4.3 的保证）。
 */
public final class FullStatus {
    /** 从头生成 / 读盘的成品 / 读盘的半成品被接着做完（donor 导入的 tile 就是这种）。 */
    public enum Kind { GENERATED, LOADED, RESUMED }

    @FunctionalInterface
    public interface Counter { void on(Kind kind); }

    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();
    private static volatile Counter counter = k -> {};

    private FullStatus() {}

    /** bench 注册计数（没有 bench 时不计数，只发事件）。 */
    public static void setCounter(Counter c) {
        counter = c;
    }

    public static void onFull(Kind kind, int cx, int cz) {
        LOCK.readLock().lock();
        try {
            counter.on(kind);
            Provenance.report(cx, cz, switch (kind) {
                case GENERATED -> Provenance.Kind.VANILLA;
                case RESUMED -> Provenance.Kind.RESUMED;
                case LOADED -> Provenance.Kind.LOADED;
            }, -1);
        } finally {
            LOCK.readLock().unlock();
        }
    }

    /** 开始监听，同一把写锁里读快照（snapshot 读计数）。 */
    public static <T> T listen(Provenance.Listener l, Supplier<T> snapshot) {
        LOCK.writeLock().lock();
        try {
            Provenance.addListener(l);
            return snapshot.get();
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public static <T> T unlisten(Provenance.Listener l, Supplier<T> snapshot) {
        LOCK.writeLock().lock();
        try {
            Provenance.removeListener(l);
            return snapshot.get();
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    /** 不改监听，只在写锁里读一次快照。 */
    public static <T> T snapshot(Supplier<T> snapshot) {
        LOCK.writeLock().lock();
        try {
            return snapshot.get();
        } finally {
            LOCK.writeLock().unlock();
        }
    }
}
