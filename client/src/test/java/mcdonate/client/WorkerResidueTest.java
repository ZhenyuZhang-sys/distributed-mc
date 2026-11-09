package mcdonate.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerResidueTest {
    @Test
    void legacyWorldLockHasExplicitMessage(@TempDir Path dir) throws Exception {
        Path lock = dir.resolve("world/session.lock"); Files.createDirectories(lock.getParent());
        try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock held = ch.lock()) {
            IOException e = assertThrows(IOException.class, () -> WorkerProcess.preflight(dir));
            assertTrue(e.getMessage().contains("世界锁"));
            assertTrue(Files.exists(lock), "不删除Minecraft锁文件");
        }
    }

    @Test
    void recordsIdentityAndRejectsDuplicate(@TempDir Path dir) throws Exception {
        WorkerProcess p = WorkerProcess.start(List.of("sh", "-c", "read -r line"), Map.of(), dir, l -> {});
        try {
            assertTrue(Files.readString(dir.resolve("worker.pid")).contains("pid=" + p.pid()));
            assertThrows(IOException.class, () -> WorkerProcess.preflight(dir));
            assertTrue(p.alive(), "检测只提示，不杀未知进程");
        } finally { p.stop(1, TimeUnit.SECONDS); }
        WorkerProcess.preflight(dir); // 死进程记录不能挡住下次启动
    }

    @Test
    void pidReuseRecordDoesNotBlockOrKillCurrentProcess(@TempDir Path dir) throws Exception {
        long pid = ProcessHandle.current().pid();
        long born = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
        Files.writeString(dir.resolve("worker.pid"), "pid=" + pid + "\nstartMillis=" + (born + 1)
                + "\nparentPid=1\ndirectory=" + dir.toRealPath() + "\n");
        WorkerProcess.preflight(dir);
        assertTrue(ProcessHandle.current().isAlive());
    }

    @Test
    void startupLockRaceRemainsVisibleAfterProcessDies(@TempDir Path dir) throws Exception {
        WorkerProcess p = WorkerProcess.start(List.of("sh", "-c",
                "echo 'world/session.lock: already locked'; exit 1"), Map.of(), dir, l -> {});
        for (int i = 0; i < 100 && p.failureMessage().isEmpty(); i++) Thread.sleep(20);
        assertTrue(p.failureMessage().contains("世界锁"));
        p.stop(1, TimeUnit.SECONDS);
    }
}
