package mcdonate.bench.flight;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一次飞行的输出文件（bench-runs/&lt;run&gt;/）。主线程只往写线程排任务，按提交顺序写；
 * done.json 最后写（先写临时文件再改名），Python 看到它就说明前面的行都已落盘。
 */
final class FlightOutput {
    private static final Logger LOG = LoggerFactory.getLogger("mcdonate-bench");

    private final Path dir;
    private final ExecutorService writer;
    private BufferedWriter seconds, chunks;

    FlightOutput(Path dir, ExecutorService writer) {
        this.dir = dir;
        this.writer = writer;
    }

    void runJson(String json) {
        writer.execute(() -> write("run.json", json));
    }

    void second(String line) {
        writer.execute(() -> {
            try {
                if (seconds == null) seconds = Files.newBufferedWriter(dir.resolve("seconds.jsonl"), StandardCharsets.UTF_8);
                seconds.write(line);
                seconds.write('\n');
            } catch (IOException e) {
                LOG.error("写 seconds.jsonl 失败：{}", dir, e);
            }
        });
    }

    void chunk(String line) {
        writer.execute(() -> {
            try {
                if (chunks == null) chunks = Files.newBufferedWriter(dir.resolve("chunks.jsonl"), StandardCharsets.UTF_8);
                chunks.write(line);
                chunks.write('\n');
            } catch (IOException e) {
                LOG.error("写 chunks.jsonl 失败：{}", dir, e);
            }
        });
    }

    /** 关掉两个流（没有行也建空文件），再写 done.json。 */
    void done(String json) {
        writer.execute(() -> {
            try {
                if (seconds == null) seconds = Files.newBufferedWriter(dir.resolve("seconds.jsonl"), StandardCharsets.UTF_8);
                if (chunks == null) chunks = Files.newBufferedWriter(dir.resolve("chunks.jsonl"), StandardCharsets.UTF_8);
                seconds.close();
                chunks.close();
            } catch (IOException e) {
                LOG.error("关闭飞行输出失败：{}", dir, e);
            }
            write("done.json", json);
        });
    }

    private void write(String name, String json) {
        try {
            Path tmp = dir.resolve(name + ".tmp");
            Files.writeString(tmp, json + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.error("写 {} 失败：{}", name, dir, e);
        }
    }
}
