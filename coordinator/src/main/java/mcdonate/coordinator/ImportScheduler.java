package mcdonate.coordinator;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongSupplier;

/**
 * 主线程导入的两个队列（codex-audit 第十七节 B1）：前方的（有截止、手动派的）照原来的软预算先导；
 * 后台的（加速模式）只在主线程闲时导，一次一个（{@link BoostPolicy#backgroundImportAllowed}）。
 * 原来两种结果共用一个先来先导的队列，一个 tile 在 2 核上导入要 42～72 ms，站着开加速一直导后台，MSPT 被顶上去、前方结果排在后台后面。
 * 网络线程往里放，主线程每个 tick 结束时 drain。
 */
final class ImportScheduler {
    private final Queue<Runnable> foreground = new ConcurrentLinkedQueue<>();
    private final Queue<Runnable> background = new ConcurrentLinkedQueue<>();
    /** 离上一次导后台过了几个 tick（只在主线程上用）。 */
    private int ticksSinceBackground = BoostPolicy.BACKGROUND_IMPORT_EVERY_TICKS;
    private long backgroundImported;

    void addForeground(Runnable r) {
        foreground.add(r);
    }

    void addBackground(Runnable r) {
        background.add(r);
    }

    int foregroundPending() {
        return foreground.size();
    }

    int backgroundPending() {
        return background.size();
    }

    long backgroundImported() {
        return backgroundImported;
    }

    /** 一个 tick 的导入。avgTickMs：服务器最近的平均 tick 毫秒数。返回导了几个（前方 + 后台）。 */
    int drain(long budgetNanos, LongSupplier nanoClock, double avgTickMs) {
        ticksSinceBackground++;
        int n = ImportBudget.drain(foreground, budgetNanos, nanoClock);
        if (BoostPolicy.backgroundImportAllowed(n > 0, !foreground.isEmpty(), avgTickMs, ticksSinceBackground)) {
            Runnable r = background.poll();
            if (r != null) {
                r.run();
                ticksSinceBackground = 0;
                backgroundImported++;
                n++;
            }
        }
        return n;
    }
}
