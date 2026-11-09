package mcdonate.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * 11-09 f1：客户端经标准输入发的 PAUSE 被原版服务器的控制台线程读走了（worker.log："Unknown command PAUSE<--[HERE]"），
 * coordinator 没看到暂停。原版的控制台和我们的控制通道抢同一个标准输入，哪个先读到算哪个。
 * 修法：客户端起的 worker 在服务器起来之前把标准输入拿走给控制通道，System.in 换成空的，原版控制台读到 EOF 就退出。
 */
class ControlStdinTest {
    @Test
    void claimTakesStdinAndLeavesConsoleEmpty() throws Exception {
        InputStream saved = System.in;
        InputStream fake = new ByteArrayInputStream("PAUSE\n".getBytes());
        try {
            System.setIn(fake);
            InputStream control = ControlStdin.claim();
            assertSame(fake, control, "控制通道拿到原来的标准输入");
            assertEquals(-1, System.in.read(), "原版控制台读到的是空的");
        } finally {
            System.setIn(saved);
        }
    }
}
