package mcdonate.coordinator;

import mcdonate.common.Config;
import mcdonate.common.TilePos;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mcdonate.common.TileFile;
import mcdonate.coordinator.mixin.ChunkMapAccessor;
import mcdonate.common.proto.Cancel;
import mcdonate.common.proto.Frame;
import mcdonate.common.proto.FrameDecoder;
import mcdonate.common.proto.Heartbeat;
import mcdonate.common.proto.Hello;
import mcdonate.common.proto.Message;
import mcdonate.common.proto.Protocol;
import mcdonate.common.proto.ProtocolException;
import mcdonate.common.proto.Result;
import mcdonate.common.proto.Task;
import mcdonate.common.proto.Welcome;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * coordinator 的网络部分（第 5 步）：监听 worker、握手、发任务、收结果。
 *
 * <p>线程：一个接收线程；每个连接一个读线程和一个写线程；一个定时线程（收回到期租约、派任务、
 * 发心跳、断开 15 秒没动静的连接）。网络线程从不碰游戏状态，导入用 server.execute 交给主线程。
 */
final class CoordinatorServer {
    private static final Logger LOG = LoggerFactory.getLogger("mcdonate-coordinator");
    static final long LEASE_MS = Config.getLong("leaseMs", 30_000);
    static final long HEARTBEAT_MS = Config.getLong("heartbeatMs", 5_000);
    static final long DEAD_MS = Config.getLong("deadMs", 15_000);
    static final int MAX_ATTEMPTS = Config.getInt("maxAttempts", 3);

