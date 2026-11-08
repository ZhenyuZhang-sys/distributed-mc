package mcdonate.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 第 9 步：父进程没了，worker 自己退出。 */
class ParentWatchTest {
    @Test
    void exitsOnStdinEof() throws Exception {
        PipedOutputStream parent = new PipedOutputStream();
        CountDownLatch done = new CountDownLatch(1);
        new ParentWatch(() -> true, new PipedInputStream(parent), c -> {}, done::countDown).start();
        parent.close(); // 父进程没了，管道关闭
        assertTrue(done.await(3, TimeUnit.SECONDS));
    }

    @Test
    void exitsWhenParentGone() throws Exception {
        AtomicBoolean alive = new AtomicBoolean(true);
        PipedOutputStream keepOpen = new PipedOutputStream();
        CountDownLatch done = new CountDownLatch(1);
        new ParentWatch(alive::get, new PipedInputStream(keepOpen), c -> {}, done::countDown).start();
        alive.set(false); // kill -9：PID 没了，但管道可能还没关
        assertTrue(done.await(3, TimeUnit.SECONDS));
        keepOpen.close();
    }

    @Test
    void commandsPassedThroughAndExitOnlyOnce() throws Exception {
        PipedOutputStream parent = new PipedOutputStream();
        List<String> got = new CopyOnWriteArrayList<>();
        AtomicInteger exits = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean alive = new AtomicBoolean(true);
        new ParentWatch(alive::get, new PipedInputStream(parent), got::add, () -> {
            exits.incrementAndGet();
            done.countDown();
        }).start();
        parent.write("PAUSE\n\nRESUME\n".getBytes(StandardCharsets.UTF_8));
        parent.flush();
        alive.set(false);
        parent.close(); // 两道保险同时触发
        assertTrue(done.await(3, TimeUnit.SECONDS));
        Thread.sleep(1_500); // 等另一道保险也走完，确认没有第二次退出
        assertEquals(1, exits.get());
        assertEquals(List.of("PAUSE", "RESUME"), got);
    }

    @Test
    void pidAliveForThisProcess() throws IOException {
        assertTrue(ParentWatch.pidAlive(ProcessHandle.current().pid()).getAsBoolean());
    }
}
