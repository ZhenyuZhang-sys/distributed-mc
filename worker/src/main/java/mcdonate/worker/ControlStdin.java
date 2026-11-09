package mcdonate.worker;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * 客户端起的 worker：标准输入只给控制通道（ControlLine）。
 *
 * <p>原版专用服务器启动时开一个控制台线程读 System.in，和控制通道抢同一个标准输入，哪个先读到算哪个
 * （11-09 f1：PAUSE 被原版控制台当成命令，worker.log 里是 "Unknown command PAUSE<--[HERE]"，coordinator 没看到暂停；
 * TOKEN、STOP 也可能这样丢）。所以在服务器起来之前（mod 初始化时）把标准输入拿走，System.in 换成空的，
 * 原版控制台读到 EOF 就退出。Docker 里的 worker 不调这个（它的控制台照常用）。
 */
final class ControlStdin {
    private static InputStream claimed;

    private ControlStdin() {}

    /** 拿走标准输入（只拿一次，再调返回同一个）。 */
    static synchronized InputStream claim() {
        if (claimed == null) {
            claimed = System.in;
            System.setIn(new ByteArrayInputStream(new byte[0]));
        }
        return claimed;
    }
}