    private final MinecraftServer server;
    private final int port;
    /** 第 7 步的开关（docs/step7-plan.md 0.1），初值来自配置项 features，运行中用 /donor feature 切换。 */
    final Features features = Features.fromSystemProperty();
    /** 7.3：每个 worker 做一个 tile 的实测耗时。 */
    final DonorThroughput throughput = new DonorThroughput();
    /** 7.6：等 donor。 */
    final WaitDonor waitDonor;
    final TaskQueue tasks = new TaskQueue(System::currentTimeMillis, LEASE_MS, MAX_ATTEMPTS, features);
    private final Map<Integer, Connection> workers = new ConcurrentHashMap<>();
    private final AtomicInteger nextWorkerId = new AtomicInteger(1);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "mcdonate-coordinator-timer"));
    private ServerSocket listener;
    private volatile boolean running;
    final DemandScanner demand;
    /** 找需求的开关：正式运行默认开；测试服务器用 -Dmcdonate.demand=off 默认关，测试里按需打开。 */
    volatile boolean demandOn = !"off".equals(Config.get("demand", "on"));
    /** 严格导入（第 6 步的区域规则）；关掉就用第 3 步的旧规则（只给测试故意制造接缝用）。 */
    volatile boolean strict = true;
    final AtomicInteger discardedConflict = new AtomicInteger();
    /** 整个丢弃按原因分：来晚了（区域已在服务器内存里）、碰到别的 donor 世界、碰到服务器生成的。 */
    final java.util.Map<RegionRules.Discard, AtomicInteger> discardedBy = new java.util.EnumMap<>(RegionRules.Discard.class);
    {
        for (RegionRules.Discard d : RegionRules.Discard.values()) {
            discardedBy.put(d, new AtomicInteger());
        }
    }
    /** 严格导入里被接受的结果数（丢弃比例的分母之一）。 */
    final AtomicInteger acceptedImports = new AtomicInteger();
    /** 主线程上导入用了多久（每次结果的校验 + 写存档请求，调度 11-09：看 donor 加进来是不是拖慢服务器）。 */
    private final java.util.concurrent.atomic.AtomicLong importCount = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong importNanos = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong importMaxNanos = new java.util.concurrent.atomic.AtomicLong();
    /** 每个导入的记录，测试用：tile、donor 世界、写了几个、是否整个丢弃。 */
    private final List<String> imports = new java.util.concurrent.CopyOnWriteArrayList<>();

    CoordinatorServer(MinecraftServer server, int port) {
        this.server = server;
        this.port = port;
        this.demand = new DemandScanner(server, tasks, features);
        this.waitDonor = new WaitDonor(features, tasks, throughput);
        WaitDonor.instance = waitDonor;
    }

    /** 找需求开着没有：没有 donor 连着就不扫，服务器和原版一样。 */
    boolean shouldScan() {
        return demandOn && !workers.isEmpty();
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    void start() throws IOException {
        if (ALLOW_TOKENLESS) {
            // 安全：没带 token 的 worker 也放进来。只该出现在测试的 compose.test.yaml 里
            LOG.warn("mcdonate.allowTokenless 开着：没带 token 的 worker 也能连进来、领任务、导入。只用于测试，正式部署要关掉");
        }
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(port));
        running = true;
        daemon(this::acceptLoop, "mcdonate-coordinator-accept").start();
        timer.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
        timer.scheduleAtFixedRate(this::sendHeartbeats, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
        LOG.info("coordinator 在端口 {} 监听", port);
    }

    void stop() {
        running = false;
        timer.shutdownNow();
        try {
            listener.close();
        } catch (IOException ignored) {
            // 关服时不管
        }
        workers.values().forEach(c -> c.close("coordinator 停止"));
    }

    private static final java.util.regex.Pattern WORLD_ID = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /**
     * hello 里报的 donor 世界算不算数（11-09，kexu 审）：格式不对（只许字母数字、下划线、横线，最长 64）不收；
     * 这个世界已经绑在另一个在线的 worker 上，而两边不是同一个玩家（或都没有玩家）时不收——防冒认，拿走别人认主的任务。
     * 不收就是空串：照旧等它交第一个结果时再从结果里认。纯函数。
     *
     * @param online 现在在线的 worker：世界 → 它的玩家（没有玩家是 null）
     */
    static String helloWorld(String worldId, java.util.UUID player, java.util.Map<String, java.util.UUID> online) {
        if (worldId == null || !WORLD_ID.matcher(worldId).matches()) {
            return "";
        }
        if (online.containsKey(worldId)) {
            java.util.UUID owner = online.get(worldId);
            if (owner == null || !owner.equals(player)) {
                return "";
            }
        }
        return worldId;
    }

    /** 握手：版本对就分配编号回 welcome，不对回 workerId = -1。纯函数，单元测试用。 */
    static Welcome handshake(Hello hello, int workerId, long seed, int dataVersion) {
        return handshake(hello, workerId, seed, dataVersion, true);
    }

    /** 第 9 步：token 不认（admitted = false）回 {@link Welcome#BAD_TOKEN}。版本不对优先报版本。 */
    static Welcome handshake(Hello hello, int workerId, long seed, int dataVersion, boolean admitted) {
        if (hello.protocol() != Protocol.VERSION) {
            return new Welcome(Protocol.VERSION, Welcome.BAD_VERSION, seed, dataVersion);
        }
        return new Welcome(Protocol.VERSION, admitted ? workerId : Welcome.BAD_TOKEN, seed, dataVersion);
    }

    /** 第 9 步：放不放这个 worker 进来；player 是 token 对应的玩家，免 token 的本地 worker 是 null。 */
    record Admission(boolean ok, java.util.UUID player) {}

    /**
     * 第 9 步（docs/step9-plan.md 3.1）：hello 带了 token 就必须有效（认识、没用过、没过期），用掉它；
     * 没带 token 只有 allowTokenless 时放行（测试里 Docker 的本地 worker，系统属性 mcdonate.allowTokenless，正式默认关）。
     */
    static Admission admit(Hello hello, TokenRegistry tokens, boolean allowTokenless) {
        if (hello.token().isEmpty()) {
            return new Admission(allowTokenless, null);
        }
        return tokens.consume(hello.token()).map(p -> new Admission(true, p)).orElse(new Admission(false, null));
    }

    /** 第 9 步：没有 token 的 worker 放不放（测试 compose 里设 true；正式默认 false）。 */
    static final boolean ALLOW_TOKENLESS = Boolean.parseBoolean(mcdonate.common.Config.get("allowTokenless", "false"));

    /** 第 9 步：玩家 worker 的一次性 token。玩家进服时发（Coordinator），worker 握手时用掉。 */
    final TokenRegistry tokens = new TokenRegistry(System::currentTimeMillis);

    /** 第 7 步对比用（/donor reset）：清掉队列、撤回在租的（发 cancel）、放开所有闸门。返回撤回了几个。 */
    int reset() {
        List<TaskQueue.Cancelled> cancel = tasks.reset();
        for (TaskQueue.Cancelled c : cancel) {
            waitDonor.release(c.taskId());
            Connection conn = workers.get(c.worker());
            if (conn != null) {
                conn.send(new Cancel(c.taskId()));
            }
        }
        LOG.info("清空任务队列，撤回在租的 {} 个", cancel.size());
        return cancel.size();
    }

    /** 第 9 步：玩家退出：作废他没用过的 token，断开他的 worker（手上的任务回到队列）。 */
    void playerLeft(java.util.UUID player) {
        tokens.revoke(player);
        for (Connection c : workers.values()) {
            if (player.equals(c.player)) {
                LOG.info("玩家 {} 退出，断开他的 worker {}", player, c.workerId);
                c.close("玩家退出");
            }
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = listener.accept();
                new Connection(s).start();
            } catch (IOException e) {
                if (running) {
                    LOG.warn("接收连接出错：{}", e.toString());
                }
            }
        }
    }

    /** 每秒：收回到期租约，给空闲 worker 派任务，断开 15 秒没动静的连接。 */
    private void tick() {
        try {
            for (long id : tasks.expire()) {
                LOG.info("任务 {} 租约到期，回到队首", id);
            }
            java.util.Set<String> worlds = new java.util.HashSet<>();
            workers.values().forEach(c -> {
                if (c.world != null) {
                    worlds.add(c.world);
                }
            });
            for (long id : tasks.dropOrphans(worlds)) {
                LOG.info("任务 {} 认主的 donor 世界 60 秒不在，丢掉，交给服务器", id);
            }
            long now = System.currentTimeMillis();
            if (features.on(Features.DEADLINE)) {
                // 7.2：玩家已经看到了还没派出去的，交给服务器
                List<Long> late = tasks.dropLate();
                if (!late.isEmpty()) {
                    LOG.info("任务 {} 玩家已经看到了还没派出去，丢掉，交给服务器", late);
                }
                // 7.2（11-09 补的漏洞）：玩家已经飞过、又不在任何玩家需求范围里的：删掉，派出去的撤回
                TaskQueue.Stale stale = tasks.dropPassed(demand::inDemand);
                for (TaskQueue.Cancelled c : stale.cancelled()) {
                    waitDonor.release(c.taskId());
                    Connection conn = workers.get(c.worker());
                    if (conn != null) {
                        conn.send(new Cancel(c.taskId()));
                    }
                }
                if (!stale.dropped().isEmpty() || !stale.cancelled().isEmpty()) {
                    LOG.info("玩家已经飞过的 tile：删掉排队的 {} 个，撤回派出的 {} 个", stale.dropped().size(), stale.cancelled().size());
                }
                // 11-09：按在线 donor 的实测吞吐估每个排队任务什么时候做完，来不及的现在就丢掉，交给服务器
                double rate = 0;
                long latency = 0;
                int n = 0;
                for (Connection c : workers.values()) {
                    if (!c.paused) {
                        rate += throughput.ratePerMs(c.workerId); // 11-09（A15）：按完成间隔，不按 1/延迟
                        latency += throughput.latencyMs(c.workerId);
                        n++;
                    }
                }
                // 一个 tile 从开始到做完：各 donor 延迟中位数的平均（不能用 n/速率：并行时速率高、单个 tile 照样要那么久）
                List<Long> hopeless = tasks.pruneHopeless(rate, n == 0 ? 0 : latency / n);
                if (!hopeless.isEmpty()) {
                    LOG.info("排队的 {} 个 tile 按现在的吞吐来不及，丢掉，交给服务器", hopeless.size());
                }
            }
            if (features.on(Features.HEDGE)) {
                // 7.4：预计做完时玩家已经看到了的，撤回，交给服务器
                for (TaskQueue.Lease l : tasks.leasesWithDue()) {
                    if (Hedge.shouldHedge(l.leasedAt(), throughput.averageMs(l.worker()), now, l.due())) {
                        int w = tasks.hedgeToServer(l.taskId());
                        waitDonor.release(l.taskId());
                        Connection c = workers.get(w);
                        if (c != null) {
                            c.send(new Cancel(l.taskId()));
                        }
                        LOG.info("任务 {} 在 worker {} 上预计赶不上，撤回交给服务器", l.taskId(), w);
                    }
                }
            }
            boolean thr = features.on(Features.THROUGHPUT);
            for (Connection c : workers.values()) {
                if (now - c.lastSeen > DEAD_MS) {
                    LOG.info("worker {} 15 秒没有消息，断开", c.workerId);
                    c.close("15 秒没有消息（心跳超时）");
                    continue;
                }
                if (c.paused) {
                    continue; // 第 9 步：玩家那边帧率低、worker 暂停了：不派新任务，手上的照常续租、等结果
                }
                // 7.3：按实测耗时决定同时派几个，慢的只派来得及的；关着时一次一个。
                // 11-09：开 deadline 时"来不及就不派"也生效（不开 throughput 也按实测耗时估，区域进前沿之前做不完的留给服务器）
                int max = thr ? throughput.concurrency(c.workerId, LEASE_MS) : 1;
                java.util.function.LongPredicate canMeet = thr || features.on(Features.DEADLINE)
                        ? due -> throughput.canMeet(c.workerId, tasks.inFlight(c.workerId), now, due) : due -> true;
                Optional<TaskQueue.Assignment> a;
                while ((a = tasks.assign(c.workerId, max, canMeet)).isPresent()) {
                    TaskQueue.Assignment x = a.get();
                    LOG.info("任务 {}（tile {}, {}）派给 worker {}", x.taskId(), x.tx(), x.tz(), c.workerId);
                    // 派出去就记下，不等 worker 的心跳（一个 tile 只要 2 秒左右，可能两次心跳之间就做完了）
                    c.currentTask = x.taskId();
                    c.send(new Task(x.taskId(), x.tx(), x.tz(), x.leaseMs()));
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("定时任务出错：{}", e.toString());
        }
    }

    private void sendHeartbeats() {
        long now = System.currentTimeMillis();
        workers.values().forEach(c -> c.send(new Heartbeat(now, Heartbeat.NO_TASK)));
    }

    /**
     * 每个结果的大小（NBT 字节；线上的帧字节数）和派出到收到的毫秒数，导入时写进 /donor imports（第 7 步"有网络"那组要看分布）。
     * 线上字节数（限速卡的是它）：common 的 FrameDecoder.feedFrames 报每帧的长度（长度前缀里的 N + 4，kexu 11-09），精确值。
     */
    private final Map<Long, long[]> resultMeta = new ConcurrentHashMap<>();

    private void onResult(Connection c, Result r) {
        long leasedAt = tasks.leasedAt(r.taskId());
        if (r.ok()) {
            long got = System.currentTimeMillis();
            long ms = leasedAt > 0 ? got - leasedAt : -1;
            // 分段耗时（11-09）：入队、派出、收到，加上 worker 带来的 4 个时间点（没有是 -1）
            long[] w = mcdonate.common.TaskTiming.read(r.tile());
            resultMeta.put(r.taskId(), new long[] {r.tile().sizeInBytes(), ms, c.lastWireBytes,
                    tasks.enqueuedAt(r.taskId()), leasedAt > 0 ? leasedAt : -1, got,
                    w == null ? -1 : w[0], w == null ? -1 : w[1], w == null ? -1 : w[2], w == null ? -1 : w[3],
                    tasks.slackAtLease(r.taskId())});
        }
        if (r.ok() && leasedAt > 0) {
            // 7.3：实测耗时（派出到结果回来），不信 worker 自己报的
            long nowMs = System.currentTimeMillis();
            throughput.record(c.workerId, nowMs - leasedAt, nowMs);
        }
        if (r.ok()) {
            String world = r.tile().getString("worldId");
            if (!world.isEmpty()) {
                if (c.world != null && !c.world.equals(world)) {
                    // 11-09（kexu）：hello 报的和结果里的不一样，以结果为准
                    LOG.warn("worker {} 的结果来自 donor 世界 {}，和它 hello 里报的 {} 不一样，按结果算", c.workerId, world, c.world);
                }
                c.world = world;
                tasks.setWorkerWorld(c.workerId, world);
            }
        }
        TaskQueue.Decision d = tasks.onResult(c.workerId, r.taskId(), r.ok());
        switch (d.action()) {
            case IMPORT -> {
                d.cancelWorker().map(workers::get).ifPresent(other -> other.send(new Cancel(r.taskId())));
                if (waitDonor.hasGate(r.taskId())) {
                    // 7.6：有 chunk 在等这个结果。导入提交到 chunk 缓存自己的队列：主线程同步等 chunk 时
                    // （getChunk 的 managedBlock）只执行这个队列，server.execute 的要等它结束，闸门只能等到超时。
                    java.util.concurrent.Executor ex = waitDonor.importViaChunkCache
                            ? ((ChunkMapAccessor) server.overworld().getChunkSource().chunkMap).mcdonate$mainThreadExecutor()
                            : server;
                    ex.execute(() -> {
                        try {
                            if (importResult(c, r)) {
                                waitDonor.hits.incrementAndGet();
                            }
                        } finally {
                            waitDonor.release(r.taskId());
                        }
                    });
                } else {
                    server.execute(() -> importResult(c, r));
                }
            }
            case REQUEUE -> LOG.warn("任务 {} 在 worker {} 上失败（{}）：{}", r.taskId(), c.workerId, r.error(), d.reason());
            case FAIL -> LOG.warn("任务 {} 在 worker {} 上失败（{}）：{}", r.taskId(), c.workerId, r.error(), d.reason());
            case DISCARD -> LOG.info("丢弃 worker {} 的结果：{}", c.workerId, d.reason());
        }
        if (d.action() != TaskQueue.Action.IMPORT) {
            resultMeta.remove(r.taskId());
            waitDonor.release(r.taskId());
        }
    }

    /** 主线程上：校验 tile 并导入。出错就让任务回到队列。返回是否被接受。 */
    private boolean importResult(Connection c, Result r) {
        long t0 = System.nanoTime();
        try {
            return importResultTimed(c, r, System.currentTimeMillis());
        } finally {
            long ns = System.nanoTime() - t0;
            importCount.incrementAndGet();
            importNanos.addAndGet(ns);
            importMaxNanos.accumulateAndGet(ns, Math::max);
        }
    }

    /** importStart：主线程开始处理这个结果的时间（分段耗时用）。 */
    private boolean importResultTimed(Connection c, Result r, long importStart) {
        int workerId = c.workerId;
        try {
            long p0 = System.nanoTime();
            TileFile file = TileFile.fromTag(r.tile(), TileImporter.dataVersion(), server.overworld().getSeed());
            long parseNs = System.nanoTime() - p0;
            String tile = "[" + file.tile().x() + "," + file.tile().z() + "]";
            if (strict) {
                TileImporter.StrictResult res = TileImporter.importStrict(server.overworld(), file, workerId,
                        c.player == null ? "" : c.player.toString());
                if (!res.accepted()) {
                    discardedConflict.incrementAndGet();
                    discardedBy.get(res.discard()).incrementAndGet();
                    LOG.info("导入任务 {}（worker {}，tile {}）整个丢弃：{}", r.taskId(), workerId, tile, res.reason());
                } else {
                    acceptedImports.incrementAndGet();
                    c.doneTiles.incrementAndGet();
                    tasks.markImported(r.taskId(), file.worldId());
                    LOG.info("导入任务 {}（worker {}，tile {}）：written={} overwritten={} skipped={}", r.taskId(), workerId,
                            tile, res.written(), res.overwritten(), res.skipped());
                }
                long[] meta = resultMeta.remove(r.taskId());
                imports.add("{\"tile\":" + tile + ",\"world\":\"" + file.worldId() + "\",\"accepted\":" + res.accepted()
                        + ",\"written\":" + (res.written() + res.overwritten())
                        + ",\"bytes\":" + (meta == null ? -1 : meta[0]) + ",\"wire_bytes\":" + (meta == null ? -1 : meta[2])
                        + ",\"ms\":" + (meta == null ? -1 : meta[1])
                        + ",\"slack_ms\":" + (meta == null ? -1 : meta[10])
                        // 主线程导入的三段（毫秒，小数）：解析 NBT、查区域（含读存档）、写入
                        + ",\"imp\":{\"parse\":" + parseNs / 100_000 / 10.0 + ",\"check\":" + TileImporter.LAST_SPLIT_NS[0] / 100_000 / 10.0
                        + ",\"write\":" + TileImporter.LAST_SPLIT_NS[1] / 100_000 / 10.0 + "}"
                        + ",\"seg\":" + (meta == null ? "null" : TaskTimeline.json(meta[3], meta[4],
                                meta[6] < 0 ? null : new long[] {meta[6], meta[7], meta[8], meta[9]},
                                meta[5], importStart, System.currentTimeMillis())) + "}");
                return res.accepted();
            } else {
                TileImporter.Result res = TileImporter.importTile(server.overworld(), file, "task " + r.taskId());
                LOG.info("导入任务 {}（worker {}，旧规则）：written={} loaded={} exists={}", r.taskId(), workerId,
                        res.written(), res.loaded(), res.exists());
                imports.add("{\"tile\":" + tile + ",\"world\":\"" + file.worldId() + "\",\"accepted\":true,\"written\":"
                        + res.written() + "}");
                return true;
            }
        } catch (RuntimeException e) {
            LOG.warn("导入任务 {} 失败，重新排队：{}", r.taskId(), e.toString());
            tasks.importFailed(r.taskId());
            return false;
        }
    }

    /**
     * worker 编号 → 稳定名字和显示名（11-09 kexu overlay 用）；不在线返回空。玩家名从在线玩家列表里找（玩家下线了用主机名）。
     * 在主线程上调（玩家列表是主线程的）。
     */
    /** 测试用：断开一个 worker。不在线返回 false。 */
    boolean kick(int workerId) {
        Connection c = workers.get(workerId);
        if (c == null) {
            return false;
        }
        c.close("被 /donor kick 踢掉（测试用）");
        return true;
    }

    /** 现在连着的 worker 个数。 */
    public int workerCount() {
        return workers.size();
    }

    public java.util.Optional<WorkerName> workerName(int workerId) {
        Connection c = workers.get(workerId);
        if (c == null) {
            return java.util.Optional.empty();
        }
        java.util.UUID p = c.player;
        net.minecraft.server.level.ServerPlayer sp = p == null ? null : server.getPlayerList().getPlayer(p);
        return java.util.Optional.of(WorkerName.of(p, sp == null ? null : sp.getGameProfile().getName(), c.world, c.name));
    }

    /** 当前生效的配置项（docs/config.md），放进 /donor status。 */
    static String configJson() {
        return "{\"tileSize\":" + TilePos.SIZE
                + ",\"demandLookahead\":" + Config.getInt("demandLookahead", 8)
                + ",\"demandInnerMargin\":" + DemandScanner.INNER_MARGIN
                + ",\"demandQueueCap\":" + DemandScanner.QUEUE_CAP
                + ",\"scanTicks\":" + Coordinator.SCAN_TICKS
                + ",\"demandReadsPerScan\":" + DemandScanner.READS_PER_SCAN
                + ",\"demandScanBudgetMicros\":" + DemandScanner.BUDGET_NANOS / 1000
                + ",\"leaseMs\":" + LEASE_MS
                + ",\"heartbeatMs\":" + HEARTBEAT_MS
                + ",\"deadMs\":" + DEAD_MS
                + ",\"maxAttempts\":" + MAX_ATTEMPTS
                + ",\"orphanMs\":" + TaskQueue.ORPHAN_MS
                + ",\"maxConcurrency\":" + DonorThroughput.MAX_CONCURRENCY
                + ",\"tileDefaultMs\":" + DonorThroughput.DEFAULT_MS
                + ",\"throughputWindow\":" + DonorThroughput.WINDOW
                + ",\"motionWindowMs\":" + Motion.WINDOW_MS
                + ",\"motionHorizonS\":" + Motion.HORIZON_S
                + ",\"tokenTtlMs\":" + TokenRegistry.TTL_MS
                + ",\"allowTokenless\":" + ALLOW_TOKENLESS + ",\"publicHost\":\"" + Coordinator.PUBLIC_HOST + "\",\"publicPort\":" + Coordinator.PUBLIC_PORT
                + ",\"maxLeaseTotalMs\":" + TaskQueue.MAX_LEASE_TOTAL_MS
                + ",\"corridorChunks\":" + TaskQueue.CORRIDOR_CHUNKS + ",\"skipNoDeadline\":" + TaskQueue.SKIP_NO_DEADLINE
                + ",\"importFrontier\":" + DemandScanner.IMPORT_FRONTIER + ",\"importMarginMs\":" + TaskQueue.IMPORT_MARGIN_MS
                + ",\"waitDonorMaxMs\":" + WaitDonor.MAX_WAIT_MS
                + ",\"features\":\"" + Config.get("features", "").replace("\"", "") + "\""
                + ",\"genThreads\":" + Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() - 1, Integer.getInteger("max.bg.threads", 255)))
                + "}";
    }

    /** /donor status 的 JSON。 */
    String statusJson() {
        StringBuilder sb = new StringBuilder("{\"workers\":[");
        long now = System.currentTimeMillis();
        int i = 0;
        for (Connection c : workers.values()) {
            if (i++ > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":").append(c.workerId).append(",\"name\":\"").append(c.name.replace("\"", ""))
                    .append("\",\"world\":\"").append(c.world == null ? "" : c.world)
                    .append("\",\"task\":").append(c.currentTask).append(",\"done\":").append(c.doneTiles.get())
                    .append(",\"idleMs\":").append(now - c.lastSeen)
                    .append(",\"inFlight\":").append(tasks.inFlight(c.workerId))
                    .append(",\"paused\":").append(c.paused)
                    .append(",\"avgMs\":").append(throughput.averageMs(c.workerId))
                    .append('}');
        }
        return sb.append("],\"features\":").append(features.json()).append(",\"config\":").append(configJson()).append(",\"demand\":").append(demandOn).append(",\"scan\":").append(demand.json()).append(",\"tasks\":").append(tasks.countsJson())
                .append(",\"discarded_conflict\":").append(discardedConflict.get())
                .append(",\"discard_ratio\":").append(discardRatio())
                .append(",\"discarded\":{\"late\":").append(discardedBy.get(RegionRules.Discard.LOADED).get())
                .append(",\"other_world\":").append(discardedBy.get(RegionRules.Discard.OTHER_WORLD).get())
                .append(",\"server\":").append(discardedBy.get(RegionRules.Discard.SERVER).get())
                .append("},\"accepted\":").append(acceptedImports.get())
                .append(",\"stale\":").append(tasks.staleJson())
                .append(",\"hedge\":{\"cancelled\":").append(tasks.hedgeCancelled()).append(",\"wasted\":").append(tasks.hedgeWasted())
                .append("},\"ledger\":").append(Ownership.LEDGER.size())
                .append(",\"waitdonor\":").append(waitDonor.json())
                .append(",\"import\":{\"count\":").append(importCount.get())
                .append(",\"totalMs\":").append(importNanos.get() / 1_000_000.0)
                .append(",\"maxMs\":").append(importMaxNanos.get() / 1_000_000.0).append("}}").toString();
    }

    /** 跨 donor 冲突被整个丢弃的结果占所有严格导入结果的比例（donor 白干的比例）。第 7 步要压低它。 */
    double discardRatio() {
        int d = discardedConflict.get(), a = acceptedImports.get();
        return d + a == 0 ? 0.0 : Math.round(1000.0 * d / (d + a)) / 1000.0;
    }

    /** 一页最多几条：RCON 一次回复只有约 4096 字节。 */
    static final int IMPORTS_PAGE = 30;

    /** /donor imports [from]：从第 from 条起最多 30 条导入记录，{"total": N, "items": [...]}。 */
    String importsJson(int from) {
        return importsPage(List.copyOf(imports), from, IMPORTS_PAGE_BYTES);
    }

    /** 一页最多多少字节：RCON 一次回复只有约 4096 字节，条目长短不一（11-09 加了 bytes、wire_bytes、ms 以后 30 条会超）。 */
    static final int IMPORTS_PAGE_BYTES = 3000;

    /** 从 from 起取一页：总长不超过 budget（至少一条），调用方按拿到的条数往后翻。纯函数。 */
    static String importsPage(List<String> all, int from, int budget) {
        int a = Math.min(Math.max(from, 0), all.size()), b = a, used = 0;
        while (b < all.size() && b - a < IMPORTS_PAGE && (b == a || used + all.get(b).length() + 1 <= budget)) {
            used += all.get(b).length() + 1;
            b++;
        }
        return "{\"total\":" + all.size() + ",\"items\":[" + String.join(",", all.subList(a, b)) + "]}";
    }

    /** 一个 worker 连接：读线程、写线程。 */
    private final class Connection {
        final Socket socket;
        final BlockingQueue<Message> out = new LinkedBlockingQueue<>();
        volatile long lastSeen = System.currentTimeMillis();
        volatile int workerId = -1;
        volatile String name = "";
        /** 正在处理的这一帧在线上占多少字节（读线程上写、同一线程上的 onResult 读）。 */
        volatile int lastWireBytes = -1;
        /** 第 9 步：worker 在心跳里报的暂停状态（限速），暂停时不派新任务。 */
        volatile boolean paused;
        /** 第 9 步：这个 worker 属于哪个玩家（token 换来的）；免 token 的本地 worker 是 null。 */
        volatile java.util.UUID player;
        /** 这个 worker 的 donor 世界（从它交的第一个结果里知道）。 */
        volatile String world;
        /** 被接受导入的 tile 数。 */
        final AtomicInteger doneTiles = new AtomicInteger();
        volatile long currentTask = -1;
        private volatile boolean closed;

        Connection(Socket socket) {
            this.socket = socket;
        }

        void start() {
            daemon(this::readLoop, "mcdonate-coordinator-read").start();
            daemon(this::writeLoop, "mcdonate-coordinator-write").start();
        }

        void send(Message m) {
            if (!closed) {
                out.add(m);
            }
        }

        private void writeLoop() {
            try (OutputStream os = socket.getOutputStream()) {
                while (!closed) {
                    Message m = out.poll(1, TimeUnit.SECONDS);
                    if (m != null) {
                        os.write(Frame.encode(m));
                        os.flush();
                    }
                }
            } catch (IOException | InterruptedException | ProtocolException e) {
                close("发送失败：" + e);
            }
        }

        private void readLoop() {
            FrameDecoder decoder = new FrameDecoder();
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = socket.getInputStream()) {
                int n;
                while (!closed && (n = in.read(buf)) >= 0) {
                    for (FrameDecoder.Decoded d : decoder.feedFrames(buf, 0, n)) {
                        lastSeen = System.currentTimeMillis();
                        lastWireBytes = d.wireBytes(); // handle 在同一个线程上同步处理这一帧，onResult 读到的就是这一帧的
                        handle(d.message());
                    }
                }
            } catch (SocketException e) {
                closeReason = closeReason != null ? closeReason : "连接断了：" + e.getMessage();
            } catch (IOException | ProtocolException e) {
                closeReason = "连接出错：" + e;
                LOG.warn("worker {} 的连接出错，断开：{}", workerId, e.toString());
            } finally {
                close(closeReason == null ? "对方关了连接" : closeReason);
            }
        }

        private void handle(Message m) {
            if (workerId < 0) {
                if (!(m instanceof Hello hello)) {
                    LOG.warn("连接的第一条消息不是 hello，断开");
                    close("第一条消息不是 hello");
                    return;
                }
                Admission adm = admit(hello, tokens, ALLOW_TOKENLESS);
                Welcome w = handshake(hello, nextWorkerId.getAndIncrement(), server.overworld().getSeed(),
                        TileImporter.dataVersion(), adm.ok());
                if (w.workerId() == Welcome.BAD_TOKEN) {
                    // 不打 token 本身（TokenRegistry.redact）
                    LOG.warn("worker {} 的 token 不对（{}），拒绝", hello.name(),
                            hello.token().isEmpty() ? "没带" : TokenRegistry.redact(hello.token()));
                    sendThenClose(w);
                    return;
                }
                if (w.workerId() < 0) {
                    LOG.warn("worker {} 的协议版本是 {}，本机是 {}，拒绝", hello.name(), hello.protocol(), Protocol.VERSION);
                    sendThenClose(w);
                    return;
                }
                workerId = w.workerId();
                name = hello.name();
                player = adm.player();
                // 11-09：worker 一连上就报它的 donor 世界，认主给这个世界的任务马上能派，"认主的世界不在"也不会误报
                // （原来要等它交出第一个结果；donor 世界跨会话复用时，新连上的 worker 领不到认主的任务，用户真机上空等了 83 秒）
                java.util.Map<String, java.util.UUID> online = new java.util.HashMap<>();
                for (Connection o : workers.values()) {
                    if (o.world != null) {
                        online.put(o.world, o.player);
                    }
                }
                String hw = helloWorld(hello.worldId(), adm.player(), online);
                if (!hello.worldId().isEmpty() && hw.isEmpty()) {
                    LOG.warn("worker {} 在 hello 里报的 donor 世界不收（格式不对，或者已经绑在另一个在线的 worker 上）：{}", hello.name(),
                            hello.worldId().length() > 80 ? hello.worldId().substring(0, 80) + "…" : hello.worldId());
                } else if (!hw.isEmpty()) {
                    world = hw;
                    tasks.setWorkerWorld(w.workerId(), world);
                }
                workers.put(workerId, this);
                send(w);
                LOG.info("worker {}（{}）连上{}", workerId, name, world == null ? "" : "，donor 世界 " + world);
                return;
            }
            switch (m) {
                case Heartbeat h -> {
                    currentTask = h.taskId();
                    if (paused != h.paused()) {
                        LOG.info("worker {} {}", workerId, h.paused() ? "暂停（玩家那边帧率低）" : "恢复");
                    }
                    paused = h.paused();
                    if (h.taskId() >= 0) {
                        tasks.heartbeat(workerId, h.taskId());
                    }
                }
                case Result r -> {
                    currentTask = -1;
                    onResult(this, r);
                }
                default -> LOG.warn("worker {} 发来不该发的消息 {}，忽略", workerId, m.getClass().getSimpleName());
            }
        }

        private void sendThenClose(Message m) {
            try {
                OutputStream os = socket.getOutputStream();
                os.write(Frame.encode(m));
                os.flush();
            } catch (IOException ignored) {
                // 反正要断
            }
            close("发完最后一条消息后断开");
        }

        /** 断开的原因（11-09：用户真机上 worker 反复断开，日志里只有"断开"，看不出为什么）。 */
        private volatile String closeReason;
        private final long openedAt = System.currentTimeMillis();

        void close(String reason) {
            if (closed) {
                return;
            }
            closeReason = reason;
            closed = true;
            try {
                socket.close();
            } catch (IOException ignored) {
                // 已经断了
            }
            if (workerId >= 0 && workers.remove(workerId, this)) {
                List<Long> back = tasks.workerGone(workerId);
                throughput.forget(workerId);
                long now = System.currentTimeMillis();
                LOG.info("worker {} 断开（{}；连了 {} 秒，最后一条消息在 {} 秒前）{}", workerId, closeReason,
                        (now - openedAt) / 1000, (now - lastSeen) / 1000, back.isEmpty() ? "" : "，任务 " + back + " 回到队首");
            }
        }
    }
}
