package mcdonate.bench.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 飞行输出要能边飞边读（实时地图、演示面板 7.1）。11-09 在 k2 真机上发现：三个文件是带缓冲的流，
 * 20 秒的飞行不到 8 KB，done.json 之前一行都没落盘，实时曲线和实时地图到飞完才有东西。
 * 现在每写一行逐秒行就把三个流都 flush（每秒一次，开销可以忽略）。
 */
class FlightOutputTest {
    /** 在调用线程上直接执行，测试里不用等写线程。 */
    static final class Direct extends AbstractExecutorService {
        public void execute(Runnable r) { r.run(); }
        public void shutdown() {}
        public List<Runnable> shutdownNow() { return List.of(); }
        public boolean isShutdown() { return false; }
        public boolean isTerminated() { return false; }
        public boolean awaitTermination(long t, TimeUnit u) { return true; }
    }

    @Test
    void linesAreOnDiskAfterEachSecondRowBeforeDone(@TempDir Path dir) throws Exception {
        FlightOutput out = new FlightOutput(dir, new Direct());
        out.chunk("{\"t_ms\":1,\"cx\":0,\"cz\":0,\"ev\":\"enter\"}");
        out.provenance("{\"t_ms\":2,\"cx\":0,\"cz\":0,\"kind\":\"vanilla\",\"donor\":-1}");
        out.second("{\"t_s\":1}");
        assertEquals(List.of("{\"t_s\":1}"), Files.readAllLines(dir.resolve("seconds.jsonl")));
        assertEquals(1, Files.readAllLines(dir.resolve("chunks.jsonl")).size());
        assertEquals(1, Files.readAllLines(dir.resolve("provenance.jsonl")).size());
        out.second("{\"t_s\":2}");
        assertEquals(2, Files.readAllLines(dir.resolve("seconds.jsonl")).size());
        assertTrue(Files.notExists(dir.resolve("done.json")));
        out.done("{}");
        assertTrue(Files.exists(dir.resolve("done.json")));
    }
}
