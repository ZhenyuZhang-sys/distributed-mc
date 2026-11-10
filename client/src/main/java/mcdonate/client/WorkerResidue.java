package mcdonate.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/** 只检测本worker目录的残留。不凭PID杀进程，不删除Minecraft的session.lock。 */
final class WorkerResidue {
    static final String LOCKED = "有残留 worker 占着世界锁，请先关闭旧 worker";
    private WorkerResidue() {}

    static void check(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path identity = dir.resolve("worker.pid");
        if (Files.isRegularFile(identity)) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(identity)) { p.load(r); }
            catch (IllegalArgumentException malformed) { p.clear(); }
            try {
                long pid = Long.parseLong(p.getProperty("pid", "-1"));
                long born = Long.parseLong(p.getProperty("startMillis", "-1"));
                ProcessHandle h = pid > 0 ? ProcessHandle.of(pid).orElse(null) : null;
                boolean sameDir = dir.toRealPath().toString().equals(p.getProperty("directory"));
                boolean sameBirth = born >= 0 && h != null && h.info().startInstant()
                        .map(t -> t.toEpochMilli() == born).orElse(false);
                if (sameDir && sameBirth && h.isAlive()) {
                    boolean orphan = h.parent().map(ProcessHandle::pid).orElse(-1L) != ProcessHandle.current().pid();
                    throw new IOException((orphan ? "有残留 worker 占用此目录" : "此目录已有 worker 在运行")
                            + "（PID " + pid + "），请先关闭旧 worker");
                }
            } catch (NumberFormatException ignored) {
                // 旧/不完整记录只作为提示线索，真正的世界锁仍要检查。
            }
        }
        Path worldLock = dir.resolve("world/session.lock");
        if (Files.exists(worldLock)) {
            try (FileChannel channel = FileChannel.open(worldLock, StandardOpenOption.WRITE);
                 FileLock held = channel.tryLock()) {
                if (held == null) throw new IOException(LOCKED);
            } catch (OverlappingFileLockException e) {
                throw new IOException(LOCKED, e);
            }
        }
    }

    static void record(Path dir, Process process) throws IOException {
        Properties p = new Properties();
        p.setProperty("pid", Long.toString(process.pid()));
        p.setProperty("startMillis", Long.toString(process.info().startInstant()
                .map(java.time.Instant::toEpochMilli).orElse(-1L)));
        p.setProperty("parentPid", Long.toString(ProcessHandle.current().pid()));
        p.setProperty("directory", dir.toRealPath().toString());
        Path tmp = dir.resolve("worker.pid.tmp");
        try (Writer w = Files.newBufferedWriter(tmp)) { p.store(w, "mc-donate worker identity; no token"); }
        Files.move(tmp, dir.resolve("worker.pid"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        // 保留最后一次启动记录。下次同时核对出生时间和活性，避免旧退出回调误删新记录。
    }
}
