package mcdonate.coordinator;

import mcdonate.common.Config;
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
    static final long ORPHAN_MS = Config.getLong("orphanMs", 60_000);

    /**
     * 11-09：一个任务从派出起，心跳最多续租到这么久，过了就不再续，到期收回重派（用户真机：worker 卡死、心跳照发，续租了 5 分钟）。
     */
    static final long MAX_LEASE_TOTAL_MS = Config.getLong("maxLeaseTotalMs", 120_000);

    /**
     * 11-09：前方走廊的半宽（chunk）：tile 中心离飞行线不超过这么多的先派（kexu 烟花直线：donor 写入只有 26% 在前方；
     * 服务器只有 2 核时自己只够做最近的，donor 要先包下路线）。
     */
    static final int CORRIDOR_CHUNKS = Config.getInt("corridorChunks", 6);

    /** 11-09：找需求算过、没有截止时间的 tile（侧后方）不派。false 退回原来的（donor 空着时也派它们），对照用。 */
    static final boolean SKIP_NO_DEADLINE = !"false".equalsIgnoreCase(Config.get("skipNoDeadline", "true"));

    /**
     * 11-09：派之前问"做得完吗"时，在 importBy 前面再留多少毫秒（结果在主线程上排队等导入、找需求每秒才看一次前沿）。
     */
    static final long IMPORT_MARGIN_MS = Config.getLong("importMarginMs", 1_000);

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
        /** 导入被接受时记下来自哪个 donor 世界（按领地派任务用）；整个丢弃的是 null。 */
        String doneWorld;
        /** 派出时间（对冲用）。 */
        long leasedAt;
        /** 派出过几次（重派的结果分不清是哪一次的，不拿来估延迟，codex-audit 第十五节 V4）。 */
        int leaseCount;
        /** 对冲撤回过：之后回来的结果不导入，算浪费。 */
        boolean hedged;
        /** 7.2：预计玩家什么时候看到这个 tile（毫秒时间戳）；不知道是 Long.MAX_VALUE。 */
        long due = Long.MAX_VALUE;
        /** 7.2：找需求算过 due 没有（没算过的，比如手动派的，不按"飞过了"删）。 */
        boolean scored;
        /**
         * 11-09：预计这个 tile 的区域进服务器前沿（Frontier）的时间（毫秒时间戳）；之后交回会被判"在内存里"整个丢掉。
         * 不知道是 Long.MAX_VALUE。
         */
        long importBy = Long.MAX_VALUE;
        /** 入队时间（分段耗时用）。 */
        long enqueuedAt = -1;
        /** 什么时候被丢掉的（DROPPED；重新入队要冷却，codex-audit F6）。 */
        long droppedAt = Long.MIN_VALUE;
        /** 11-09：tile 离最近一个玩家飞行线多少个 chunk（取整；不知道是 Integer.MAX_VALUE）。派活先做走廊里的。 */
        int lateral = Integer.MAX_VALUE;
        /** 派出时离截止（importBy，不知道时 due）还有多少毫秒；不知道是 -1。 */
        long slackAtLease = -1;

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
    /** worker → 它手上租着的任务（7.3 起一个 worker 可以同时租几个）。 */
    private final Map<Integer, Set<Long>> leases = new HashMap<>();
    /** worker → 它的 donor 世界编号（从它交上来的结果里知道）。 */
    private final Map<Integer, String> workerWorlds = new HashMap<>();
    private long nextId = 1;

    /**
     * @param clock 当前时间（毫秒）
     * @param leaseMs 租约长度
     * @param maxAttempts ok=false 最多几次，到了就标 FAILED
     */
    public TaskQueue(LongSupplier clock, long leaseMs, int maxAttempts) {
        this(clock, leaseMs, maxAttempts, new Features(""));
    }

    private final Features features;

    public TaskQueue(LongSupplier clock, long leaseMs, int maxAttempts, Features features) {
        this.clock = clock;
        this.leaseMs = leaseMs;
        this.maxAttempts = maxAttempts;
        this.features = features;
    }

    /** 导入被接受：记下这个 tile 归哪个 donor 世界（按领地派任务用）。 */
    public synchronized void markImported(long taskId, String world) {
        Task t = tasks.get(taskId);
        if (t != null) {
            t.doneWorld = world;
        }
    }

    /** 一个 worker 在领地上的名字：知道它的世界就用世界，否则用它的编号。 */
    private String ownerKey(int worker) {
        String w = workerWorlds.get(worker);
        return w != null ? "world:" + w : "worker:" + worker;
    }

    /** 7.1：和 t 相邻的 tile 现在归谁（正租着的 worker、导入过的 donor 世界）。 */
    private Set<String> territoryOwners(Task t) {
        Set<String> owners = new java.util.HashSet<>();
        for (Task a : tasks.values()) {
            if (!adjacent(a, t)) {
                continue;
            }
            if (a.state == State.LEASED) {
                owners.add(ownerKey(a.worker));
            } else if (a.state == State.DONE && a.doneWorld != null) {
                owners.add("world:" + a.doneWorld);
            }
        }
        return owners;
    }

    /** worker → 在途表（11-09 codex-audit 第十五节：实测并行度）。所有租约的开始、结束都经过 leaseAdd/leaseRemove。 */
    private final Map<Integer, LoadMeter> meters = new HashMap<>();

    private void leaseAdd(int worker, long id) {
        if (leases.computeIfAbsent(worker, k -> new java.util.LinkedHashSet<>()).add(id)) {
            meters.computeIfAbsent(worker, k -> new LoadMeter()).start(clock.getAsLong());
        }
    }

    private void leaseRemove(int worker, long id) {
        Set<Long> s = leases.get(worker);
        if (s != null && s.contains(id)) {
            LoadMeter m = meters.get(worker);
            if (m != null) {
                m.end(clock.getAsLong());
            }
        }
        if (s != null && s.remove(id) && s.isEmpty()) {
            leases.remove(worker);
        }
    }

    /** 这个 worker 手上租着几个任务。 */
    public synchronized int inFlight(int worker) {
        Set<Long> s = leases.get(worker);
        return s == null ? 0 : s.size();
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
            Task old = tasks.get(existing);
            if (old.state != State.FAILED && old.state != State.DROPPED) {
                return false;
            }
        }
        // 11-09（codex-audit A3）：失败过、丢掉过又重新需要的，用新的任务编号；旧编号留着（撤回的标记不清），
        // 旧编号的结果回来时认得出来，不能拿走新任务的导入权
        t = new Task(nextId++, tx, tz);
        tasks.put(t.id, t);
        byTile.put(tileKey(tx, tz), t.id);
        t.state = State.QUEUED;
        t.hedged = false; // 撤回过、又重新需要了：之后的结果照常收
        t.scored = false;
        t.due = Long.MAX_VALUE;
        t.importBy = Long.MAX_VALUE;
        t.lateral = Integer.MAX_VALUE;
        t.enqueuedAt = clock.getAsLong();
        t.hardWorld = hardWorld;
        t.softWorker = -1;
        t.orphanSince = clock.getAsLong();
        queue.addLast(t.id);
        return true;
    }

    /** 测试用：放一个任务并软认主给指定的 worker（绕过"包一片"，用来故意制造跨 donor 的接缝）。 */
    public synchronized boolean enqueueFor(int tx, int tz, int worker) {
        if (!enqueue(tx, tz, null)) {
            return false;
        }
        tasks.get(byTile.get(tileKey(tx, tz))).softWorker = worker;
        return true;
    }

    private int hedgeCancelled;
    private int hedgeWasted;

    /**
     * 7.4 对冲：把一个租出去的任务撤回，交给服务器（标 DROPPED）。返回原来租着它的 worker（要给它发 cancel），
     * 不是 LEASED 返回 -1。之后这个任务的结果回来时不导入，算浪费。
     */
    public synchronized int hedgeToServer(long taskId) {
        Task t = tasks.get(taskId);
        if (t == null || t.state != State.LEASED) {
            return -1;
        }
        int worker = t.worker;
        leaseRemove(worker, taskId);
        t.state = State.DROPPED;
        t.droppedAt = clock.getAsLong();
        t.worker = -1;
        t.hedged = true;
        hedgeCancelled++;
        return worker;
    }

    /** 派出时间（对冲判断用）；不是 LEASED 返回 -1。 */
    /**
     * 估延迟用的派出时刻：任务正租给这个 worker、而且只派出过一次才给，否则 -1（不取样）。
     * 11-09（codex-audit 第十五节 V4）：租约过期重派给别人后旧结果回来，原来拿新租约的派出时间算旧 worker 的延迟（1 秒记成 1 毫秒）。
     */
    public synchronized long sampleLeasedAt(int worker, long taskId) {
        Task t = tasks.get(taskId);
        return t != null && t.state == State.LEASED && t.worker == worker && t.leaseCount == 1 ? t.leasedAt : -1;
    }

    /** 这个 worker 的在途表读数 {手上有活的累计毫秒, 在途数 × 时间的累计}；没派过是 {0, 0}。 */
    public synchronized long[] load(int worker) {
        LoadMeter m = meters.get(worker);
        return m == null ? new long[] {0, 0} : m.read(clock.getAsLong());
    }

    public synchronized long leasedAt(long taskId) {
        Task t = tasks.get(taskId);
        return t != null && t.state == State.LEASED ? t.leasedAt : -1;
    }

    public synchronized int hedgeCancelled() {
        return hedgeCancelled;
    }

    public synchronized int hedgeWasted() {
        return hedgeWasted;
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
        return assign(worker, 1, due -> true);
    }

    /**
     * worker 领一个任务。
     *
     * @param maxInFlight 这个 worker 最多同时租几个（7.3 按实测算力；关着时是 1）
     * @param dueOk 这个 worker 能不能在 due 之前做完（7.3：慢的 donor 只派时间宽裕的）；due 不知道的任务不问
     */
    public synchronized Optional<Assignment> assign(int worker, int maxInFlight, java.util.function.LongPredicate dueOk) {
        if (inFlight(worker) >= Math.max(1, maxInFlight) || queue.isEmpty()) {
            return Optional.empty();
        }
        boolean byDue = features.on(Features.DEADLINE);
        Task t = null;
        for (var it = queue.iterator(); it.hasNext(); ) {
            Task c = tasks.get(it.next());
            if (features.on(Features.TERRITORY) && c.hardWorld == null) {
                // 7.1 按领地派任务：挨着某个 donor 的只派给它；挨着两个 donor 的不派，交给服务器
                Set<String> owners = territoryOwners(c);
                if (owners.size() >= 2) {
                    c.state = State.DROPPED;
                    c.droppedAt = clock.getAsLong();
                    it.remove();
                    continue;
                }
                if (owners.size() == 1 && !owners.contains(ownerKey(worker))) {
                    continue;
                }
            }
            if (!eligible(c, worker)) {
                continue;
            }
            if (byDue && SKIP_NO_DEADLINE && c.scored && deadlineKey(c) == Long.MAX_VALUE) {
                continue; // 11-09：找需求算过、玩家不会去的（侧后方）不派
            }
            // 7.3 / 11-09：知道前沿时问"能不能在区域进前沿之前做完"（留余量），不知道时照旧问 due
            long limit = c.importBy != Long.MAX_VALUE ? c.importBy - IMPORT_MARGIN_MS : c.due;
            if (limit != Long.MAX_VALUE && !dueOk.test(limit)) {
                continue;
            }
            if (!byDue) {
                t = c; // 先进先出：第一个合适的
                break;
            }
            if (t == null || DISPATCH_ORDER.compare(c, t) < 0) {
                t = c; // 7.2：最急的先派（11-09：知道前沿时按 importBy，和 pruneHopeless 的顺序一致）
            }
        }
        if (t == null) {
            return Optional.empty();
        }
        queue.remove(t.id);
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
        t.leasedAt = clock.getAsLong();
        t.leaseCount++;
        long key = deadlineKey(t);
        t.slackAtLease = key == Long.MAX_VALUE ? -1 : key - t.leasedAt;
        t.worker = worker;
        t.deadline = Math.min(t.leasedAt + leaseMs, t.leasedAt + MAX_LEASE_TOTAL_MS);
        leaseAdd(worker, t.id);
        return Optional.of(new Assignment(t.id, t.tx, t.tz, leaseMs));
    }

    /** 7.2：更新 tile 的预计"被玩家看到"时间（找需求每次扫描时算）。 */
    public synchronized void setDue(int tx, int tz, long due) {
        setDue(tx, tz, due, Long.MAX_VALUE);
    }

    /** 同上，再加上预计区域进服务器前沿的时间（11-09；不知道是 Long.MAX_VALUE）。 */
    public synchronized void setDue(int tx, int tz, long due, long importBy) {
        setDue(tx, tz, due, importBy, Integer.MAX_VALUE);
    }

    /** 同上，再加上 tile 离飞行线多少个 chunk（11-09，走廊优先；不知道是 Integer.MAX_VALUE）。 */
    public synchronized void setDue(int tx, int tz, long due, long importBy, int lateral) {
        Long id = byTile.get(tileKey(tx, tz));
        if (id == null) {
            return;
        }
        Task t = tasks.get(id);
        if (t.state == State.QUEUED || t.state == State.LEASED) {
            t.due = due;
            t.importBy = importBy;
            t.lateral = lateral;
            t.scored = true;
        }
    }

    /** 派出时离截止还有多少毫秒（截止不知道、不认识的任务是 -1）。 */
    public synchronized long slackAtLease(long taskId) {
        Task t = tasks.get(taskId);
        return t == null ? -1 : t.slackAtLease;
    }

    /** 丢掉的 tile 过多久才能重新入队（11-09，codex-audit F6；免得剪了又放、放了又剪）。 */
    static final long REQUEUE_COOLDOWN_MS = Config.getLong("requeueCooldownMs", 5_000);

    /**
     * 找需求能不能把这个 tile（重新）放进队列：没有任务，或者任务被丢掉（DROPPED）已经过了冷却。
     * 11-09（codex-audit F6）：原来丢掉的永远不再放回去，玩家转回来时需求重新成立也不复活。
     */
    public synchronized boolean canEnqueueAgain(int tx, int tz) {
        Long id = byTile.get(tileKey(tx, tz));
        if (id == null) {
            return true;
        }
        Task t = tasks.get(id);
        return t.state == State.DROPPED && clock.getAsLong() - t.droppedAt >= REQUEUE_COOLDOWN_MS;
    }

    /**
     * 算进找需求队列上限的排队任务数：有截止时间的，加上还没算过的。找需求算过、玩家不会去的（侧后方）不算——
     * 它们不派（skipNoDeadline），原来占满上限后前方的新 tile 进不来（11-09）。
     */
    public synchronized int queuedCountingTowardCap() {
        int n = 0;
        for (long id : queue) {
            Task t = tasks.get(id);
            if (!t.scored || deadlineKey(t) != Long.MAX_VALUE) {
                n++;
            }
        }
        return n;
    }

    /** 入队时间（毫秒时间戳），不认识的任务是 -1。 */
    public synchronized long enqueuedAt(long taskId) {
        Task t = tasks.get(taskId);
        return t == null ? -1 : t.enqueuedAt;
    }

    public record Cancelled(long taskId, int worker) {}

    /** {@link #dropPassed} 的结果：删掉的排队任务、撤回的已派任务（要给 worker 发 cancel）。 */
    public record Stale(List<Long> dropped, List<Cancelled> cancelled) {}

    public interface TileFilter {
        boolean test(int tx, int tz);
    }

    private int staleDropped;
    private int staleCancelled;

    /**
     * 7.2（11-09 补的漏洞）：开 deadline 时，玩家已经飞过（找需求算过、没有将来的到达时间，due 是无穷大）、
     * 又不在任何玩家当前需求范围里的 tile：排队的删掉，派出去的撤回（之后回来的结果作废）。
     * 计划本来就写了"玩家已经到了的任务直接丢掉"，原来只丢了 due 已过的，飞过的一直留着，别的做完了照样派
     * （f3 实测几轮 donor 写入的 chunk 几乎全在玩家身后）。开关关着（v0，先来先服务）不动，它是对照组。
     */
    public synchronized Stale dropPassed(TileFilter inDemand) {
        List<Long> dropped = new ArrayList<>();
        List<Cancelled> cancelled = new ArrayList<>();
        if (!features.on(Features.DEADLINE)) {
            return new Stale(dropped, cancelled);
        }
        for (Task t : tasks.values()) {
            if (!t.scored || t.due != Long.MAX_VALUE || inDemand.test(t.tx, t.tz)) {
                continue;
            }
            if (t.state == State.QUEUED) {
                queue.remove(t.id);
                t.state = State.DROPPED;
                t.droppedAt = clock.getAsLong();
                dropped.add(t.id);
                staleDropped++;
            } else if (t.state == State.LEASED) {
                int w = t.worker;
                leaseRemove(w, t.id);
                t.state = State.DROPPED;
                t.droppedAt = clock.getAsLong();
                t.worker = -1;
                t.hedged = true; // 之后回来的结果作废
                cancelled.add(new Cancelled(t.id, w));
                staleCancelled++;
            }
        }
        dropped.sort(null);
        return new Stale(dropped, cancelled);
    }

    /**
     * 清空（第 7 步对比每轮开始前，/donor reset）：排队的、在租的、做完的都忘掉，领地和归属不带进下一轮。
     * 返回在租的（要给 worker 发 cancel）；之后回来的结果不认识，丢掉。worker 认的 donor 世界和累计计数留着。
     */
    private long epoch;

    /** 代次：每次 reset 加一。交给主线程排着导入的结果在导入前比一下，变了就不导（11-09，codex-audit A2）。 */
    public synchronized long epoch() {
        return epoch;
    }

    public synchronized List<Cancelled> reset() {
        epoch++;
        List<Cancelled> out = new ArrayList<>();
        for (Task t : tasks.values()) {
            if (t.state == State.LEASED) {
                out.add(new Cancelled(t.id, t.worker));
            }
        }
        out.sort(java.util.Comparator.comparingLong(Cancelled::taskId));
        tasks.clear();
        byTile.clear();
        queue.clear();
        long now = clock.getAsLong();
        leases.forEach((w, ids) -> ids.forEach(id -> meters.get(w).end(now))); // 在途表的累计量留着，只把在途数归零
        leases.clear();
        return out;
    }

    public synchronized String staleJson() {
        return "{\"dropped\":" + staleDropped + ",\"cancelled\":" + staleCancelled + ",\"import_late\":" + importLate + ",\"hopeless\":" + hopeless + "}";
    }

    private int importLate;
    private int hopeless;

    /** 排序和"来得及吗"用的截止时间：知道前沿时用 importBy，否则用 due（都不知道是 Long.MAX_VALUE）。 */
    private static long deadlineKey(Task t) {
        return t.importBy != Long.MAX_VALUE ? t.importBy : t.due;
    }

    /** 派活的先后：走廊里的在前（0），其余（1）；同一档按截止时间。pruneHopeless 按同样的顺序估排队。 */
    private static final java.util.Comparator<Task> DISPATCH_ORDER = java.util.Comparator
            .<Task>comparingInt(t -> t.lateral <= CORRIDOR_CHUNKS ? 0 : 1)
            .thenComparingLong(TaskQueue::deadlineKey)
            .thenComparingLong(t -> t.id);

    /**
     * 11-09：排队太久的现在就丢掉，交给服务器（f2 实测入队→派出 p50 4～10 秒、p90 22～43 秒，派出→交回约 1.4 秒）。
     * 排队的任务按截止时间从早到晚排（和 assign 的顺序一致），第 i 个（只数留下的）大约在
     * now + (i + 在租数 / 2) / 总吞吐 开始，再过 tileMs 做完；做完晚于截止时间 − 余量的丢掉。
     * 截止时间不知道的不丢。只在开 deadline 时动；总吞吐不大于 0（没有 donor）时估不了，不动。
     *
     * @param tilesPerMs 所有在线、没暂停的 donor 加起来每毫秒做几个 tile（按实测耗时）
     * @param tileMs 一个 tile 从派出到交回的毫秒数（按实测）
     */
    public synchronized List<Long> pruneHopeless(double tilesPerMs, long tileMs) {
        if (tilesPerMs <= 0) {
            return new ArrayList<>();
        }
        int leased = (int) tasks.values().stream().filter(t -> t.state == State.LEASED).count();
        return pruneHopeless(List.of(new DonorEstimate(tileMs, tilesPerMs, leased)));
    }

    /** 一个 donor 的估计：延迟（毫秒）、速率（个/毫秒）、手上在做几个。 */
    public record DonorEstimate(long latencyMs, double ratePerMs, int inFlight, int slots) {
        /** 不知道名额数：按 1 个（一个一个做）。 */
        public DonorEstimate(long latencyMs, double ratePerMs, int inFlight) {
            this(latencyMs, ratePerMs, inFlight, 1);
        }
    }

    /**
     * 11-09（codex-audit G5）：按每个 donor 分别估。排队的任务按派活顺序，依次假设给能最早做完的那个 donor
     * （DonorThroughput.predictFinish，前面的个数 = 它手上的 + 已经假设给它的）；最早也做不完的才剪。
     * 原来把所有 donor 的在途加在一起、按总速率排队，慢 donor 手上的会让空着的快 donor 能做完的任务被剪掉。
     */
    public synchronized List<Long> pruneHopeless(List<DonorEstimate> donors) {
        List<Long> dropped = new ArrayList<>();
        if (!features.on(Features.DEADLINE) || donors.isEmpty()) {
            return dropped;
        }
        long now = clock.getAsLong();
        int[] ahead = new int[donors.size()];
        for (int i = 0; i < ahead.length; i++) {
            ahead[i] = donors.get(i).inFlight();
        }
        List<Task> order = new ArrayList<>();
        for (long id : queue) {
            Task t = tasks.get(id);
            if (deadlineKey(t) != Long.MAX_VALUE) {
                order.add(t);
            }
        }
        order.sort(DISPATCH_ORDER);
        for (Task t : order) {
            // 和派发一样的预测（codex-audit F2），取最早做完的 donor
            int best = -1;
            double finish = Double.POSITIVE_INFINITY;
            for (int i = 0; i < ahead.length; i++) {
                DonorEstimate d = donors.get(i);
                if (d.ratePerMs() <= 0) {
                    continue;
                }
                double f = DonorThroughput.predictFinish(now, d.latencyMs(), d.ratePerMs(), ahead[i], d.slots());
                if (f < finish) {
                    finish = f;
                    best = i;
                }
            }
            if (best < 0) {
                break; // 估不了（没有速率），不剪
            }
            long limit = t.importBy != Long.MAX_VALUE ? t.importBy - IMPORT_MARGIN_MS : t.due;
            if (finish > limit) {
                queue.remove(t.id);
                t.state = State.DROPPED;
                t.droppedAt = now;
                dropped.add(t.id);
                hopeless++;
            } else {
                ahead[best]++;
            }
        }
        dropped.sort(null);
        return dropped;
    }

    /**
     * 7.2：还在排队、玩家已经看到了（due 已过）的任务丢掉，交给服务器。
     * 11-09：区域已经进了服务器前沿（importBy 已过）的也丢掉，交回也会被整个丢弃。返回丢掉的。
     */
    public synchronized List<Long> dropLate() {
        long now = clock.getAsLong();
        List<Long> dropped = new ArrayList<>();
        for (var it = queue.iterator(); it.hasNext(); ) {
            Task t = tasks.get(it.next());
            if (t.due >= now && t.importBy < now) {
                importLate++;
            }
            if (t.due < now || t.importBy < now) {
                t.state = State.DROPPED;
                t.droppedAt = now;
                it.remove();
                dropped.add(t.id);
            }
        }
        return dropped;
    }

    /** 一个租出去、知道 due 的任务（7.4 对冲判断用）。 */
    public record Lease(long taskId, int worker, long leasedAt, long due) {}

    /** 这个任务正租着的话，租给谁、什么时候派的（7.6 等 donor 用）。 */
    public synchronized Optional<Lease> lease(long taskId) {
        Task t = tasks.get(taskId);
        return t != null && t.state == State.LEASED ? Optional.of(new Lease(t.id, t.worker, t.leasedAt, t.due)) : Optional.empty();
    }

    public synchronized List<Lease> leasesWithDue() {
        List<Lease> out = new ArrayList<>();
        for (Task t : tasks.values()) {
            if (t.state == State.LEASED && t.due != Long.MAX_VALUE) {
                out.add(new Lease(t.id, t.worker, t.leasedAt, t.due));
            }
        }
        return out;
    }

    /** worker 的心跳带着正在做的任务：是租给它的就续租。 */
    public synchronized void heartbeat(int worker, long taskId) {
        Task t = tasks.get(taskId);
        long now = clock.getAsLong();
        if (t != null && t.state == State.LEASED && t.worker == worker && now - t.leasedAt < MAX_LEASE_TOTAL_MS) {
            t.deadline = Math.min(now + leaseMs, t.leasedAt + MAX_LEASE_TOTAL_MS);
        }
    }

    /** 收回到期的租约，任务回到队首。返回收回的任务。由定时线程调。 */
    public synchronized List<Long> expire() {
        return expireLeases().stream().map(Cancelled::taskId).toList();
    }

    /**
     * 收回到期的租约。11-09（codex-audit A4）：收回也算一次失败（卡死的 worker 心跳照发，不能无限拿回同一个任务），
     * 到 maxAttempts 次就 FAILED；返回收回的（任务、原来的 worker），调用方给原 worker 发 cancel。由定时线程调。
     */
    public synchronized List<Cancelled> expireLeases() {
        long now = clock.getAsLong();
        List<Task> expired = new ArrayList<>();
        for (Task t : tasks.values()) {
            if (t.state == State.LEASED && t.deadline < now) {
                expired.add(t);
            }
        }
        expired.sort(java.util.Comparator.comparingLong(t -> t.id));
        List<Cancelled> out = new ArrayList<>();
        for (int i = expired.size() - 1; i >= 0; i--) {
            Task t = expired.get(i);
            out.add(0, new Cancelled(t.id, t.worker));
            releaseSoft(t.worker);
            t.failures++;
            if (t.failures >= maxAttempts) {
                leaseRemove(t.worker, t.id);
                t.state = State.FAILED;
                t.worker = -1;
            } else {
                // 同一个编号回到队首（第 5 步 2.4：租约到期后原 worker 迟到的成功结果照样收）。原 worker 收到了 Cancel(这个编号)，
                // 再派给它时 worker 收到 Task 会清掉撤销标记（codex-audit G3，在 WorkerClient.onTask）
                backToHead(t);
            }
        }
        return out;
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
                t.droppedAt = now;
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
        List<Long> ids = new ArrayList<>(leases.getOrDefault(worker, Set.of()));
        for (int i = ids.size() - 1; i >= 0; i--) {
            backToHead(tasks.get(ids.get(i)));
        }
        return ids;
    }

    private void backToHead(Task t) {
        leaseRemove(t.worker, t.id);
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
        // 这个 worker 交了结果，这个任务不再占它的名额
        leaseRemove(worker, taskId);
        if (!Long.valueOf(taskId).equals(byTile.get(tileKey(t.tx, t.tz)))) {
            // 11-09（A3）：这个 tile 已经有了新的任务（撤回或丢掉以后又需要了）：旧编号的结果不导入
            return discard("任务 " + taskId + " 已被新的任务取代");
        }
        if (t.hedged) {
            // 对冲撤回后才回来：服务器已经接手，不导入，算浪费
            if (ok) {
                hedgeWasted++;
            }
            return discard("任务 " + taskId + " 已对冲交给服务器，结果作废");
        }
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
                leaseRemove(other, taskId);
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
