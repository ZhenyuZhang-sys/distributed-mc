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
    final TaskQueue tasks = new TaskQueue(System::currentTimeMillis, LEASE_MS, MAX_ATTEMPTS);
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
    /** 每个导入的记录，测试用：tile、donor 世界、写了几个、是否整个丢弃。 */
    private final List<String> imports = new java.util.concurrent.CopyOnWriteArrayList<>();

    CoordinatorServer(MinecraftServer server, int port) {
        this.server = server;
        this.port = port;
        this.demand = new DemandScanner(server, tasks);
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
        workers.values().forEach(Connection::close);
    }

    /** 握手：版本对就分配编号回 welcome，不对回 workerId = -1。纯函数，单元测试用。 */
    static Welcome handshake(Hello hello, int workerId, long seed, int dataVersion) {
        if (hello.protocol() != Protocol.VERSION) {
            return new Welcome(Protocol.VERSION, -1, seed, dataVersion);
        }
        return new Welcome(Protocol.VERSION, workerId, seed, dataVersion);
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
            for (Connection c : workers.values()) {
                if (now - c.lastSeen > DEAD_MS) {
                    LOG.info("worker {} 15 秒没有消息，断开", c.workerId);
                    c.close();
                    continue;
                }
                Optional<TaskQueue.Assignment> a = tasks.assign(c.workerId);
                a.ifPresent(x -> {
                    LOG.info("任务 {}（tile {}, {}）派给 worker {}", x.taskId(), x.tx(), x.tz(), c.workerId);
                    // 派出去就记下，不等 worker 的心跳（一个 tile 只要 2 秒左右，可能两次心跳之间就做完了）
                    c.currentTask = x.taskId();
                    c.send(new Task(x.taskId(), x.tx(), x.tz(), x.leaseMs()));
                });
            }
        } catch (RuntimeException e) {
            LOG.warn("定时任务出错：{}", e.toString());
        }
    }

    private void sendHeartbeats() {
        long now = System.currentTimeMillis();
        workers.values().forEach(c -> c.send(new Heartbeat(now, Heartbeat.NO_TASK)));
    }

    private void onResult(Connection c, Result r) {
        if (r.ok()) {
            String world = r.tile().getString("worldId");
            if (!world.isEmpty()) {
                c.world = world;
                tasks.setWorkerWorld(c.workerId, world);
            }
        }
        TaskQueue.Decision d = tasks.onResult(c.workerId, r.taskId(), r.ok());
        switch (d.action()) {
            case IMPORT -> {
                d.cancelWorker().map(workers::get).ifPresent(other -> other.send(new Cancel(r.taskId())));
                server.execute(() -> importResult(c, r));
            }
            case REQUEUE -> LOG.warn("任务 {} 在 worker {} 上失败（{}）：{}", r.taskId(), c.workerId, r.error(), d.reason());
            case FAIL -> LOG.warn("任务 {} 在 worker {} 上失败（{}）：{}", r.taskId(), c.workerId, r.error(), d.reason());
            case DISCARD -> LOG.info("丢弃 worker {} 的结果：{}", c.workerId, d.reason());
        }
    }

    /** 主线程上：校验 tile 并导入。出错就让任务回到队列。 */
    private void importResult(Connection c, Result r) {
        int workerId = c.workerId;
        try {
            TileFile file = TileFile.fromTag(r.tile(), TileImporter.dataVersion(), server.overworld().getSeed());
            String tile = "[" + file.tile().x() + "," + file.tile().z() + "]";
            if (strict) {
                TileImporter.StrictResult res = TileImporter.importStrict(server.overworld(), file);
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
                imports.add("{\"tile\":" + tile + ",\"world\":\"" + file.worldId() + "\",\"accepted\":" + res.accepted()
                        + ",\"written\":" + (res.written() + res.overwritten()) + "}");
            } else {
                TileImporter.Result res = TileImporter.importTile(server.overworld(), file, "task " + r.taskId());
                LOG.info("导入任务 {}（worker {}，旧规则）：written={} loaded={} exists={}", r.taskId(), workerId,
                        res.written(), res.loaded(), res.exists());
                imports.add("{\"tile\":" + tile + ",\"world\":\"" + file.worldId() + "\",\"accepted\":true,\"written\":"
                        + res.written() + "}");
            }
        } catch (RuntimeException e) {
            LOG.warn("导入任务 {} 失败，重新排队：{}", r.taskId(), e.toString());
            tasks.importFailed(r.taskId());
        }
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
                    .append('}');
        }
        return sb.append("],\"config\":").append(configJson()).append(",\"demand\":").append(demandOn).append(",\"scan\":").append(demand.json()).append(",\"tasks\":").append(tasks.countsJson())
                .append(",\"discarded_conflict\":").append(discardedConflict.get())
                .append(",\"discard_ratio\":").append(discardRatio())
                .append(",\"discarded\":{\"late\":").append(discardedBy.get(RegionRules.Discard.LOADED).get())
                .append(",\"other_world\":").append(discardedBy.get(RegionRules.Discard.OTHER_WORLD).get())
                .append(",\"server\":").append(discardedBy.get(RegionRules.Discard.SERVER).get())
                .append("},\"accepted\":").append(acceptedImports.get()).append('}').toString();
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
        List<String> all = List.copyOf(imports);
        int a = Math.min(Math.max(from, 0), all.size()), b = Math.min(a + IMPORTS_PAGE, all.size());
        return "{\"total\":" + all.size() + ",\"items\":[" + String.join(",", all.subList(a, b)) + "]}";
    }

    /** 一个 worker 连接：读线程、写线程。 */
    private final class Connection {
        final Socket socket;
        final BlockingQueue<Message> out = new LinkedBlockingQueue<>();
        volatile long lastSeen = System.currentTimeMillis();
        volatile int workerId = -1;
        volatile String name = "";
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
                close();
            }
        }

        private void readLoop() {
            FrameDecoder decoder = new FrameDecoder();
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = socket.getInputStream()) {
                int n;
                while (!closed && (n = in.read(buf)) >= 0) {
                    for (Message m : decoder.feed(buf, 0, n)) {
                        lastSeen = System.currentTimeMillis();
                        handle(m);
                    }
                }
            } catch (SocketException e) {
                // 断开
            } catch (IOException | ProtocolException e) {
                LOG.warn("worker {} 的连接出错，断开：{}", workerId, e.toString());
            } finally {
                close();
            }
        }

        private void handle(Message m) {
            if (workerId < 0) {
                if (!(m instanceof Hello hello)) {
                    LOG.warn("连接的第一条消息不是 hello，断开");
                    close();
                    return;
                }
                Welcome w = handshake(hello, nextWorkerId.getAndIncrement(), server.overworld().getSeed(),
                        TileImporter.dataVersion());
                if (w.workerId() < 0) {
                    LOG.warn("worker {} 的协议版本是 {}，本机是 {}，拒绝", hello.name(), hello.protocol(), Protocol.VERSION);
                    sendThenClose(w);
                    return;
                }
                workerId = w.workerId();
                name = hello.name();
                workers.put(workerId, this);
                send(w);
                LOG.info("worker {}（{}）连上", workerId, name);
                return;
            }
            switch (m) {
                case Heartbeat h -> {
                    currentTask = h.taskId();
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
            close();
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                socket.close();
            } catch (IOException ignored) {
                // 已经断了
            }
            if (workerId >= 0 && workers.remove(workerId, this)) {
                List<Long> back = tasks.workerGone(workerId);
                LOG.info("worker {} 断开{}", workerId, back.isEmpty() ? "" : "，任务 " + back + " 回到队首");
            }
        }
    }
}
