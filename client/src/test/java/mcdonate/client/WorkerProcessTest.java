package mcdonate.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 第 9 步 c：worker 子进程（用一个 sh 脚本冒充 worker）。 */
class WorkerProcessTest {
    // 冒充 worker：打一行普通日志、一行给游戏看的、回显收到的命令，收到 STOP 就退出
    private static final String FAKE = """
            echo "[12:00:00] [Server thread/INFO]: Done"
            echo "MCDONATE NEED_TOKEN"
            echo "env token=$MCDONATE_TOKEN"
            while read -r line; do
              echo "MCDONATE GOT $line"
              [ "$line" = "STOP" ] && exit 0
            done
            """;

    @Test
    void controlLinesTokenEnvAndStop(@TempDir Path dir) throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        WorkerProcess p = WorkerProcess.start(List.of("sh", "-c", FAKE), Map.of("MCDONATE_TOKEN", "tok1"), dir, lines::add);
        assertTrue(waitFor(() -> lines.contains("NEED_TOKEN")), lines.toString());
        p.send("PAUSE");
        assertTrue(waitFor(() -> lines.contains("GOT PAUSE")), lines.toString());
        assertTrue(p.stop(5, TimeUnit.SECONDS), "收到 STOP 自己退出，不用强杀");
        assertFalse(p.alive());
        String log = Files.readString(dir.resolve("worker.log"));
        assertTrue(log.contains("Done") && log.contains("env token=tok1"), "全部输出记进 worker.log");
        assertFalse(lines.stream().anyMatch(l -> l.contains("Done")), "普通日志不当控制行");
    }

    @Test
    void stopForcesAfterGrace(@TempDir Path dir) throws Exception {
        WorkerProcess p = WorkerProcess.start(List.of("sh", "-c", "trap '' TERM; sleep 60"), Map.of(), dir, l -> { });
        assertFalse(p.stop(1, TimeUnit.SECONDS), "不理 STOP 的，宽限期过了强杀");
        assertFalse(p.alive());
    }

    private static boolean waitFor(java.util.function.BooleanSupplier c) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (c.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
