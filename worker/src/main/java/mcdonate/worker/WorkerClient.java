package mcdonate.worker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import mcdonate.common.TilePos;
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
import net.minecraft.nbt.CompoundTag;
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
    private static final long HEARTBEAT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private final MinecraftServer server;
    private final String host;
    private final int port;
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();
    private volatile long currentTask = -1;
    private volatile Socket socket;
    private volatile boolean running = true;
    private volatile boolean seedMismatch;

    WorkerClient(MinecraftServer server, String address) {
        this.server = server;
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
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 5000);
                s.setSoTimeout(READ_TIMEOUT_MS);
                socket = s;
                send(new Hello(Protocol.VERSION, hostName()));
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
                if (w.workerId() < 0) {
                    throw new IOException("coordinator 拒绝：协议版本 " + w.protocol() + "，本机 " + Protocol.VERSION);
                }
                long mine = server.overworld().getSeed();
                seedMismatch = w.seed() != mine;
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
            send(new Result(t.taskId(), false, "seed 不一致", new CompoundTag()));
            return;
        }
        currentTask = t.taskId();
        server.execute(() -> {
            Result r;
            try {
                TileExporter.Generated g = TileExporter.generate(server.overworld(), new TilePos(t.tx(), t.tz()));
                r = new Result(t.taskId(), true, "", g.file().toTag());
            } catch (RuntimeException e) {
                LOG.warn("任务 {} 生成失败：{}", t.taskId(), e.toString());
                r = new Result(t.taskId(), false, e.toString(), new CompoundTag());
            }
            currentTask = -1;
            if (cancelled.remove(t.taskId())) {
                LOG.info("任务 {} 已被撤掉，结果不发", t.taskId());
                return;
            }
            send(r);
        });
    }

    private void heartbeatLoop() {
        while (running) {
            sleep(HEARTBEAT_MS);
            send(new Heartbeat(System.currentTimeMillis(), currentTask));
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
            out.write(Frame.encode(m));
            out.flush();
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
