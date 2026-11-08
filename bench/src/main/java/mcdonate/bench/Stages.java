package mcdonate.bench;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * 各生成阶段的计时：次数、CPU 总时间、墙钟总时间、单次最长墙钟时间（都是纳秒）。
 *
 * <p>默认关闭，关闭时 {@link #time} 直接执行，不取任何时间。
 * 不分维度：测试里只有主世界在生成。
 */
public final class Stages {
    /** 阶段名，按生成顺序。light_engine 是光照线程的实际工作（按批次计，不按 chunk）。 */
    public enum Stage {
        STRUCTURE_STARTS, STRUCTURE_REFERENCES, BIOMES, NOISE, SURFACE, CARVERS, FEATURES,
        INITIALIZE_LIGHT, LIGHT, SPAWN, FULL, LIGHT_ENGINE;

        final LongAdder count = new LongAdder();
        final LongAdder cpuNs = new LongAdder();
        final LongAdder wallNs = new LongAdder();
        final LongAccumulator maxWallNs = new LongAccumulator(Math::max, 0);
    }

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static volatile boolean enabled = false;

    private Stages() {}

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /**
     * 在当前线程上执行 work 并计时。
     *
     * @param counted 是否算一次。异步阶段的后台部分只加时间，次数由同步入口算。
     */
    public static <T> T time(Stage stage, boolean counted, Supplier<T> work) {
        if (!enabled) {
            return work.get();
        }
        long cpu0 = THREADS.getCurrentThreadCpuTime();
        long wall0 = System.nanoTime();
        try {
            return work.get();
        } finally {
            long wall = System.nanoTime() - wall0;
            stage.cpuNs.add(THREADS.getCurrentThreadCpuTime() - cpu0);
            stage.wallNs.add(wall);
            stage.maxWallNs.accumulate(wall);
            if (counted) {
                stage.count.increment();
            }
        }
    }

    public static void reset() {
        for (Stage s : Stage.values()) {
            s.count.reset();
            s.cpuNs.reset();
            s.wallNs.reset();
            s.maxWallNs.reset();
        }
    }

    /** {"timing":false,"stages":{"structure_starts":{"count":0,"cpu_ns":0,"wall_ns":0,"max_wall_ns":0},...}} */
    public static String json() {
        StringBuilder sb = new StringBuilder("{\"timing\":").append(enabled).append(",\"stages\":{");
        Stage[] all = Stage.values();
        for (int i = 0; i < all.length; i++) {
            Stage s = all[i];
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(s.name().toLowerCase()).append("\":{\"count\":").append(s.count.sum())
                    .append(",\"cpu_ns\":").append(s.cpuNs.sum())
                    .append(",\"wall_ns\":").append(s.wallNs.sum())
                    .append(",\"max_wall_ns\":").append(s.maxWallNs.get()).append('}');
        }
        return sb.append("}}").toString();
    }
}
