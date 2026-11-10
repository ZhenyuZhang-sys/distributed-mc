package mcdonate.coordinator;

/**
 * 11-09（codex-audit 第十五节）：一个 worker 的在途数随时间的记录。租约开始、结束时调（TaskQueue 的 leaseAdd/leaseRemove），
 * 读出两个累计量：手上有活的时间（毫秒）、在途数 × 时间（毫秒）。两次读数之差相除就是这段忙碌时间里的平均在途数（实测并行度）。
 * 纯逻辑，时间由调用方传，不加锁（由 TaskQueue 的锁保护）。
 */
final class LoadMeter {
    private int count;
    private long last = Long.MIN_VALUE;
    private long busy;
    private long load;

    private void advance(long t) {
        if (last != Long.MIN_VALUE && t > last && count > 0) {
            busy += t - last;
            load += (t - last) * count;
        }
        if (last == Long.MIN_VALUE || t > last) {
            last = t;
        }
    }

    void start(long t) {
        advance(t);
        count++;
    }

    void end(long t) {
        advance(t);
        count = Math.max(0, count - 1);
    }

    /** {手上有活的时间, 在途数 × 时间}，到 t 为止。 */
    long[] read(long t) {
        advance(t);
        return new long[] {busy, load};
    }
}
