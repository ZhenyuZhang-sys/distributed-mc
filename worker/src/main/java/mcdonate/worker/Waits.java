package mcdonate.worker;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * 主线程上等 chunk（11-09）：一边处理主线程任务一边等，超时抛异常。
 * 原来用原版的 getChunk(..., true) 等，它没有超时：用户真机上一个 chunk 的生成被异常打断以后，主线程永远等下去，
 * worker 卡了 5 分多钟，心跳照发，coordinator 一直给它续租。
 */
final class Waits {
    private Waits() {}

    /**
     * 等到 done 为真。poll：处理一个主线程任务，没有任务可做时返回 false（这时睡一小会儿）。
     *
     * @throws IllegalStateException 超过 timeoutMs 还没好
     */
    static void until(BooleanSupplier done, BooleanSupplier poll, long timeoutMs, LongSupplier clock, String what) {
        long deadline = clock.getAsLong() + timeoutMs;
        while (!done.getAsBoolean()) {
            if (clock.getAsLong() > deadline) {
                throw new IllegalStateException("等 " + what + " 超过 " + timeoutMs / 1000 + " 秒，放弃这个任务");
            }
            if (!poll.getAsBoolean()) {
                java.util.concurrent.locks.LockSupport.parkNanos(200_000);
            }
        }
    }

    /** 一个 tile 最多等多久：最近平均耗时的 10 倍，最少 30 秒；还没有样本（avgMs < 0）时 30 秒。 */
    static long tileTimeoutMs(long avgMs) {
        return Math.max(30_000, avgMs * 10);
    }
}
