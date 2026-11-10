package mcdonate.coordinator;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import mcdonate.common.Config;
import net.minecraft.Util;
import mcdonate.common.TilePos;
import mcdonate.coordinator.RegionRules.ChunkInfo;
import mcdonate.coordinator.mixin.ChunkMapAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * 找需求（第 6 步 6.3）：每秒在主线程上看一次主世界的玩家，把周围符合条件的 tile 放进队列。
 *
 * <p>范围：离玩家 inner 圈以外、outer 圈以内的 tile。
 * <ul>
 *   <li>inner = 视距 + ChunkLevel.RADIUS_AROUND_FULL_CHUNK + 1：服务器为了把视距内的 chunk 做成成品，
 *       会把它们的依赖（邻居的各个中间阶段，最外层只到结构起点）一直占在内存里，约往外 11 圈。
 *       按接缝规则，区域碰到内存里的 chunk 就不能交给 donor（它之后会被服务器存盘、覆盖导入的数据）。
 *       再加 1 是 tile 的区域比 tile 多一圈。
 *   <li>outer = inner + 提前量（系统属性 mcdonate.demandLookahead，默认 8 圈）。
 * </ul>
 * 最早按"视距 + 12"写死，没考虑原版的依赖范围，tile 回来时区域里的 chunk 已经被服务器加载，全部被丢弃。
 *
 * <p>查存档不卡主线程：第一次查某个 chunk 时在存档读写线程上异步读，结果进缓存；还没查到的当"不确定"，
 * 这一轮不入队。服务器存盘（ChunkMapSaveMixin）和导入时更新缓存。缓存只在主线程上读写，
 * 别的线程来的变化先排进 EVENTS，扫描开始时并进来。
 */
public final class DemandScanner {
    static final int QUEUE_CAP = Config.getInt("demandQueueCap", 256);
    /** inner 在"视距 + 原版占内存的圈数"之外再留几圈。 */
    /** 已知含有服务器 chunk 的 tile（键是 tile 坐标打包成 long），只在主线程上用。 */
    private final LongOpenHashSet serverTiles = new LongOpenHashSet();
    /** 每次扫描最多发起几次读存档：刚开始时玩家周围几千个 chunk 都不知道状态，一次全发会让那一次扫描很慢。 */
    static final int READS_PER_SCAN = Config.getInt("demandReadsPerScan", 256);
    private int readsLeft;
    /** 每次扫描最多占主线程多久；没查完的 tile 下一秒再查（按离玩家远近，近的先查）。 */
    static final long BUDGET_NANOS = Config.getLong("demandScanBudgetMicros", 500) * 1000;
    private final LongArrayList toRead = new LongArrayList();
    static final int INNER_MARGIN = Config.getInt("demandInnerMargin", 1);

    /** 11-09：开 deadline 时实测服务器前沿（Frontier），按它算 importBy，来不及导入的不派。false 退回只按 due。 */
    static final boolean IMPORT_FRONTIER = !"false".equalsIgnoreCase(Config.get("importFrontier", "true"));
    private static final int PENDING = Integer.MIN_VALUE;
    private static final int ABSENT = -1;
    private static final int UNKNOWN = Integer.MIN_VALUE + 1;
    /** 事件里表示"忘掉这个 chunk，下次重新查" */
    private static final int FORGET = Integer.MIN_VALUE + 2;

    /**
     * 主世界里查过的 chunk：存档里的状态序号，ABSENT 不存在，PENDING 正在查。只在主线程上用（不装箱、不加锁）。
     * 读盘线程的结果、存盘和导入的变化都先进 EVENTS，扫描开始时在主线程上并进来。
     */
    private static final Long2IntOpenHashMap DISK = new Long2IntOpenHashMap();
    /** {chunk, 状态}；读盘结果只在当时还是 PENDING 时生效（第三个元素为 1）。 */
    private static final ConcurrentLinkedQueue<long[]> EVENTS = new ConcurrentLinkedQueue<>();

    static {
        DISK.defaultReturnValue(UNKNOWN);
    }

