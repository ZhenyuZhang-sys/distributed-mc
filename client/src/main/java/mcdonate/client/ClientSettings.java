package mcdonate.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * 第 9 步 e：客户端 mod 的设置，存在 {@code .minecraft/config/mc-donate-settings.properties}。
 * 和同意记录（{@link Consent}）分开存：同意是玩家本人点的，设置可以随便改。
 *
 * <ul>
 *   <li>enabled：帮服务器生成地形（默认关）。打开要先同意（第一次打开时弹出同意界面）。
 *   <li>threads：worker 用几个生成线程（1～{@link #MAX_THREADS}，默认 2）。worker 运行中改不了，下次启动生效。
 *   <li>memoryMb：worker 最多用多少内存（{@link #MIN_MEMORY_MB}～8192，默认 2048）。
 *   <li>frameTargetMs：帧时间目标（默认 50 ms，就是 20 帧/秒），p99 超过它 2 秒就暂停 worker。
 * </ul>
 */
public record ClientSettings(boolean enabled, int threads, int memoryMb, int frameTargetMs) {
    public static final int MAX_THREADS = 8;
    public static final int MIN_MEMORY_MB = 1024;
    public static final int MAX_MEMORY_MB = 8192;
    public static final ClientSettings DEFAULTS = new ClientSettings(false, 2, 2048, 50);

    public ClientSettings {
        threads = Math.clamp(threads, 1, MAX_THREADS);
        memoryMb = Math.clamp(memoryMb, MIN_MEMORY_MB, MAX_MEMORY_MB);
        frameTargetMs = Math.clamp(frameTargetMs, 10, 200);
    }

    public ClientSettings withEnabled(boolean v) {
        return new ClientSettings(v, threads, memoryMb, frameTargetMs);
    }

    public ClientSettings withThreads(int v) {
        return new ClientSettings(enabled, v, memoryMb, frameTargetMs);
    }

    /** 设置界面里帧时间目标的选项（毫秒）：33（30 帧）、50（20 帧，默认）、100、200。 */
    public static final java.util.List<Integer> FRAME_TARGETS = java.util.List.of(33, 50, 100, 200);

    public ClientSettings withFrameTargetMs(int v) {
        return new ClientSettings(enabled, threads, memoryMb, v);
    }

    public ClientSettings withMemoryMb(int v) {
        return new ClientSettings(enabled, threads, v, frameTargetMs);
    }

    /** 读设置；没有文件、读不懂的项用默认值。 */
    public static ClientSettings load(Path file) {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            return DEFAULTS;
        }
        return new ClientSettings(Boolean.parseBoolean(p.getProperty("enabled", "false")),
                intOr(p, "threads", DEFAULTS.threads), intOr(p, "memoryMb", DEFAULTS.memoryMb),
                intOr(p, "frameTargetMs", DEFAULTS.frameTargetMs));
    }

    private static int intOr(Properties p, String key, int def) {
        try {
            return Integer.parseInt(p.getProperty(key, "").trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public void save(Path file) throws IOException {
        Properties p = new Properties();
        p.setProperty("enabled", Boolean.toString(enabled));
        p.setProperty("threads", Integer.toString(threads));
        p.setProperty("memoryMb", Integer.toString(memoryMb));
        p.setProperty("frameTargetMs", Integer.toString(frameTargetMs));
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            p.store(w, "mc-donate 客户端设置");
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
