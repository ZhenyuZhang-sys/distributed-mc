package mcdonate.coordinator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 任务队列和租约（第 5 步）。纯逻辑：不碰网络、不碰游戏，时间用注入的时钟。
 *
 * <p>每个任务是一个 tile，状态：QUEUED → LEASED(worker, 到期时间) → DONE 或 FAILED。
 * 收到结果怎么处理见 docs/step5-plan.md 的 2.4 表，{@link #onResult} 按表返回决定。
 * 收到成功结果时立刻标 DONE，再交主线程导入：两份结果几乎同时到，也只导入一次。
 *
 * <p>认主（第 6 步，docs/step6-plan.md 0.3）：
 * <ul>
 *   <li>硬认主：区域里已有某个 donor 世界导入的 chunk，任务只派给这个世界的 donor；
 *       这个世界 60 秒没有 donor 连着，任务丢掉（DROPPED），交给服务器生成。
 *   <li>软认主：派出一个不认主的任务时，队列里和它相邻（含对角）的不认主任务归同一个 worker，
 *       相邻的 tile 尽量由同一个 donor 生成（"包一片"）。这个 worker 断线或租约到期就解除。
 * </ul>
 * 认主只为效率；正确性靠导入时的检查（RegionRules），派错了也只是被整个丢弃。
 *
 * <p>网络线程和定时线程都会调，所以方法都加锁。不持久化：coordinator 重启后队列清空。
 */
public final class TaskQueue {
    public enum State { QUEUED, LEASED, DONE, FAILED, DROPPED }

    /** 硬认主的世界多久没有 donor 连着就丢掉任务。 */
    static final long ORPHAN_MS = 60_000;

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
        /** 硬认主的 donor 世界，没有是 null。 */
        String hardWorld;
        /** 软认主的 worker，没有是 -1。 */
        int softWorker = -1;
        /** 硬认主的世界从什么时候起没有 donor 连着。 */
        long orphanSince;

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
    /** worker → 它的 donor 世界编号（从它交上来的结果里知道）。 */
    private final Map<Integer, String> workerWorlds = new HashMap<>();
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

    /** 放一个不认主的任务。 */
    public synchronized boolean enqueue(int tx, int tz) {
        return enqueue(tx, tz, null);
    }

    /**
     * 放一个任务，hardWorld 不为 null 时硬认主这个 donor 世界。
     * 同一个 tile 已在队列里、租出去了或做完了就不放，返回 false。失败过、丢掉过的可以重新放。
     */
    public synchronized boolean enqueue(int tx, int tz, String hardWorld) {
        Long existing = byTile.get(tileKey(tx, tz));
        Task t;
        if (existing != null) {
            t = tasks.get(existing);
            if (t.state != State.FAILED && t.state != State.DROPPED) {
                return false;
            }
            t.failures = 0;
        } else {
            t = new Task(nextId++, tx, tz);
            tasks.put(t.id, t);
            byTile.put(tileKey(tx, tz), t.id);
        }
        t.state = State.QUEUED;
        t.hardWorld = hardWorld;
        t.softWorker = -1;
        t.orphanSince = clock.getAsLong();
        queue.addLast(t.id);
        return true;
    }

    /** 这个 tile 的任务编号，没有返回 -1。 */
    public synchronized long find(int tx, int tz) {
        return byTile.getOrDefault(tileKey(tx, tz), -1L);
    }

    /** 记下 worker 的 donor 世界（收到它的结果时调）。 */
    public synchronized void setWorkerWorld(int worker, String world) {
        workerWorlds.put(worker, world);
    }

    private boolean eligible(Task t, int worker) {
        if (t.hardWorld != null && !t.hardWorld.equals(workerWorlds.get(worker))) {
            return false;
        }
        return t.softWorker == -1 || t.softWorker == worker;
    }

    private static boolean adjacent(Task a, Task b) {
        return a != b && Math.abs(a.tx - b.tx) <= 1 && Math.abs(a.tz - b.tz) <= 1;
    }

    /** 空闲的 worker 领队首的任务。手上已有任务或队列空了返回空。 */
    public synchronized Optional<Assignment> assign(int worker) {
        if (leases.containsKey(worker) || queue.isEmpty()) {
            return Optional.empty();
        }
        Task t = null;
        for (var it = queue.iterator(); it.hasNext(); ) {
            Task c = tasks.get(it.next());
            if (eligible(c, worker)) {
                t = c;
                it.remove();
                break;
            }
        }
        if (t == null) {
            return Optional.empty();
        }
        if (t.hardWorld == null) {
            // 包一片：队列里相邻的不认主任务归这个 worker
            for (long id : queue) {
                Task c = tasks.get(id);
                if (c.hardWorld == null && c.softWorker == -1 && adjacent(c, t)) {
                    c.softWorker = worker;
                }
            }
        }
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
            Task t = tasks.get(expired.get(i));
            releaseSoft(t.worker);
            backToHead(t);
        }
        return expired;
    }

    /** 解除这个 worker 的软认主。 */
    private void releaseSoft(int worker) {
        for (Task t : tasks.values()) {
            if (t.softWorker == worker) {
                t.softWorker = -1;
            }
        }
    }

    /** 硬认主的世界超过 60 秒没有 donor 连着：任务丢掉，交给服务器。由定时线程调。 */
    public synchronized List<Long> dropOrphans(Set<String> connectedWorlds) {
        long now = clock.getAsLong();
        List<Long> dropped = new ArrayList<>();
        for (Task t : tasks.values()) {
            if (t.state != State.QUEUED || t.hardWorld == null) {
                continue;
            }
            if (connectedWorlds.contains(t.hardWorld)) {
                t.orphanSince = now;
            } else if (now - t.orphanSince > ORPHAN_MS) {
                t.state = State.DROPPED;
                queue.remove(t.id);
                dropped.add(t.id);
            }
        }
        dropped.sort(null);
        return dropped;
    }

    /** worker 断线：它手上的任务立刻回到队首。返回收回的任务。 */
    public synchronized List<Long> workerGone(int worker) {
        releaseSoft(worker);
        workerWorlds.remove(worker);
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
            case DROPPED:
                // 失败几次后、或丢掉后又来了一份成功的：收下（导入时照样按区域规则检查）
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
                + ",\"done\":" + count(State.DONE) + ",\"failed\":" + count(State.FAILED)
                + ",\"dropped\":" + count(State.DROPPED) + "}";
    }
}
