package mcdonate.worker;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * 控制通道的输出（给游戏看的 "MCDONATE ..." 行）。
 *
 * <p>原版服务器启动时 Bootstrap.wrapStreams 把 System.out 换成按日志格式输出的流，经过它写的行变成
 * "[时间] [线程/INFO]: [STDOUT]: MCDONATE ..."，客户端按行首认不出来（11-09：NEED_TOKEN、CONNECTED、STATUS 全丢，
 * 用户那边 HUD 一直显示 worker 没在跑）。所以直接写进程的标准输出（文件描述符 1），一行一刷。
 */
final class ControlOut {
    private ControlOut() {}

    /** 写进程的标准输出（不经过 System.out）。 */
    static Consumer<String> stdout() {
        return to(new FileOutputStream(FileDescriptor.out));
    }

    /** 写进给定的流（测试用）。几个线程会同时写，加锁。 */
    static Consumer<String> to(OutputStream raw) {
        PrintStream ps = new PrintStream(raw, true, StandardCharsets.UTF_8);
        return line -> {
            synchronized (ps) {
                ps.print(line + "\n");
                ps.flush();
            }
        };
    }
}
