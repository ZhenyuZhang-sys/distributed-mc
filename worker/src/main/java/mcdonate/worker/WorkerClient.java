package mcdonate.worker;

import mcdonate.common.Config;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import mcdonate.common.TilePos;
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
 * worker 的网络部分（第 5 步）：连 coordinator，领任务、生成、发回，断线重连。
 *
 * <p>线程：一个连接线程（连上后读消息）；一个心跳线程每 5 秒发心跳，带上正在做的任务用来续租。
 * 生成用 server.execute 交给主线程，主线程卡住也不影响心跳和收发。一次只做一个任务。
 */
final class WorkerClient {
    private static final Logger LOG = LoggerFactory.getLogger("mcdonate-worker");
    private static final long HEARTBEAT_MS = Config.getLong("heartbeatMs", 5_000);
    private static final int READ_TIMEOUT_MS = Config.getInt("readTimeoutMs", 15_000);

    private final MinecraftServer server;
    private final String host;
    private final int port;
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();
    private volatile long currentTask = Heartbeat.NO_TASK;
    private volatile Socket socket;
    private volatile boolean running = true;
    private volatile boolean seedMismatch;
    /** 第 9 步：一次性 token（客户端起的 worker 从环境变量拿；Docker 里的本地 worker 没有）。 */
    final TokenState tokens = new TokenState(System.getenv(WORKER_TOKEN_ENV));
    /** 第 9 步：玩家那边帧率低，暂停：心跳里报 paused，coordinator 不再派新任务；手上的照常做完。 */
    private volatile boolean paused;
    /** 第 9 步：给客户端看的行（标准输出，带 MCDONATE 前缀）。 */
    private final java.util.function.Consumer<String> controlOut;

    /** 和客户端 mod 的 WorkerLaunch.TOKEN_ENV 一样。 */
    static final String WORKER_TOKEN_ENV = "MCDONATE_TOKEN";

    WorkerClient(MinecraftServer server, String address) {
        this(server, address, ControlOut.stdout()); // 不经过 System.out（原版会把它包成日志格式，见 ControlOut）
    }

    WorkerClient(MinecraftServer server, String address, java.util.function.Consumer<String> controlOut) {
        this.server = server;
        this.controlOut = controlOut;
        this.generating = new OneAtATime<>(server::execute);
        String[] hp = address.split(":");
        this.host = hp[0];
        this.port = Integer.parseInt(hp[1]);
    }

