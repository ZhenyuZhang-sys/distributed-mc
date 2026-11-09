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

    @Test
    void parentQueryFailureMustNotSilentlyKillWatchdog() throws Exception {
        PipedOutputStream keepOpen = new PipedOutputStream();
        CountDownLatch done = new CountDownLatch(1);
        new ParentWatch(() -> { throw new IllegalStateException("query failed"); },
                new PipedInputStream(keepOpen), c -> {}, done::countDown).start();
        try { assertTrue(done.await(3, TimeUnit.SECONDS)); } finally { keepOpen.close(); }
    }

    @Test
    void controlHandlerFailureAlsoStopsWorker() throws Exception {
        PipedOutputStream keepOpen = new PipedOutputStream();
        CountDownLatch done = new CountDownLatch(1);
        new ParentWatch(() -> true, new PipedInputStream(keepOpen), c -> {
            throw new IllegalStateException("handler failed");
        }, done::countDown).start();
        keepOpen.write("STATUS\n".getBytes(StandardCharsets.UTF_8));
        keepOpen.flush();
        try { assertTrue(done.await(3, TimeUnit.SECONDS)); } finally { keepOpen.close(); }
    }

    @Test
    void changedParentBirthIsNotTheSameGame() {
        long pid = ProcessHandle.current().pid();
        long born = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
        assertTrue(ParentWatch.pidAlive(pid, born).getAsBoolean());
        assertTrue(!ParentWatch.pidAlive(pid, born + 5_000).getAsBoolean());
    }

    @Test
    void birthComparedWithTolerance() {
        // 出生时间是游戏 JVM 算了传过来、worker JVM 再算一遍：两边取整可能差一点，差一点不能当成换了进程
        // （差了就是 worker 一起来就自己退出）。PID 复用到出生时间只差 2 秒以内的进程，实际不会发生
        long pid = ProcessHandle.current().pid();
        long born = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
        assertTrue(ParentWatch.pidAlive(pid, born + 500).getAsBoolean());
        assertTrue(ParentWatch.pidAlive(pid, born - 1_500).getAsBoolean());
    }

    @Test
    void unknownBirthFallsBackToAlive() {
        // 读不到出生时间（没权限之类）：只看活着没有，不能直接当成父进程没了
        assertTrue(ParentWatch.sameBirth(java.util.Optional.empty(), 123));
        assertTrue(ParentWatch.sameBirth(java.util.Optional.of(java.time.Instant.ofEpochMilli(1_000)), 1_900));
        assertTrue(!ParentWatch.sameBirth(java.util.Optional.of(java.time.Instant.ofEpochMilli(1_000)), 4_000));
    }

    @Test
    void realKilledParentIsDetectedWhileStdinStaysOpen() throws Exception {
        Process parent = new ProcessBuilder("sh", "-c", "exec sleep 60").start();
        PipedOutputStream keepOpen = new PipedOutputStream();
        CountDownLatch done = new CountDownLatch(1);
        new ParentWatch(ParentWatch.pidAlive(parent.pid()), new PipedInputStream(keepOpen),
                c -> {}, done::countDown).start();
        try {
            parent.destroyForcibly(); parent.waitFor(3, TimeUnit.SECONDS);
            assertTrue(done.await(3, TimeUnit.SECONDS));
        } finally {
            parent.destroyForcibly(); keepOpen.close();
        }
    }
}
