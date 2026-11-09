package mcdonate.worker;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

/**
 * worker 主线程上一次只生成一个 tile。
 *
 * <p>11-09 f3 实测：开 7.3（一个 donor 同时领几个任务）时，主线程在 getChunk 的 managedBlock 里等 chunk，
 * 等的时候会执行服务器队列里排着的任务——下一个 tile 的生成就嵌套在上一个里面跑，越嵌越深，60 秒看门狗把 worker 杀掉。
 * 这里自己排队：任务交给 executor（主线程）时只放一个"取下一个"的动作；正在做的时候被嵌套调用就直接返回，
 * 做完（不管成败）再排一次。
 */
final class OneAtATime<T> {
    interface Job<T> {
        void run(T item);
    }

    private record Item<T>(T value, Job<T> job) {}

    private final Executor executor;
    private final Queue<Item<T>> pending = new ArrayDeque<>();
    private boolean busy;

    OneAtATime(Executor executor) {
        this.executor = executor;
    }

    synchronized int pending() {
        return pending.size();
    }

    /** 排着的（还没开始的）项，按先后。 */
    synchronized java.util.List<T> pendingValues() {
        java.util.List<T> out = new java.util.ArrayList<>();
        for (Item<T> i : pending) {
            out.add(i.value());
        }
        return out;
    }

    void submit(T value, Job<T> job) {
        synchronized (this) {
            pending.add(new Item<>(value, job));
        }
        executor.execute(this::runNext);
    }

    private void runNext() {
        Item<T> next;
        synchronized (this) {
            if (busy) {
                return; // 嵌套进来的（主线程等 chunk 时执行了排着的任务）：做完当前这个再说
            }
            next = pending.poll();
            if (next == null) {
                return;
            }
            busy = true;
        }
        try {
            next.job().run(next.value());
        } finally {
            boolean more;
            synchronized (this) {
                busy = false;
                more = !pending.isEmpty();
            }
            if (more) {
                executor.execute(this::runNext);
            }
        }
    }
}
