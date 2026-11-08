package mcdonate.coordinator;

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
import mcdonate.common.tempproto.Cancel;
import mcdonate.common.tempproto.Frame;
import mcdonate.common.tempproto.FrameDecoder;
import mcdonate.common.tempproto.Heartbeat;
import mcdonate.common.tempproto.Hello;
import mcdonate.common.tempproto.Message;
import mcdonate.common.tempproto.Protocol;
import mcdonate.common.tempproto.ProtocolException;
import mcdonate.common.tempproto.Result;
import mcdonate.common.tempproto.Task;
import mcdonate.common.tempproto.Welcome;
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
    static final long LEASE_MS = 30_000;
    static final long HEARTBEAT_MS = 5_000;
    static final long DEAD_MS = 15_000;
    static final int MAX_ATTEMPTS = 3;

    private final MinecraftServer server;
    private final int port;
    final TaskQueue tasks = new TaskQueue(System::currentTimeMillis, LEASE_MS, MAX_ATTEMPTS);
    private final Map<Integer, Connection> workers = new ConcurrentHashMap<>();
    private final AtomicInteger nextWorkerId = new AtomicInteger(1);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "mcdonate-coordinator-timer"));
    private ServerSocket listener;
    private volatile boolean running;

    CoordinatorServer(MinecraftServer server, int port) {
        this.server = server;
        this.port = port;
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
                    c.send(new Task(x.taskId(), x.tx(), x.tz(), x.leaseMs()));
                });
            }
        } catch (RuntimeException e) {
            LOG.warn("定时任务出错：{}", e.toString());
        }
    }

    private void sendHeartbeats() {
        long now = System.currentTimeMillis();
        workers.values().forEach(c -> c.send(new Heartbeat(now, -1)));
    }

    private void onResult(Connection c, Result r) {
        TaskQueue.Decision d = tasks.onResult(c.workerId, r.taskId(), r.ok());
        switch (d.action()) {
            case IMPORT -> {
                d.cancelWorker().map(workers::get).ifPresent(other -> other.send(new Cancel(r.taskId())));
                server.execute(() -> importResult(c.workerId, r));
            }
            case REQUEUE -> LOG.warn("任务 {} 在 worker {} 上失败（{}）：{}", r.taskId(), c.workerId, r.error(), d.reason());
            case FAIL -> LOG.warn("任务 {} 在 worker {} 上失败（{}）：{}", r.taskId(), c.workerId, r.error(), d.reason());
            case DISCARD -> LOG.info("丢弃 worker {} 的结果：{}", c.workerId, d.reason());
        }
    }

    /** 主线程上：校验 tile 并导入。出错就让任务回到队列。 */
    private void importResult(int workerId, Result r) {
        try {
            TileFile file = TileFile.fromTag(r.tile(), TileImporter.dataVersion(), server.overworld().getSeed());
            TileImporter.Result res = TileImporter.importTile(server.overworld(), file, "task " + r.taskId());
            LOG.info("导入任务 {}（worker {}）：written={} loaded={} exists={}", r.taskId(), workerId,
                    res.written(), res.loaded(), res.exists());
        } catch (RuntimeException e) {
            LOG.warn("导入任务 {} 失败，重新排队：{}", r.taskId(), e.toString());
            tasks.importFailed(r.taskId());
        }
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
                    .append("\",\"task\":").append(c.currentTask).append(",\"idleMs\":").append(now - c.lastSeen)
                    .append('}');
        }
        return sb.append("],\"tasks\":").append(tasks.countsJson()).append('}').toString();
    }

    /** 一个 worker 连接：读线程、写线程。 */
    private final class Connection {
        final Socket socket;
        final BlockingQueue<Message> out = new LinkedBlockingQueue<>();
        volatile long lastSeen = System.currentTimeMillis();
        volatile int workerId = -1;
        volatile String name = "";
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