    static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "worker";
        }
    }

    void start() {
        Thread t = new Thread(this::connectLoop, "mcdonate-worker-net");
        t.setDaemon(true);
        t.start();
        Thread hb = new Thread(this::heartbeatLoop, "mcdonate-worker-heartbeat");
        hb.setDaemon(true);
        hb.start();
    }

    void stop() {
        running = false;
        closeSocket();
    }

    /** 断线后按 1、2、4…最长 30 秒重连。 */
    private void connectLoop() {
        long wait = 1000;
        while (running) {
            if (tokens.needsNew()) {
                // token 用过了或被拒：请客户端再要一个（mcdonate:invite_request），拿到前不连
                controlOut.accept(ControlLine.out("NEED_TOKEN"));
                while (running && tokens.needsNew()) {
                    sleep(500);
                }
                if (!running) {
                    return;
                }
            }
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 5000);
                s.setSoTimeout(READ_TIMEOUT_MS);
                socket = s;
                send(new Hello(Protocol.VERSION, hostName(), tokens.forHello()));
                wait = 1000;
                readLoop(s);
            } catch (IOException | ProtocolException e) {
                if (running) {
                    LOG.info("和 coordinator 的连接断了（{}），{} 秒后重连", e.getMessage(), wait / 1000);
                }
            } finally {
                socket = null;
                // 断线时手上的任务已被 coordinator 收回，做完也不发
                long t = currentTask;
                if (t >= 0) {
                    cancelled.add(t);
                }
            }
            sleep(wait);
            wait = Math.min(wait * 2, 30_000);
        }
    }

    private void readLoop(Socket s) throws IOException {
        FrameDecoder decoder = new FrameDecoder();
        byte[] buf = new byte[64 * 1024];
        InputStream in = s.getInputStream();
        int n;
        while ((n = in.read(buf)) >= 0) {
            for (Message m : decoder.feed(buf, 0, n)) {
                handle(m);
            }
        }
        throw new IOException("coordinator 断开了");
    }

    private void handle(Message m) throws IOException {
        switch (m) {
            case Welcome w -> {
                if (w.workerId() == Welcome.BAD_TOKEN) {
                    tokens.rejected();
                    throw new IOException("coordinator 拒绝：token 不对或过期");
                }
                if (w.workerId() < 0) {
                    throw new IOException("coordinator 拒绝：协议版本 " + w.protocol() + "，本机 " + Protocol.VERSION);
                }
                tokens.connected();
                controlOut.accept(ControlLine.out("CONNECTED " + w.workerId()));
                long mine = server.overworld().getSeed();
                seedMismatch = !seedOk(w, mine);
                if (seedMismatch) {
                    LOG.error("存档 seed {} 和 coordinator 的 {} 不一样，不领任务", mine, w.seed());
                } else {
                    LOG.info("连上 coordinator，编号 {}", w.workerId());
                }
            }
            case Task t -> onTask(t);
            case Cancel c -> {
                cancelled.add(c.taskId());
                LOG.info("任务 {} 被撤掉", c.taskId());
            }
            case Heartbeat h -> {
                // 收到任何消息就算活着，读超时会重置
            }
            default -> LOG.warn("收到不该收的消息 {}，忽略", m.getClass().getSimpleName());
        }
    }

    private void onTask(Task t) {
        if (seedMismatch) {
            send(Result.failure(t.taskId(), "seed 不一致"));
            return;
        }
        currentTask = t.taskId();
        received.put(t.taskId(), System.currentTimeMillis());
        // 11-09：收到就把它的 chunk 排上生成（加票、不等，很快），前一个 tile 还在导出时它已经在生成线程池里了。
        // 开 throughput 时 coordinator 一次给几个，这样几个 tile 同时做；导出（等、序列化）还是一个一个来
        TilePos tile = new TilePos(t.tx(), t.tz());
        server.execute(() -> TileExporter.prefetch(server.overworld(), tile));
        // 一次只导出一个：主线程等 chunk 时会执行排着的任务，直接 server.execute 会让下一个 tile 嵌套在上一个里面跑（OneAtATime）
        generating.submit(t, this::generate);
    }

    private final OneAtATime<Task> generating;
    /** 任务 → 收到的时间（分段耗时，11-09）。 */
    private final java.util.Map<Long, Long> received = new ConcurrentHashMap<>();
    /** 控制通道 STATUS 的累计数（11-09 overlay）。 */
    private final WorkerStatus status = new WorkerStatus();

    private void generate(Task t) {
        Long got = received.remove(t.taskId());
        if (cancelled.remove(t.taskId())) {
            LOG.info("任务 {} 还没开始就被撤掉，不做", t.taskId());
            TileExporter.release(server.overworld(), new TilePos(t.tx(), t.tz())); // 收到时加的票去掉
            return;
        }
        currentTask = t.taskId();
        // 排在后面的任务也先排上生成（收到时 server.execute 的那次可能还在服务器队列里没轮到）：这个 tile 等的时候它们一起做
        for (Task next : generating.pendingValues()) {
            if (!cancelled.contains(next.taskId())) {
                TileExporter.prefetch(server.overworld(), new TilePos(next.tx(), next.tz()));
            }
        }
        Result r;
        int chunkCount = 0;
        try {
            long g0 = System.currentTimeMillis();
            TileExporter.Generated g = TileExporter.generate(server.overworld(), new TilePos(t.tx(), t.tz()));
            chunkCount = g.file().chunks().size();
            long g1 = System.currentTimeMillis();
            net.minecraft.nbt.CompoundTag tag = g.file().toTag();
            // 分段耗时：收到、开始生成、生成完、打包完（coordinator 写进 /donor imports 的 seg）
            mcdonate.common.TaskTiming.stamp(tag, got == null ? g0 : got, g0, g1, System.currentTimeMillis());
            r = Result.success(t.taskId(), tag);
        } catch (RuntimeException e) {
            LOG.warn("任务 {} 生成失败：{}", t.taskId(), e.toString());
            r = Result.failure(t.taskId(), truncate(e.toString()));
        }
        currentTask = Heartbeat.NO_TASK;
        if (cancelled.remove(t.taskId())) {
            LOG.info("任务 {} 已被撤掉，结果不发", t.taskId());
            return;
        }
        deliver(r);
        if (r.ok()) {
            status.tileDone(chunkCount, 0); // 字节数在 send 里按帧记
        }
    }

    /** 第 9 步：控制通道的一条命令（游戏经 worker 的标准输入发来）。STOP 由调用方处理（要停服务器）。 */
    void control(ControlLine.Command c) {
        switch (c.kind()) {
            case PAUSE -> {
                paused = true;
                send(new Heartbeat(System.currentTimeMillis(), currentTask, true)); // 马上告诉 coordinator
                LOG.info("暂停（玩家那边帧率低）：手上的做完，不领新的");
            }
            case RESUME -> {
                paused = false;
                send(new Heartbeat(System.currentTimeMillis(), currentTask, false));
                LOG.info("恢复");
            }
            case TOKEN -> {
                if (tokens.offer(c.arg())) {
                    LOG.info("收到新 token");
                }
            }
            case STATUS -> controlOut.accept(ControlLine.out("STATUS " + status.json(
                    !running ? WorkerStatus.State.STOPPING : paused ? WorkerStatus.State.PAUSED : WorkerStatus.State.RUNNING,
                    currentTask, socket != null, tokens.needsNew(), WorkerStatus.Load.current())));
            case STOP -> { }
        }
    }

    boolean paused() {
        return paused;
    }

    /** Welcome 里的 seed 和自己建世界用的一样才领任务（第 5 步；第 9 步建世界的 seed 来自邀请，这里是唯一的核对）。 */
    static boolean seedOk(Welcome w, long worldSeed) {
        return w.seed() == worldSeed;
    }

    /** 测试用（/donor hold）：扣着做完的结果先不发，放开时一起发。7.6a 用它让服务器先碰到闸门、再收到结果。 */
    private final java.util.List<Result> held = new java.util.ArrayList<>();
    private boolean hold;

    private void deliver(Result r) {
        synchronized (held) {
            if (hold) {
                held.add(r);
                LOG.info("任务 {} 做完了，按要求先扣着", r.taskId());
                return;
            }
        }
        send(r);
    }

    /** 打开或放开扣结果；放开时把扣着的发出去，返回发了几个。 */
    int setHold(boolean on) {
        java.util.List<Result> out;
        synchronized (held) {
            hold = on;
            if (on) {
                return 0;
            }
            out = new java.util.ArrayList<>(held);
            held.clear();
        }
        out.forEach(this::send);
        return out.size();
    }

    /** NBT 字符串最长 65535 字节，错误信息太长编码会失败：截到 4000 个字符。 */
    static String truncate(String s) {
        return s.length() <= 4000 ? s : s.substring(0, 4000);
    }

    private void heartbeatLoop() {
        while (running) {
            sleep(HEARTBEAT_MS);
            send(new Heartbeat(System.currentTimeMillis(), currentTask, paused));
        }
    }

    /** 发一条消息。网络线程、主线程、心跳线程都会调，所以加锁写。 */
    private synchronized void send(Message m) {
        Socket s = socket;
        if (s == null) {
            return;
        }
        try {
            OutputStream out = s.getOutputStream();
            byte[] frame = Frame.encode(m);
            out.write(frame);
            out.flush();
            status.sent(frame.length);
        } catch (IOException e) {
            closeSocket();
        }
    }

    private void closeSocket() {
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // 已经断了
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