    private final MinecraftServer server;
    private final TaskQueue tasks;
    private final int lookahead = Config.getInt("demandLookahead", 8);
    long scans;
    long totalNanos;
    long maxNanos;
    long totalCpuNanos;
    private static final java.lang.management.ThreadMXBean THREADS = java.lang.management.ManagementFactory.getThreadMXBean();
    /** 最近 64 次扫描：[墙钟微秒, 主线程 CPU 微秒, 发起的读存档次数]，排查耗时尖峰用。 */
    private final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();

    DemandScanner(MinecraftServer server, TaskQueue tasks) {
        this(server, tasks, new Features(""));
    }

    DemandScanner(MinecraftServer server, TaskQueue tasks, Features features) {
        this.server = server;
        this.tasks = tasks;
        this.features = features;
        SERVER = server;
    }

    private final Features features;

    /**
     * 11-09 找需求先看正前方：每个玩家的候选先扫前方走廊里的（在飞行方向前面、离线不超过 corridorChunks），再扫其余的。
     * 直线飞时前方远处的 tile 按远近排在后面，扫描预算用完就轮不到（codex-pipe：待齐邻居里 39% 的 tile 整轮没派过）。
     * 不记游标，每次扫描从头来；前方组的时间片和读盘额度按玩家平分。
     * 11-09 起默认开（kexu 1×8 三轮：前方空白中位 4.6→4.2 秒、前方就绪 57→62 格；codex-bench ABABAB 3 对：43.2→47.4 格；MSPT 不变）。
     * -Dmcdonate.demandForwardFirst=0 关（对照用）。
     */
    static final boolean FORWARD_FIRST = on(Config.get("demandForwardFirst", "1"));

    private static boolean on(String v) {
        return v.equals("1") || v.equalsIgnoreCase("true");
    }

    /** 最近一次扫描的前方组候选数、实际看完的个数；累计的（status 里给平均和比例）。 */
    private int lastFwdCandidates, lastFwdVisited;
    private long totalFwdCandidates, totalFwdVisited;
    /** 7.2：每个玩家最近几秒的位置（估速度用）。 */
    private final java.util.Map<java.util.UUID, Motion> motions = new java.util.HashMap<>();

    /** 主线程上的变化直接改 DISK，别的线程的排进 EVENTS（存盘每个 chunk 都会调，不能攒着）。 */
    private static volatile MinecraftServer SERVER;

    /** 服务器存盘或导入后，这个 chunk 在存档里的状态变了。 */
    static void diskChanged(long key, int status) {
        MinecraftServer srv = SERVER;
        if (srv != null && srv.isSameThread()) {
            DISK.put(key, status);
        } else {
            EVENTS.add(new long[] {key, status, 0});
        }
    }

    /** 服务器存盘时只知道变了，不知道变成什么：下次重新查。 */
    public static void diskUnknown(long key) {
        MinecraftServer srv = SERVER;
        if (srv != null && srv.isSameThread()) {
            DISK.remove(key);
        } else {
            EVENTS.add(new long[] {key, FORGET, 0});
        }
    }

    private static void drainEvents() {
        long[] e;
        while ((e = EVENTS.poll()) != null) {
            int v = (int) e[1];
            if (v == FORGET) {
                DISK.remove(e[0]);
            } else if (e[2] == 0) {
                DISK.put(e[0], v);
            } else if (DISK.get(e[0]) == PENDING) {
                DISK.put(e[0], v);
            }
        }
    }

    /** 上一次扫描时各玩家所在的 chunk 和 outer 圈数（7.2 删飞过的 tile 用；别的线程读）。 */
    private volatile long[] lastPlayers = new long[0];
    private volatile int lastOuter;

    /** 这个 tile 在不在任何玩家当前的需求范围（outer 圈）里。按上一次扫描的位置。 */
    boolean inDemand(int tx, int tz) {
        return inArea(tx, tz, lastPlayers, lastOuter, TilePos.SIZE);
    }

    /** tile 离某个玩家所在 chunk 的切比雪夫距离（chunk）不超过 outer。纯函数。 */
    static boolean inArea(int tx, int tz, long[] players, int outer, int size) {
        for (long p : players) {
            int px = (int) p, pz = (int) (p >>> 32);
            int dx = Math.max(0, Math.max(tx * size - px, px - (tx * size + size - 1)));
            int dz = Math.max(0, Math.max(tz * size - pz, pz - (tz * size + size - 1)));
            if (Math.max(dx, dz) <= outer) {
                return true;
            }
        }
        return false;
    }

