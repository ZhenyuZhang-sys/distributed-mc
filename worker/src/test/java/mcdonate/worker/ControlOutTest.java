package mcdonate.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 11-09（distributed-mc-75 在用户 Mac 上查到、f1 的 worker.log 证实）：原版服务器启动时 Bootstrap.wrapStreams 把 System.out
 * 换成按日志格式输出的流，控制通道写的 "MCDONATE CONNECTED 5" 变成 "[时间] [线程/INFO]: [STDOUT]: MCDONATE CONNECTED 5"，
 * 客户端按行首 "MCDONATE " 认不出来：NEED_TOKEN、CONNECTED、STATUS 全丢（HUD 一直显示 worker 没在跑）。
 * 修法：控制通道直接写进程的标准输出（文件描述符 1），不经过 System.out。
 */
class ControlOutTest {
    @Test
    void linesBypassWrappedSystemOut() {
        PrintStream saved = System.out;
        ByteArrayOutputStream fd1 = new ByteArrayOutputStream();
        ByteArrayOutputStream wrapped = new ByteArrayOutputStream();
        try {
            // 模拟 wrapStreams：System.out 被换成加前缀的流
            System.setOut(new PrintStream(wrapped, true, StandardCharsets.UTF_8) {
                @Override
                public void println(String x) {
                    super.println("[12:00:00] [Server thread/INFO]: [STDOUT]: " + x);
                }
            });
            java.util.function.Consumer<String> out = ControlOut.to(fd1);
            out.accept(ControlLine.out("CONNECTED 5"));
            out.accept(ControlLine.out("STATUS {\"paused\":false}"));
            assertEquals("MCDONATE CONNECTED 5\nMCDONATE STATUS {\"paused\":false}\n", fd1.toString(StandardCharsets.UTF_8));
            assertEquals("", wrapped.toString(StandardCharsets.UTF_8), "不经过 System.out");
        } finally {
            System.setOut(saved);
        }
    }
}
