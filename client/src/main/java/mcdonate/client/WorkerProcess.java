package mcdonate.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 第 9 步 c：worker 子进程（docs/step9-plan.md 第 2 节）。
 *
 * <ul>
 *   <li>标准输入是控制通道：{@link #send} 写一行（PAUSE、RESUME、STOP、STATUS、TOKEN）。游戏进程没了，管道关闭，worker 读到 EOF 自己退出。
 *   <li>标准输出（和错误输出合在一起）全部记进 worker 目录的 worker.log；以 {@code MCDONATE } 开头的行去掉前缀交给 onLine（给游戏看的）。
 *   <li>token 走环境变量，不放命令行（同一台机器上别的用户能用 ps 看到命令行）。
 *   <li>{@link #stop}：发 STOP 等宽限期，还在就强杀。
 * </ul>
 */
public final class WorkerProcess {
    static final String PREFIX = "MCDONATE ";

    private final Process process;
    private final Writer stdin;
    private volatile String failure = "";

    private WorkerProcess(Process process) {
        this.process = process;
        this.stdin = new java.io.OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
    }

    public static WorkerProcess start(List<String> command, Map<String, String> env, Path dir, Consumer<String> onLine)
            throws IOException {
        preflight(dir);
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().putAll(env);
        WorkerProcess w = new WorkerProcess(pb.start());
        try {
            WorkerResidue.record(dir, w.process);
        } catch (IOException e) {
            w.process.descendants().forEach(ProcessHandle::destroyForcibly);
            w.process.destroyForcibly();
            throw e;
        }
        Path log = dir.resolve("worker.log");
        Thread reader = new Thread(() -> w.pump(log, onLine), "mcdonate-worker-output");
        reader.setDaemon(true);
        reader.start();
        return w;
    }

    private void pump(Path log, Consumer<String> onLine) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = Files.newOutputStream(log, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("session.lock") && line.contains("already locked")) {
                    failure = WorkerResidue.LOCKED;
                }
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (line.startsWith(PREFIX)) {
                    onLine.accept(line.substring(PREFIX.length()));
                }
            }
        } catch (IOException e) {
            // 进程没了，管道断了
        }
    }

    /** 给 worker 发一行命令；进程已经没了就忽略。 */
    public synchronized void send(String line) {
        try {
            stdin.write(line + "\n");
            stdin.flush();
        } catch (IOException e) {
            // 管道已经关了
        }
    }

    public boolean alive() {
        return process.isAlive();
    }

    public static void preflight(Path dir) throws IOException {
        WorkerResidue.check(dir);
    }

    public String failureMessage() {
        return failure;
    }

    public long pid() {
        return process.pid();
    }

    /** 发 STOP，等 grace；自己退出了返回 true，到时间还在就强杀（连子进程一起）返回 false。 */
    public boolean stop(long grace, TimeUnit unit) throws InterruptedException {
        if (!process.isAlive()) {
            return true;
        }
        java.util.concurrent.atomic.AtomicBoolean forced = new java.util.concurrent.atomic.AtomicBoolean();
        long nanos = unit.toNanos(grace);
        long deadline = System.nanoTime() + nanos;
        Runnable force = () -> {
            if (process.isAlive()) {
                forced.set(true);
                try { process.descendants().forEach(ProcessHandle::destroyForcibly); }
                finally { process.destroyForcibly(); }
            }
        };
        Thread timer = new Thread(() -> {
            try { TimeUnit.NANOSECONDS.sleep(Math.max(0, nanos)); }
            catch (InterruptedException cancelled) { return; }
            force.run();
        }, "mcdonate-worker-stop-deadline");
        timer.setDaemon(true);
        timer.start(); // STOP写入或另一个send持锁也可能堵住，必须先限时。
        try {
            send("STOP");
            if (!process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) force.run();
            process.waitFor(5, TimeUnit.SECONDS);
            return !forced.get() && !process.isAlive();
        } catch (InterruptedException e) {
            force.run();
            throw e;
        } finally {
            timer.interrupt();
        }
    }
}