    /** 在主线程上调用，每秒一次。 */
    void scan() {
        long t0 = System.nanoTime();
        long c0 = THREADS.getCurrentThreadCpuTime();
        ServerLevel level = server.overworld();
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        ChunkMapAccessor access = (ChunkMapAccessor) chunkMap;
        int inner = innerRadius(), outer = inner + lookahead;
        // 11-09：只算有截止时间（或还没算过）的排队任务：侧面的（玩家不朝那边走、不派）原来占满上限后就不再扫描，
        // 前方的新 tile 进不来、已有的也不再重算截止时间，donor 闲着（kexu 三臂：8 核 donor 只用 1.2 核）
        int queued = tasks.queuedCountingTowardCap();
        readsLeft = READS_PER_SCAN;
        drainEvents();
        long now = System.currentTimeMillis();
        boolean deadline = features.on(Features.DEADLINE);
        int view = server.getPlayerList().getViewDistance();
        java.util.Set<java.util.UUID> present = new java.util.HashSet<>();
        for (ServerPlayer p : level.players()) {
            present.add(p.getUUID());
            motions.computeIfAbsent(p.getUUID(), k -> new Motion()).record(now, p.getX(), p.getZ());
        }
        motions.keySet().retainAll(present);
        // 11-09：每个玩家前进方向上服务器占住的最远圈数（前沿），算 importBy 用
        java.util.Map<Motion, Integer> fronts = new java.util.HashMap<>();
        if (deadline && IMPORT_FRONTIER) {
            for (ServerPlayer p : level.players()) {
                Motion mo = motions.get(p.getUUID());
                double[] v = mo.velocity();
                int f = Frontier.ahead(p.chunkPosition().x, p.chunkPosition().z, v[0], v[1], inner,
                        k -> access.mcdonate$getUpdatingChunkIfPresent(k) != null);
                if (f >= 0) {
                    fronts.put(mo, f);
                }
            }
            lastFrontier = fronts.isEmpty() ? -1 : java.util.Collections.min(fronts.values());
        }
        long[] at0 = new long[level.players().size()];
        for (int i = 0; i < at0.length; i++) {
            at0[i] = level.players().get(i).chunkPosition().toLong();
        }
        lastPlayers = at0;
        lastOuter = outer;
        List<ServerPlayer> players = level.players();
        int np = players.size();
        int[][] ptx = new int[np][], ptz = new int[np][], pidx = new int[np][];
        boolean[][] pfwd = new boolean[np][];
        int[] pm = new int[np], pos = new int[np], pfc = new int[np];
        int fwdCandidates = 0, fwdVisited = 0;
        for (int i = 0; i < np; i++) {
            ServerPlayer p = players.get(i);
            ChunkPos at = p.chunkPosition();
            Motion mo = motions.get(p.getUUID());
            // 每个玩家只算一次位置和两个窗口的速度
            double[] mp = mo == null ? null : mo.position();
            double[] vs = mo == null ? null : mo.velocity(Motion.SHORT_WINDOW_MS), vl = mo == null ? null : mo.velocity(Motion.LONG_WINDOW_MS);
            // 先按距离筛掉 inner 以内（服务器自己会碰到的范围，不交给 donor），再按离玩家远近排序。
            // 和 Demand.tilesAround / nearestFirst 同样的范围和顺序，只是不新建 TilePos、不装箱。
            TilePos a = TilePos.ofChunk(at.x - outer, at.z - outer), b = TilePos.ofChunk(at.x + outer, at.z + outer);
            int w = b.x() - a.x() + 1, n = w * (b.z() - a.z() + 1);
            int[] tx = new int[n], tz = new int[n];
            double[] d2 = new double[n];
            boolean[] fwd = new boolean[n];
            int m = 0;
            for (int x = a.x(); x <= b.x(); x++) {
                for (int z = a.z(); z <= b.z(); z++) {
                    int dx = Math.max(0, Math.max(x * TilePos.SIZE - at.x, at.x - (x * TilePos.SIZE + TilePos.SIZE - 1)));
                    int dz = Math.max(0, Math.max(z * TilePos.SIZE - at.z, at.z - (z * TilePos.SIZE + TilePos.SIZE - 1)));
                    if (Math.max(dx, dz) <= inner) {
                        continue;
                    }
                    double cx = x * TilePos.SIZE + (TilePos.SIZE - 1) / 2.0 - at.x, cz = z * TilePos.SIZE + (TilePos.SIZE - 1) / 2.0 - at.z;
                    tx[m] = x;
                    tz[m] = z;
                    d2[m] = cx * cx + cz * cz;
                    // 11-09：前方走廊里的候选（开关关着也算，status 里对照看候选够不够）
                    if (mo != null) {
                        TilePos tp = new TilePos(x, z);
                        fwd[m] = Motion.forward(tp, mp[0], mp[1], vs, TaskQueue.CORRIDOR_CHUNKS)
                                || Motion.forward(tp, mp[0], mp[1], vl, TaskQueue.CORRIDOR_CHUNKS);
                    }
                    if (fwd[m]) {
                        pfc[i]++;
                    }
                    m++;
                }
            }
            ptx[i] = tx;
            ptz[i] = tz;
            pfwd[i] = fwd;
            pm[i] = m;
            pidx[i] = Demand.scanOrder(m, tx, tz, d2, fwd, FORWARD_FIRST);
            fwdCandidates += pfc[i];
        }
        scanQueued = queued;
        if (FORWARD_FIRST) {
            // 第一遍：每个玩家先扫自己的前方组，时间片和读盘额度按玩家平分（一共一半），一个玩家用不完的留给后面的。
            // 时间片从第一遍开始时算：前面准备（前沿、候选）偶尔就超过半个预算，从扫描开始算前方组会一个都轮不到
            // （f3 直飞实测：开着时前方组也只看完 83%）。第二遍仍按整次扫描的预算
            long p0 = System.nanoTime();
            for (int i = 0; i < np; i++) {
                long sliceEnd = forwardSliceEnd(BUDGET_NANOS, i, np);
                int share = Math.min(readsLeft, forwardReadShare(READS_PER_SCAN, np));
                int rest = readsLeft - share;
                readsLeft = share;
                while (pos[i] < pfc[i] && System.nanoTime() - p0 <= sliceEnd) {
                    visit(ptx[i][pidx[i][pos[i]]], ptz[i][pidx[i][pos[i]]], deadline, now, view, fronts, chunkMap, access);
                    fwdVisited++;
                    pos[i]++;
                }
                readsLeft += rest;
            }
        }
        // 第二遍（关着时只有这一遍，和原来一样）：每个玩家从上次停下的地方接着，按顺序扫到预算用完
        scan:
        for (int i = 0; i < np; i++) {
            for (; pos[i] < pm[i]; pos[i]++) {
                if (System.nanoTime() - t0 > BUDGET_NANOS) {
                    break scan; // 这次扫描的时间用完了，剩下的（离玩家较远的）下一秒再查
                }
                int j = pidx[i][pos[i]];
                visit(ptx[i][j], ptz[i][j], deadline, now, view, fronts, chunkMap, access);
                if (pfwd[i][j]) {
                    fwdVisited++;
                }
            }
        }
        lastFwdCandidates = fwdCandidates;
        lastFwdVisited = fwdVisited;
        totalFwdCandidates += fwdCandidates;
        totalFwdVisited += fwdVisited;
        // 11-10 演示：后台先沿玩家朝向往前铺。走着用移动方向，站着（< 0.5 格/秒）用视线方向
        double[][] dirs = new double[at0.length][];
        for (int i = 0; i < at0.length; i++) {
            ServerPlayer p = level.players().get(i);
            Motion mo = motions.get(p.getUUID());
            double[] v = mo == null ? new double[] {0, 0} : mo.velocity(Motion.SHORT_WINDOW_MS);
            dirs[i] = Math.hypot(v[0], v[1]) >= 0.5 ? v : BoostPolicy.directionFromYaw(p.getYRot());
        }
        background(at0, dirs, outer, chunkMap, access);
        if (!toRead.isEmpty()) {
            long[] keys = toRead.toLongArray();
            toRead.clear();
            // 发起读存档放到后台线程：在主线程上调 chunkMap.read 每次要几十微秒（实测 256 次 1～14 ms）。
            // ChunkMap.read → IOWorker.loadAsync 只是往读盘线程的队列里提交任务，哪个线程调都安全。
            Util.ioPool().execute(() -> {
                for (long k : keys) {
                    chunkMap.read(new ChunkPos(k)).thenAccept(tag -> EVENTS.add(new long[] {k,
                            tag.map(t -> ChunkStatus.byName(t.getString("Status")).getIndex()).orElse(ABSENT), 1}));
                }
            });
        }
        long dt = System.nanoTime() - t0;
        scans++;
        totalNanos += dt;
        maxNanos = Math.max(maxNanos, dt);
        if (recent.size() == 64) {
            recent.removeFirst();
        }
        long cpu = THREADS.getCurrentThreadCpuTime() - c0;
        totalCpuNanos += cpu;
        recent.addLast("[" + dt / 1000 + "," + cpu / 1000 + "," + (READS_PER_SCAN - readsLeft) + "]");
    }

