package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * codex-audit 第十七节 B1：后台结果和前方结果共用一个导入队列（先来先导），一个 tile 在 2 核上导入要 42～72 ms，
 * 站着开加速持续后台导入会把 MSPT 顶上去、前方结果排在后台后面。改成两个队列：前方的先导（原来的软预算）；
 * 后台的只在主线程闲时导（BoostPolicy.backgroundImportAllowed），一次一个。
 */
class ImportSchedulerTest {
    private final AtomicLong nanos = new AtomicLong();
    private final List<String> done = new ArrayList<>();

    private Runnable job(String name, long ms) {
        return () -> {
            nanos.addAndGet(ms * 1_000_000);
            done.add(name);
        };
    }

    @Test
    void foregroundBeforeBackground() {
        ImportScheduler s = new ImportScheduler();
        s.addBackground(job("bg1", 60));
        s.addForeground(job("fg1", 60));
        s.drain(10_000_000, nanos::get, 20);   // 一个 tick
        assertEquals(List.of("fg1"), done, "前方的先导；这个 tick 导过前方的就不导后台");
        for (int i = 0; i < 4; i++) {
            s.drain(10_000_000, nanos::get, 20);
        }
        assertEquals(List.of("fg1", "bg1"), done, "主线程闲了才导后台");
    }

    @Test
    void backgroundAtMostOneEveryFewTicks() {
        ImportScheduler s = new ImportScheduler();
        for (int i = 0; i < 5; i++) {
            s.addBackground(job("bg" + i, 60));
        }
        for (int t = 0; t < 8; t++) {
            s.drain(10_000_000, nanos::get, 20);
        }
        assertEquals(2, done.size(), "8 个 tick 里最多 2 个后台（每 4 tick 一个）：" + done);
        assertEquals(3, s.backgroundPending());
    }

    @Test
    void busyServerSkipsBackground() {
        ImportScheduler s = new ImportScheduler();
        s.addBackground(job("bg", 60));
        for (int t = 0; t < 10; t++) {
            s.drain(10_000_000, nanos::get, 45);   // 平均 tick 45 ms：忙
        }
        assertEquals(List.of(), done);
        assertEquals(1, s.backgroundPending());
    }

    @Test
    void foregroundKeepsSoftBudget() {
        // 前方的照旧：做到预算用完，至少一个
        ImportScheduler s = new ImportScheduler();
        s.addForeground(job("a", 3));
        s.addForeground(job("b", 3));
        s.addForeground(job("c", 8));
        s.addForeground(job("d", 3));
        s.drain(10_000_000, nanos::get, 20);
        assertEquals(List.of("a", "b", "c"), done);
    }

    @Test
    void pendingBackgroundPromotedWhenItBecomesForeground() {
        // X3：排着导入的后台结果，玩家要了这个 tile（有了截止）：挪到前方队列，服务器忙也照样导
        ImportScheduler s = new ImportScheduler();
        boolean[] fg = {false};
        s.addBackground(7, job("bg7", 60));
        s.drain(10_000_000, nanos::get, 45, id -> fg[0]);
        assertEquals(List.of(), done, "服务器忙：后台不导");
        fg[0] = true;
        s.drain(10_000_000, nanos::get, 45, id -> fg[0]);
        assertEquals(List.of("bg7"), done, "变成前方的：照前方导");
    }

    @Test
    void clearDropsEverything() {
        ImportScheduler s = new ImportScheduler();
        s.addForeground(job("a", 1));
        s.addBackground(3, job("b", 1));
        s.clear();
        assertEquals(0, s.foregroundPending() + s.backgroundPending(), "reset 时清掉，旧的结果不占背压名额");
    }
}
