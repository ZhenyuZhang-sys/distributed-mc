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
 *   <li>threads：整台 donor 的总生成线程（1～{@link #MAX_THREADS}，默认 2）。worker 运行中改不了，下次启动生效。
 *   <li>memoryMb：整台 donor 的总堆预算（{@link #MIN_MEMORY_MB}～8192，默认 2048）。
 *   <li>workerCount：0按总线程每4个起一个，最多3个；显式1～3。线程/堆预算均分，每进程至少1024MB。
 *   <li>frameTargetMs：帧时间目标（默认 50 ms，就是 20 帧/秒），最近 10 秒帧时间的中位数超过它 5 秒就暂停 worker。
 * </ul>
 */
public record ClientSettings(boolean enabled, int threads, int memoryMb, int frameTargetMs, int workerCount) {
    public static final int MAX_THREADS = 8;
    public static final int MIN_MEMORY_MB = 1024;
    public static final int MAX_MEMORY_MB = 8192;
    public static final ClientSettings DEFAULTS = new ClientSettings(false, 2, 2048, 50);

    /** workerCount=0：按总线程数每4个一个进程，最多3个；线程和内存都是整台 donor 的总预算。 */
    public ClientSettings(boolean enabled, int threads, int memoryMb, int frameTargetMs) {
        this(enabled, threads, memoryMb, frameTargetMs, 0);
    }

    public ClientSettings {
        workerCount = Math.clamp(workerCount, 0, 3);
        threads = Math.clamp(threads, 1, MAX_THREADS);
        memoryMb = Math.clamp(memoryMb, MIN_MEMORY_MB, MAX_MEMORY_MB);
        frameTargetMs = Math.clamp(frameTargetMs, 10, 200);
    }

    public ClientSettings withEnabled(boolean v) {
        return new ClientSettings(v, threads, memoryMb, frameTargetMs, workerCount);
    }

    public ClientSettings withThreads(int v) {
        return new ClientSettings(enabled, v, memoryMb, frameTargetMs, workerCount);
    }

    /** 设置界面里帧时间目标的选项（毫秒）：33（30 帧）、50（20 帧，默认）、100、200。 */
    public static final java.util.List<Integer> FRAME_TARGETS = java.util.List.of(33, 50, 100, 200);

    public ClientSettings withFrameTargetMs(int v) {
        return new ClientSettings(enabled, threads, memoryMb, v, workerCount);
    }

    public ClientSettings withMemoryMb(int v) {
        return new ClientSettings(enabled, threads, v, frameTargetMs, workerCount);
    }

    public ClientSettings withWorkerCount(int count) {
        return new ClientSettings(enabled, threads, memoryMb, frameTargetMs, count);
    }

    public int effectiveWorkerCount() {
        int requested = workerCount == 0 ? (threads + 3) / 4 : workerCount;
        return Math.max(1, Math.min(requested, Math.min(threads, memoryMb / MIN_MEMORY_MB)));
    }

    /** 每个子进程分得的预算。目录/邀请/控制通道都按同一个槽位路由。 */
    public ClientSettings forWorker(int slot) {
        int count = effectiveWorkerCount();
        if (slot < 0 || slot >= 3) throw new IllegalArgumentException("worker slot 越界");
        if (slot >= count) return new ClientSettings(false, 1, MIN_MEMORY_MB, frameTargetMs, 1);
        return new ClientSettings(enabled, threads / count + (slot < threads % count ? 1 : 0),
                memoryMb / count + (slot < memoryMb % count ? 1 : 0), frameTargetMs, 1);
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
                intOr(p, "frameTargetMs", DEFAULTS.frameTargetMs), intOr(p, "workerCount", 0));
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
        p.setProperty("workerCount", Integer.toString(workerCount));
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
