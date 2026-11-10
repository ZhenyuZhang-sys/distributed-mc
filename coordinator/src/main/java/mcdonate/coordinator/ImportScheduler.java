package mcdonate.coordinator;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

/**
 * 主线程导入的两个队列（codex-audit 第十七节 B1）：前方的（有截止、手动派的）照原来的软预算先导；
 * 后台的（加速模式）只在主线程闲时导，一次一个（{@link BoostPolicy#backgroundImportAllowed}）。
 * 原来两种结果共用一个先来先导的队列，一个 tile 在 2 核上导入要 42～72 ms，站着开加速一直导后台，MSPT 被顶上去、前方结果排在后台后面。
 * 网络线程往里放，主线程每个 tick 结束时 drain。
 */
final class ImportScheduler {
    private final Queue<Runnable> foreground = new ConcurrentLinkedQueue<>();
    /** 后台的带着任务编号（-1 不知道）：玩家要了这个 tile 时挪到前方（codex-audit 第十八节 X3）。 */
    private record Bg(long taskId, Runnable job) {}

    private final Queue<Bg> background = new ConcurrentLinkedQueue<>();
    /** 离上一次导后台过了几个 tick（只在主线程上用）。 */
    private int ticksSinceBackground = BoostPolicy.BACKGROUND_IMPORT_EVERY_TICKS;
    private long backgroundImported;

    void addForeground(Runnable r) {
        foreground.add(r);
    }

    void addBackground(Runnable r) {
        addBackground(-1, r);
    }

    void addBackground(long taskId, Runnable r) {
        background.add(new Bg(taskId, r));
    }

    /** reset 时清掉（旧一轮的结果导了也会被丢弃，留着还占背压名额）。 */
    void clear() {
        foreground.clear();
        background.clear();
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
        return drain(budgetNanos, nanoClock, avgTickMs, id -> false);
    }

    /** 同上，nowForeground：这个任务现在是不是前方的了（有了截止）；是的话先挪到前方队列。 */
    int drain(long budgetNanos, LongSupplier nanoClock, double avgTickMs, LongPredicate nowForeground) {
        for (var it = background.iterator(); it.hasNext(); ) {
            Bg b = it.next();
            if (b.taskId() >= 0 && nowForeground.test(b.taskId())) {
                it.remove();
                foreground.add(b.job());
            }
        }
        ticksSinceBackground++;
        int n = ImportBudget.drain(foreground, budgetNanos, nanoClock);
        if (BoostPolicy.backgroundImportAllowed(n > 0, !foreground.isEmpty(), avgTickMs, ticksSinceBackground)) {
            Bg b = background.poll();
            if (b != null) {
                b.job().run();
                ticksSinceBackground = 0;
                backgroundImported++;
                n++;
            }
        }
        return n;
    }
}