    /** 这次扫描里已入队、算上限的排队任务数（visit 入队时加）。 */
    private int scanQueued;

    // ---- 11-09 加速模式：开了加速的 donor 空着时做的后台 tile ----

    /** 后台 tile 离玩家（或预设中心）最多几圈 chunk；0 关。找需求的 outer 以内的照旧归找需求。 */
    static final int BACKGROUND_RADIUS = Config.getInt("backgroundRadius", 48);
    /** 最多排着几个后台 tile。 */
    static final int BACKGROUND_QUEUE_CAP = Config.getInt("backgroundQueueCap", 64);
    /** 后台这一段每次扫描最多占主线程多久（在找需求之后，单独算）。 */
    static final long BACKGROUND_BUDGET_NANOS = Config.getLong("backgroundScanBudgetMicros", 300) * 1000;
    /** 后台这一段每次扫描最多发起几次读存档。 */
    static final int BACKGROUND_READS = Config.getInt("backgroundReadsPerScan", 64);
    /** 预设中心（chunk 坐标 "x,z"，空串不用）：没有玩家在附近也一圈一圈往外做。 */
    static final int[] BACKGROUND_CENTER = parseCenter(Config.get("backgroundCenter", ""));

    static int[] parseCenter(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String[] p = v.trim().split("\\s*,\\s*");
        if (p.length != 2) {
            throw new IllegalArgumentException("backgroundCenter 要写成 x,z（chunk 坐标）：" + v);
        }
        return new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1])};
    }

    /** 有没有开了加速、没暂停的 donor 连着（CoordinatorServer 每秒设）。没有就不做后台扫描。 */
    private volatile boolean boostActive;

    void setBoostActive(boolean on) {
        boostActive = on;
    }

    /** 上一次后台扫描的中心 {cx, cz, 起始圈}（inBackground 用；别的线程读）。 */
    private volatile int[][] lastBackgroundCenters = new int[0][];
    private long backgroundScans, backgroundEnqueued, backgroundNanos;

    /** 这个 tile 在不在后台范围里（离某个中心不超过 BACKGROUND_RADIUS 圈）。 */
    boolean inBackground(int tx, int tz) {
        TilePos t = new TilePos(tx, tz);
        for (int[] c : lastBackgroundCenters) {
            if (Demand.minChunkDistance(t, c[0], c[1]) <= BACKGROUND_RADIUS) {
                return true;
            }
        }
        return false;
    }

    /**
     * 后台扫描：找需求之后，有开了加速的 donor 时，从每个玩家的 outer 圈外（和预设中心）一圈一圈往外，
     * 按找需求同样的规则（接缝规则、存档状态）把 tile 当后台任务排上，排着的最多 BACKGROUND_QUEUE_CAP 个。单独的时间和读盘额度。
     */
    private void background(long[] players, double[][] playerDirs, int outer, ChunkMap chunkMap, ChunkMapAccessor access) {
        int n = players.length + (BACKGROUND_CENTER == null ? 0 : 1);
        int[][] centers = new int[n][];
        for (int i = 0; i < players.length; i++) {
            centers[i] = new int[] {(int) players[i], (int) (players[i] >>> 32), outer};
        }
        double[][] dirs = new double[n][];
        for (int i = 0; i < players.length; i++) {
            dirs[i] = playerDirs[i];
        }
        if (BACKGROUND_CENTER != null) {
            centers[n - 1] = new int[] {BACKGROUND_CENTER[0], BACKGROUND_CENTER[1], -1};
            dirs[n - 1] = new double[] {0, 0}; // 预设中心没有方向：一圈一圈
        }
        lastBackgroundCenters = centers;
        if (!boostActive || BACKGROUND_RADIUS <= 0 || n == 0) {
            return;
        }
        int room = BACKGROUND_QUEUE_CAP - tasks.backgroundQueued();
        if (room <= 0) {
            return;
        }
        long b0 = System.nanoTime();
        backgroundScans++;
        int saved = readsLeft;
        readsLeft = BACKGROUND_READS;
        try {
            for (TilePos t : BoostPolicy.backgroundOrder(centers, dirs, BACKGROUND_RADIUS, BoostPolicy.BACKGROUND_CORRIDOR)) {
                if (room <= 0 || System.nanoTime() - b0 > BACKGROUND_BUDGET_NANOS) {
                    break;
                }
                long tk = ChunkPos.asLong(t.x(), t.z());
                if (serverTiles.contains(tk) || (tasks.find(t.x(), t.z()) >= 0 && !tasks.canEnqueueAgain(t.x(), t.z()))) {
                    continue;
                }
                RegionRules.Enqueue e = decide(t, tk, chunkMap, access);
                if (e.enqueue() && tasks.enqueueBackground(t.x(), t.z(), e.world())) {
                    room--;
                    backgroundEnqueued++;
                }
            }
        } finally {
            readsLeft = saved;
            backgroundNanos += System.nanoTime() - b0;
        }
    }

    /** 看一个候选 tile：没入队的判断能不能入队，开 deadline 时算截止时间。只在 scan 里调。 */
    private void visit(int x, int z, boolean deadline, long now, int view, java.util.Map<Motion, Integer> fronts,
            ChunkMap chunkMap, ChunkMapAccessor access) {
        long tk = ChunkPos.asLong(x, z);
        if (serverTiles.contains(tk)) {
            return;
        }
        // 11-09（codex-audit F6）：丢掉过、冷却过了的 tile 需求重新成立时可以重新入队
        boolean known = tasks.find(x, z) >= 0 && !tasks.canEnqueueAgain(x, z);
        if (!known && scanQueued < QUEUE_CAP) {
            RegionRules.Enqueue e = decide(new TilePos(x, z), tk, chunkMap, access);
            if (e.enqueue() && tasks.enqueue(x, z, e.world())) {
                scanQueued++;
                known = true;
            }
        }
        if (deadline && known) {
            // 7.2：玩家（取最快看到它的那个）什么时候看到这个 tile
            double best = Double.POSITIVE_INFINITY, by = Double.POSITIVE_INFINITY, lat = Double.POSITIVE_INFINITY;
            TilePos tp = new TilePos(x, z);
            for (Motion mo : motions.values()) {
                // 11-09：短窗口、长窗口两个方向的并集（转弯时新旧路线都算）
                best = Math.min(best, mo.deadlineSecondsUnion(tp, view));
                lat = Math.min(lat, mo.lateralChunksUnion(tp));
                Integer f = fronts.get(mo);
                if (f != null) {
                    // 区域比 tile 多一圈：tile 进 f+1 圈时区域碰到前沿
                    by = Math.min(by, mo.deadlineSecondsUnion(tp, f + 1));
                }
            }
            tasks.setDue(x, z, Double.isInfinite(best) ? Long.MAX_VALUE : now + (long) (best * 1000),
                    Double.isInfinite(by) ? Long.MAX_VALUE : now + (long) (by * 1000),
                    Double.isInfinite(lat) ? Integer.MAX_VALUE : (int) Math.round(lat));
        }
    }

    /** 第一遍（前方组）第 i 个玩家的时间片到哪里为止（从第一遍开始算的纳秒）：一共给前方组半个预算，按玩家平分、依次排开。 */
    static long forwardSliceEnd(long budget, int i, int players) {
        return budget * (i + 1) / (2L * players);
    }

    /** 第一遍每个玩家能发起几次读存档：一共一半，按玩家平分，至少 1 次。 */
    static int forwardReadShare(int reads, int players) {
        return Math.max(1, reads / (2 * players));
    }

    /**
     * 和 {@link Demand#decide} 规则一样（接缝规则 0.3），只是查到服务器的或在内存里的 chunk 就停，不再查剩下的。
     * 存档里状态还不知道的 chunk 发起异步读（每次扫描最多 READS_PER_SCAN 次）、这一轮不入队；
     * 同一个区域里其他不知道的也一起发起，下一轮就齐了。
     * 含有服务器 chunk 的 tile 记进 serverTiles，以后不再查：服务器的 chunk 不会变回空的或 donor 的。
     */
    private RegionRules.Enqueue decide(TilePos tile, long tileKey, ChunkMap chunkMap, ChunkMapAccessor access) {
        List<ChunkInfo> region = new ArrayList<>(36);
        boolean unknown = false;
        for (int x = tile.minChunkX() - 1; x <= tile.maxChunkX() + 1; x++) {
            for (int z = tile.minChunkZ() - 1; z <= tile.maxChunkZ() + 1; z++) {
                long key = ChunkPos.asLong(x, z);
                if (access.mcdonate$getUpdatingChunkIfPresent(key) != null || access.mcdonate$pendingUnloads().containsKey(key)) {
                    return RegionRules.Enqueue.no("在内存里");
                }
                int s = DISK.get(key);
                if (s == UNKNOWN || s == PENDING) {
                    if (s == UNKNOWN && readsLeft > 0) {
                        readsLeft--;
                        DISK.put(key, PENDING);
                        toRead.add(key);
                    }
                    unknown = true;
                    if (readsLeft == 0) {
                        // 这次扫描的读存档名额用完了：这个 tile 这次反正不入队，剩下的不用再查
                        return RegionRules.Enqueue.no("存档状态还在读");
                    }
                } else if (s == ABSENT) {
                    region.add(ChunkInfo.empty());
                } else {
                    String owner = Ownership.owner(key);
                    if (owner == null) {
                        serverTiles.add(tileKey);
                        return RegionRules.Enqueue.no("服务器的 chunk");
                    }
                    region.add(ChunkInfo.owned(owner, s));
                }
            }
        }
        return unknown ? RegionRules.Enqueue.no("存档状态还在读") : RegionRules.enqueue(region);
    }

    /** 离玩家这么多圈以内的 tile 不交给 donor（见类注释）。 */
    /** 上一次扫描实测的前沿（各玩家取最小；没测是 -1），写进 /donor status。 */
    private volatile int lastFrontier = -1;

    int innerRadius() {
        return server.getPlayerList().getViewDistance() + ChunkLevel.RADIUS_AROUND_FULL_CHUNK + INNER_MARGIN;
    }

    String json() {
        long avgMicros = scans == 0 ? 0 : totalNanos / scans / 1000;
        int inner = innerRadius();
        return "{\"count\":" + scans + ",\"avgMicros\":" + avgMicros + ",\"maxMicros\":" + maxNanos / 1000
                + ",\"avgCpuMicros\":" + (scans == 0 ? 0 : totalCpuNanos / scans / 1000)
                + ",\"recent\":[" + String.join(",", recent) + "]"
                + ",\"inner\":" + inner + ",\"outer\":" + (inner + lookahead) + ",\"lookahead\":" + lookahead
                + ",\"frontier\":" + lastFrontier
                + ",\"background\":{\"active\":" + boostActive + ",\"scans\":" + backgroundScans
                + ",\"enqueued\":" + backgroundEnqueued + ",\"queued\":" + tasks.backgroundQueued()
                + ",\"avgMicros\":" + (backgroundScans == 0 ? 0 : backgroundNanos / backgroundScans / 1000) + "}"
                + ",\"forward\":{\"on\":" + FORWARD_FIRST + ",\"candidates\":" + lastFwdCandidates
                + ",\"visited\":" + lastFwdVisited
                + ",\"avgCandidates\":" + (scans == 0 ? 0 : totalFwdCandidates / scans)
                + ",\"visitedRatio\":" + (totalFwdCandidates == 0 ? 1.0 : Math.round(1000.0 * totalFwdVisited / totalFwdCandidates) / 1000.0)
                + "}}";
    }
}
