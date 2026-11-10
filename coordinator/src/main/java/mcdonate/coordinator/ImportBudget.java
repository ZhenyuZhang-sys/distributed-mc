package mcdonate.coordinator;

import java.util.Queue;
import java.util.function.LongSupplier;

/**
 * 导入的每 tick 预算（11-09，codex-audit A10）：结果排进队列，每个 tick 结束时做到预算用完为止，至少做一个（不会饿死）。
 * 原来每个结果各自 server.execute，几个一起到时同一个 tick 里做完，kexu 三臂里 MSPT 最大 305 ms。纯逻辑。
 */
final class ImportBudget {
    private ImportBudget() {}

    /** 做到用完 budgetNanos（开始每一个之前看一次），至少一个。返回做了几个。 */
    static int drain(Queue<Runnable> q, long budgetNanos, LongSupplier nanoClock) {
        long start = nanoClock.getAsLong();
        int n = 0;
        Runnable r;
        while ((n == 0 || nanoClock.getAsLong() - start < budgetNanos) && (r = q.poll()) != null) {
            r.run();
            n++;
        }
        return n;
    }
}
