package mcdonate.coordinator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * 任务队列和租约（第 5 步）。纯逻辑：不碰网络、不碰游戏，时间用注入的时钟。
 *
 * <p>每个任务是一个 tile，状态：QUEUED → LEASED(worker, 到期时间) → DONE 或 FAILED。
 * 收到结果怎么处理见 docs/step5-plan.md 的 2.4 表，{@link #onResult} 按表返回决定。
 * 收到成功结果时立刻标 DONE，再交主线程导入：两份结果几乎同时到，也只导入一次。
 *
 * <p>网络线程和定时线程都会调，所以方法都加锁。不持久化：coordinator 重启后队列清空。
 */
public final class TaskQueue {
    public enum State { QUEUED, LEASED, DONE, FAILED }

    /** 收到结果后要做什么。 */
    public enum Action { IMPORT, REQUEUE, FAIL, DISCARD }

    /** 发给 worker 的任务。 */
    public record Assignment(long taskId, int tx, int tz, long leaseMs) {}

    /**
     * 对一个结果的决定。
     *
     * @param cancelWorker IMPORT 时，如果任务当时租给了别的 worker，要给它发 cancel
     * @param reason 给日志看的
     */
    public record Decision(Action action, Optional<Integer> cancelWorker, String reason) {}

    private static final class Task {
        final long id;
        final int tx;
        final int tz;
        State state = State.QUEUED;
        int worker = -1;
        long deadline;
        int failures;

        Task(long id, int tx, int tz) {
            this.id = id;
            this.tx = tx;
            this.tz = tz;
        }
    }

    private final LongSupplier clock;
    private final long leaseMs;
    private final int maxAttempts;
    private final Map<Long, Task> tasks = new HashMap<>();
    private final Map<Long, Long> byTile = new HashMap<>();
    private final Deque<Long> queue = new ArrayDeque<>();
    /** worker → 它手上租着的任务。 */
    private final Map<Integer, Long> leases = new HashMap<>();
    private long nextId = 1;

    /**
     * @param clock 当前时间（毫秒）
     * @param leaseMs 租约长度
     * @param maxAttempts ok=false 最多几次，到了就标 FAILED
     */
    public TaskQueue(LongSupplier clock, long leaseMs, int maxAttempts) {
        this.clock = clock;
        this.leaseMs = leaseMs;
        this.maxAttempts = maxAttempts;
    }

    private static long tileKey(int tx, int tz) {
        return ((long) tx << 32) ^ (tz & 0xffffffffL);
    }

    /** 放一个任务。同一个 tile 已在队列里、租出去了或做完了就不放，返回 false。失败过的可以重新放。 */
    public synchronized boolean enqueue(int tx, int tz) {
        Long existing = byTile.get(tileKey(tx, tz));
        if (existing != null) {
            Task t = tasks.get(existing);
            if (t.state != State.FAILED) {
                return false;
            }
            t.state = State.QUEUED;
            t.failures = 0;
            queue.addLast(t.id);
            return true;
        }
        Task t = new Task(nextId++, tx, tz);
        tasks.put(t.id, t);
        byTile.put(tileKey(tx, tz), t.id);
        queue.addLast(t.id);
        return true;
    }

    /** 空闲的 worker 领队首的任务。手上已有任务或队列空了返回空。 */
    public synchronized Optional<Assignment> assign(int worker) {
        if (leases.containsKey(worker) || queue.isEmpty()) {
            return Optional.empty();
        }
        Task t = tasks.get(queue.pollFirst());
        t.state = State.LEASED;
        t.worker = worker;
        t.deadline = clock.getAsLong() + leaseMs;
        leases.put(worker, t.id);
        return Optional.of(new Assignment(t.id, t.tx, t.tz, leaseMs));
    }

    /** worker 的心跳带着正在做的任务：是租给它的就续租。 */
    public synchronized void heartbeat(int worker, long taskId) {
        Task t = tasks.get(taskId);
        if (t != null && t.state == State.LEASED && t.worker == worker) {
            t.deadline = clock.getAsLong() + leaseMs;
        }
    }

    /** 收回到期的租约，任务回到队首。返回收回的任务。由定时线程调。 */
    public synchronized List<Long> expire() {
        long now = clock.getAsLong();
        List<Long> expired = new ArrayList<>();
        for (Task t : tasks.values()) {
            if (t.state == State.LEASED && t.deadline < now) {
                expired.add(t.id);
            }
        }
        expired.sort(null);
        for (int i = expired.size() - 1; i >= 0; i--) {
            backToHead(tasks.get(expired.get(i)));
        }
        return expired;
    }

    /** worker 断线：它手上的任务立刻回到队首。返回收回的任务。 */
    public synchronized List<Long> workerGone(int worker) {
        Long id = leases.get(worker);
        if (id == null) {
            return List.of();
        }
        backToHead(tasks.get(id));
        return List.of(id);
    }

    private void backToHead(Task t) {
        leases.remove(t.worker, t.id);
        t.state = State.QUEUED;
        t.worker = -1;
        queue.addFirst(t.id);
    }

    /** 收到一个结果，按 2.4 表决定怎么处理。 */
    public synchronized Decision onResult(int worker, long taskId, boolean ok) {
        Task t = tasks.get(taskId);
        if (t == null) {
            return discard("不认识的任务 " + taskId);
        }
        // 这个 worker 交了结果，不管是不是它的，它都空出来了
        leases.remove(worker, taskId);
        switch (t.state) {
            case DONE:
                return discard("重复结果：任务 " + taskId + " 已经做完");
            case LEASED:
                if (t.worker == worker) {
                    return ok ? done(t, Optional.empty(), "完成") : failure(t);
                }
                if (!ok) {
                    return discard("任务 " + taskId + " 已经重发给 worker " + t.worker + "，忽略旧的失败");
                }
                int other = t.worker;
                leases.remove(other, taskId);
                return done(t, Optional.of(other), "过期结果先到，撤掉 worker " + other);
            case QUEUED:
                if (!ok) {
                    return discard("任务 " + taskId + " 已经回到队列，忽略旧的失败");
                }
                queue.remove(taskId);
                return done(t, Optional.empty(), "过期结果先到，从队列里拿掉");
            case FAILED:
                // 失败几次后又来了一份成功的：收下
                return ok ? done(t, Optional.empty(), "失败后又收到成功结果") : discard("任务 " + taskId + " 已标失败");
            default:
                throw new IllegalStateException(t.state.toString());
        }
    }

    private Decision done(Task t, Optional<Integer> cancel, String reason) {
        t.state = State.DONE;
        t.worker = -1;
        return new Decision(Action.IMPORT, cancel, reason);
    }

    private Decision failure(Task t) {
        t.failures++;
        t.worker = -1;
        if (t.failures >= maxAttempts) {
            t.state = State.FAILED;
            return new Decision(Action.FAIL, Optional.empty(), "失败 " + t.failures + " 次，放弃");
        }
        t.state = State.QUEUED;
        queue.addLast(t.id);
        return new Decision(Action.REQUEUE, Optional.empty(), "失败 " + t.failures + " 次，重新排队");
    }

    private static Decision discard(String reason) {
        return new Decision(Action.DISCARD, Optional.empty(), reason);
    }

    /** 主线程导入出错：任务回到队首重做。 */
    public synchronized void importFailed(long taskId) {
        Task t = tasks.get(taskId);
        if (t != null && t.state == State.DONE) {
            t.state = State.QUEUED;
            queue.addFirst(t.id);
        }
    }

    public synchronized State state(long taskId) {
        return tasks.get(taskId).state;
    }

    public synchronized int count(State state) {
        return (int) tasks.values().stream().filter(t -> t.state == state).count();
    }

    /** {"queued":1,"leased":1,"done":0,"failed":0} */
    public synchronized String countsJson() {
        return "{\"queued\":" + count(State.QUEUED) + ",\"leased\":" + count(State.LEASED)
                + ",\"done\":" + count(State.DONE) + ",\"failed\":" + count(State.FAILED) + "}";
    }
}
