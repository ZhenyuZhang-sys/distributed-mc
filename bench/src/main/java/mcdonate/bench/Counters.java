package mcdonate.bench;

import java.util.concurrent.atomic.LongAdder;

/**
 * 主世界的 chunk 计数。
 *
 * <ul>
 *   <li>full_generated：在这台服务器上生成完、变成完整状态的 chunk。
 *   <li>full_loaded：从存档读出来的完整 chunk。
 *   <li>noise：跑过 noise 阶段的 chunk，包括外圈的半成品。
 * </ul>
 *
 * noise 跑在后台线程上，所以用 LongAdder。
 */
public final class Counters {
    private static final LongAdder FULL_GENERATED = new LongAdder();
    private static final LongAdder FULL_LOADED = new LongAdder();
    private static final LongAdder NOISE = new LongAdder();

    private Counters() {}

    /** chunk 变成完整状态时调用。fromDisk：进来的是存档里读出的现成 chunk。 */
    public static void onFull(boolean fromDisk) {
        (fromDisk ? FULL_LOADED : FULL_GENERATED).increment();
    }

    public static void onNoise() {
        NOISE.increment();
    }

    public static void reset() {
        FULL_GENERATED.reset();
        FULL_LOADED.reset();
        NOISE.reset();
    }

    /** 一行 JSON，比如 {"full_generated":0,"full_loaded":0,"noise":0}。 */
    public static String json() {
        return "{\"full_generated\":" + FULL_GENERATED.sum()
                + ",\"full_loaded\":" + FULL_LOADED.sum()
                + ",\"noise\":" + NOISE.sum() + "}";
    }
}
