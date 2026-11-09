package mcdonate.worker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 第 9 步：游戏（父进程）没了，worker 自己退出。两道保险：
 * <ol>
 *   <li>每秒检查父进程还在不在（系统属性 mcdonate.parentPid）；
 *   <li>标准输入接在游戏进程上，读到 EOF（父进程没了，管道关闭）就退出。
 * </ol>
 * 标准输入同时是本地控制通道：一行一条命令（PAUSE、RESUME、STATUS、STOP），交给 onCommand。
 * 退出动作只调用一次。
 */
final class ParentWatch {
    static final long CHECK_MS = 1_000;

    private final BooleanSupplier parentAlive;
    private final InputStream stdin;
    private final Consumer<String> onCommand;
    private final Consumer<String> exit;
    private final AtomicBoolean exited = new AtomicBoolean();

    ParentWatch(BooleanSupplier parentAlive, InputStream stdin, Consumer<String> onCommand, Runnable exit) {
        this(parentAlive, stdin, onCommand, why -> exit.run());
    }

    ParentWatch(BooleanSupplier parentAlive, InputStream stdin, Consumer<String> onCommand, Consumer<String> exit) {
        this.parentAlive = parentAlive;
        this.stdin = stdin;
        this.onCommand = onCommand;
        this.exit = exit;
    }

    /** 按父进程 PID 检查。 */
    static BooleanSupplier pidAlive(long pid) {
        return pidAlive(pid, -1);
    }

    /**
     * 11-09（用户 Mac：旧 worker 父进程没了还跑 10 小时）：固定这一次的进程身份，每轮重新 of(pid) 会把复用同一 PID 的新进程当成游戏。
     * expectedStartMillis 是游戏自己算的出生时间（-1 不知道）：差 {@link #BIRTH_TOLERANCE_MS} 以内算同一个，读不到只看活着。
     */
    static BooleanSupplier pidAlive(long pid, long expectedStartMillis) {
        ProcessHandle parent = ProcessHandle.of(pid).orElse(null);
        return () -> parent != null && parent.isAlive()
                && (expectedStartMillis < 0 || sameBirth(parent.info().startInstant(), expectedStartMillis));
    }

    /** 两个 JVM 各算一遍出生时间，取整可能差一点；PID 复用到出生时间只差 2 秒以内的进程，实际不会发生。 */
    static final long BIRTH_TOLERANCE_MS = 2_000;

    static boolean sameBirth(java.util.Optional<java.time.Instant> actual, long expectedMillis) {
        return actual.map(t -> Math.abs(t.toEpochMilli() - expectedMillis) <= BIRTH_TOLERANCE_MS).orElse(true);
    }

    void start() {
        Thread poll = new Thread(this::pollLoop, "mcdonate-parent-watch");
        poll.setDaemon(true);
        poll.start();
        Thread read = new Thread(this::readLoop, "mcdonate-control");
        read.setDaemon(true);
        read.start();
    }

    private void pollLoop() {
        try {
            while (!exited.get()) {
                if (!parentAlive.getAsBoolean()) {
                    exitOnce("父进程没了或身份变了");
                    return;
                }
                Thread.sleep(CHECK_MS);
            }
        } catch (InterruptedException | RuntimeException e) {
            exitOnce("父进程检查异常或被打断");
        } finally {
            if (!exited.get()) exitOnce("父进程看守意外结束");
        }
    }

    private void readLoop() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String cmd = line.trim();
                if (!cmd.isEmpty()) {
                    onCommand.accept(cmd);
                }
            }
        } catch (IOException | RuntimeException e) {
            // 管道断了、控制命令处理失败，都不能静默失去这道保险。
        } finally {
            exitOnce("标准输入 EOF 或控制通道结束");
        }
    }

    private void exitOnce(String why) {
        if (exited.compareAndSet(false, true)) {
            exit.accept(why);
        }
    }

    boolean exited() {
        return exited.get();
    }
}
