package mcdonate.bench;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 主世界的 chunk 计数。
 *
 * <ul>
 *   <li>full_generated：在这台服务器上从头生成完、变成完整状态的 chunk。
 *   <li>full_loaded：从存档读出来的成品 chunk。
 *   <li>full_resumed：从存档读出来的半成品，被服务器接着做完（导入的 tile 就是这种）。
 *   <li>noise、features：这台服务器跑了几次这两个阶段（包括外圈的半成品）。
 * </ul>
 *
 * 生成阶段跑在后台线程上，所以用 LongAdder。
 * 按 chunk 记录（{@link #ran}）默认关闭，用 /bench track on 打开，只给测试用。
 */
public final class Counters {
    /** chunk 变成完整状态时，它是从哪来的。 */
    public enum FullKind { GENERATED, LOADED, RESUMED }

    private static final LongAdder FULL_GENERATED = new LongAdder();
    private static final LongAdder FULL_LOADED = new LongAdder();
    private static final LongAdder FULL_RESUMED = new LongAdder();
    private static final LongAdder NOISE = new LongAdder();
    private static final LongAdder FEATURES = new LongAdder();

    private static volatile boolean tracking = false;
    private static final Map<Long, Integer> NOISE_AT = new ConcurrentHashMap<>();
    private static final Map<Long, Integer> FEATURES_AT = new ConcurrentHashMap<>();

    private Counters() {}

    public static void onFull(FullKind kind) {
        switch (kind) {
            case GENERATED -> FULL_GENERATED.increment();
            case LOADED -> FULL_LOADED.increment();
            case RESUMED -> FULL_RESUMED.increment();
        }
    }

    public static long fullGenerated() {
        return FULL_GENERATED.sum();
    }

    public static long fullLoaded() {
        return FULL_LOADED.sum();
    }

    public static long fullResumed() {
        return FULL_RESUMED.sum();
    }

    public static void onNoise(int x, int z) {
        NOISE.increment();
        if (tracking) {
            NOISE_AT.merge(key(x, z), 1, Integer::sum);
        }
    }

    public static void onFeatures(int x, int z) {
        FEATURES.increment();
        if (tracking) {
            FEATURES_AT.merge(key(x, z), 1, Integer::sum);
        }
    }

    public static void setTracking(boolean on) {
        tracking = on;
    }

    public static void reset() {
        FULL_GENERATED.reset();
        FULL_LOADED.reset();
        FULL_RESUMED.reset();
        NOISE.reset();
        FEATURES.reset();
        NOISE_AT.clear();
        FEATURES_AT.clear();
    }

    /** 一行 JSON：五个计数。 */
    public static String json() {
        return "{\"full_generated\":" + FULL_GENERATED.sum()
                + ",\"full_loaded\":" + FULL_LOADED.sum()
                + ",\"full_resumed\":" + FULL_RESUMED.sum()
                + ",\"noise\":" + NOISE.sum()
                + ",\"features\":" + FEATURES.sum() + "}";
    }

    /** 这个 chunk 自上次 reset 起跑过几次 noise、features（要先 track on）。 */
    public static String ran(int x, int z) {
        long k = key(x, z);
        return "{\"tracking\":" + tracking + ",\"noise\":" + NOISE_AT.getOrDefault(k, 0)
                + ",\"features\":" + FEATURES_AT.getOrDefault(k, 0) + "}";
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
