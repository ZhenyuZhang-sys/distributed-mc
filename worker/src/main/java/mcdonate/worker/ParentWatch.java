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
    private final Runnable exit;
    private final AtomicBoolean exited = new AtomicBoolean();

    ParentWatch(BooleanSupplier parentAlive, InputStream stdin, Consumer<String> onCommand, Runnable exit) {
        this.parentAlive = parentAlive;
        this.stdin = stdin;
        this.onCommand = onCommand;
        this.exit = exit;
    }

    /** 按父进程 PID 检查。 */
    static BooleanSupplier pidAlive(long pid) {
        return () -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
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
        while (!exited.get()) {
            if (!parentAlive.getAsBoolean()) {
                exitOnce("父进程没了");
                return;
            }
            try {
                Thread.sleep(CHECK_MS);
            } catch (InterruptedException e) {
                return;
            }
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
        } catch (IOException e) {
            // 管道断了，和 EOF 一样处理
        }
        exitOnce("标准输入 EOF");
    }

    private void exitOnce(String why) {
        if (exited.compareAndSet(false, true)) {
            exit.run();
        }
    }

    boolean exited() {
        return exited.get();
    }
}
