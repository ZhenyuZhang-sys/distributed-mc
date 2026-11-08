package mcdonate.worker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.function.LongSupplier;
import mcdonate.common.tempproto.Frame;
import mcdonate.common.tempproto.FrameDecoder;
import mcdonate.common.tempproto.Hello;
import mcdonate.common.tempproto.Message;
import mcdonate.common.tempproto.Protocol;
import mcdonate.common.tempproto.Welcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 建世界前向 coordinator 要 seed（第 5 步 3.1）。
 *
 * <p>DedicatedServerProperties 运行中可能被重新构造，parseSeed 会被调用不止一次，
 * 所以只握手一次，seed 缓存在静态变量里。
 */
final class SeedHandshake {
    private static final Logger LOG = LoggerFactory.getLogger("mcdonate-worker");
    /** 连不上时最多重试多久。 */
    static final long GIVE_UP_MS = 5 * 60_000;

    private static Long cached;

    private SeedHandshake() {}

    /** coordinator 地址（系统属性 mcdonate.coordinator=host:port），没设返回 null。 */
    static String address() {
        String a = System.getProperty("mcdonate.coordinator");
        return a == null || a.isBlank() ? null : a.trim();
    }

    /** 拿 seed：第一次调 handshake，之后直接返回缓存。 */
    static synchronized long seed(LongSupplier handshake) {
        if (cached == null) {
            cached = handshake.getAsLong();
        }
        return cached;
    }

    /** 单元测试用：清掉缓存。 */
    static synchronized void resetForTest() {
        cached = null;
    }

    /** 真正的握手：hello → welcome，带退避重试，5 分钟还不行就抛异常（服务器启动失败）。 */
    static long handshake(String address) {
        String[] hp = address.split(":");
        long deadline = System.currentTimeMillis() + GIVE_UP_MS;
        long wait = 1000;
        while (true) {
            try {
                Welcome w = exchange(hp[0], Integer.parseInt(hp[1]));
                if (w.workerId() < 0) {
                    throw new IllegalStateException("coordinator 拒绝：协议版本 " + w.protocol() + "，本机 " + Protocol.VERSION);
                }
                LOG.info("从 coordinator {} 拿到 seed {}", address, w.seed());
                return w.seed();
            } catch (IOException e) {
                if (System.currentTimeMillis() + wait > deadline) {
                    throw new IllegalStateException("连不上 coordinator " + address + "：" + e, e);
                }
                LOG.info("连不上 coordinator {}（{}），{} 秒后重试", address, e.getMessage(), wait / 1000);
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待 coordinator 时被打断", ie);
                }
                wait = Math.min(wait * 2, 30_000);
            }
        }
    }

    /** 连上、发 hello、等 welcome、断开。 */
    static Welcome exchange(String host, int port) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setSoTimeout(15_000);
            OutputStream out = s.getOutputStream();
            out.write(Frame.encode(new Hello(Protocol.VERSION, "seed-" + WorkerClient.hostName())));
            out.flush();
            InputStream in = s.getInputStream();
            FrameDecoder d = new FrameDecoder();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                for (Message m : d.feed(buf, 0, n)) {
                    if (m instanceof Welcome w) {
                        return w;
                    }
                }
            }
            throw new IOException("coordinator 没回 welcome 就断开了");
        }
    }
}
