package mcdonate.common;

import net.minecraft.nbt.CompoundTag;

/**
 * worker 在结果的 NBT 里带的时间点（毫秒时间戳，worker 的时钟）：收到任务、开始生成、生成完、打包完。
 * coordinator 拿它拆"派出到交回"的耗时（TaskTimeline）。旧的 coordinator 不认这个键，照常忽略。
 * 不同机器时钟不一致时 worker 上几段之间的差仍然准，和 coordinator 时间点相减的两段（派出、回传）会偏。
 */
public final class TaskTiming {
    public static final String KEY = "timing";

    private TaskTiming() {}

    public static void stamp(CompoundTag tile, long received, long genStart, long genEnd, long packed) {
        tile.putLongArray(KEY, new long[] {received, genStart, genEnd, packed});
    }

    /** {收到, 开始生成, 生成完, 打包完}；没有返回 null。 */
    public static long[] read(CompoundTag tile) {
        long[] t = tile.getLongArray(KEY);
        return t.length == 4 ? t : null;
    }
}
