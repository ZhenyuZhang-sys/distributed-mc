package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 11-09（codex-audit A10，kexu 三臂 MSPT max 305 ms）：导入在主线程上每次 8～125 ms（查区域时同步读存档），
 * 几个结果一起到时同一个 tick 里做完。改成排进自己的队列，每个 tick 结束时最多做预算那么久（至少做一个）。
 */
class ImportBudgetTest {
    @Test
    void drainsUntilBudgetButAtLeastOne() {
        AtomicLong clock = new AtomicLong();
        ArrayDeque<Runnable> q = new ArrayDeque<>();
        List<Integer> ran = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int n = i;
            q.add(() -> {
                ran.add(n);
                clock.addAndGet(4_000_000);   // 每个 4 ms
            });
        }
        assertEquals(3, ImportBudget.drain(q, 10_000_000, clock::get), "10 ms 预算：做 3 个（第 3 个开始时还没超）");
        assertEquals(List.of(0, 1, 2), ran);
        assertEquals(1, ImportBudget.drain(q, 0, clock::get), "预算 0 也至少做一个，不会饿死");
        assertEquals(1, q.size());
    }
}
