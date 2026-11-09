package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 11-09 走廊邻居补齐（corridorNeighborPad）：直线飞时走廊边上的 chunk 要等两侧的邻居 FULL 才发给客户端
 * （codex-pipe 真客户端：一个 chunk 61.7 秒 FULL、93.2 秒才发）。走廊外紧挨走廊 tile、自己也会进视野的 tile，
 * 跟着走廊邻居一起派（同一档、取邻居的截止时间），只多一列。
 * 下面都是沿 +x 飞：lateral 是 tile 中心离飞行线的 chunk 数，走廊半宽 TaskQueue.CORRIDOR_CHUNKS（6）。
 */
class TaskQueuePadTest {
    private final AtomicLong now = new AtomicLong(1_000_000);

    private TaskQueue queue(int pad) {
        TaskQueue q = new TaskQueue(now::get, 30_000, 3, new Features("deadline"));
        q.setNeighborPad(pad);
        return q;
    }

    /** 走廊里两个（近的 5,0 和远的 7,0），5,0 旁边一个走廊外的 5,1。 */
    private TaskQueue line(int pad) {
        TaskQueue q = queue(pad);
        q.enqueue(5, 0);
        q.enqueue(7, 0);
        q.enqueue(5, 1);
        q.setDue(5, 0, now.get() + 8_000, now.get() + 6_000, 1);
        q.setDue(7, 0, now.get() + 12_000, now.get() + 10_000, 1);
        q.setDue(5, 1, now.get() + 14_000, now.get() + 12_000, 8);   // 走廊外一列，也会进视野
        return q;
    }

    /** 同一个 worker 连领 n 个（"包一片"会把派出 tile 的邻居软认主给它，换 worker 领不到）。 */
    private static TaskQueue.Assignment next(TaskQueue q) {
        return q.assign(1, 8, due -> true).orElseThrow();
    }

    private static int[] order(TaskQueue q, int n) {
        int[] out = new int[n * 2];
        for (int i = 0; i < n; i++) {
            TaskQueue.Assignment a = next(q);
            out[2 * i] = a.tx();
            out[2 * i + 1] = a.tz();
        }
        return out;
    }

    @Test
    void offKeepsCorridorFirst() {
        assertEquals(List.of(5, 0, 7, 0, 5, 1), toList(order(line(0), 3)), "关着：走廊里的全派完才派两侧");
    }

    @Test
    void padGoesRightAfterItsCorridorNeighbour() {
        assertEquals(List.of(5, 0, 5, 1, 7, 0), toList(order(line(1), 3)),
                "开着：5,1 用 5,0 的截止时间，排在更远的走廊 tile 7,0 前面");
    }

    @Test
    void padUsesNeighbourDeadlineEvenAfterNeighbourLeased() {
        TaskQueue q = line(1);
        assertEquals(5, next(q).tx());   // 5,0 派出去了，还在租
        TaskQueue.Assignment next = next(q);
        assertEquals(List.of(5, 1), List.of(next.tx(), next.tz()), "邻居在做了，补齐的照样跟上");
    }

    @Test
    void padUsesNeighbourDeadlineAfterNeighbourDone() {
        TaskQueue q = line(1);
        long id = next(q).taskId();
        assertEquals(TaskQueue.Action.IMPORT, q.onResult(1, id, true).action());
        TaskQueue.Assignment next = next(q);
        assertEquals(List.of(5, 1), List.of(next.tx(), next.tz()), "邻居已经交回：边上的 chunk 正等这一列");
    }

    @Test
    void notAdjacentToCorridorNotPadded() {
        TaskQueue q = queue(1);
        q.enqueue(7, 0);
        q.enqueue(2, 2);   // 离走廊 tile 7,0 隔得远
        q.setDue(7, 0, now.get() + 12_000, now.get() + 10_000, 1);
        q.setDue(2, 2, now.get() + 5_000, now.get() + 4_000, 8);
        assertEquals(List.of(7, 0, 2, 2), toList(order(q, 2)), "旁边没有走廊 tile 的照旧排在后面");
    }

    @Test
    void onlyOneColumn() {
        TaskQueue q = queue(1);
        q.enqueue(5, 0);
        q.enqueue(7, 0);
        q.enqueue(5, 2);
        q.setDue(5, 0, now.get() + 8_000, now.get() + 6_000, 1);
        q.setDue(7, 0, now.get() + 12_000, now.get() + 10_000, 1);
        q.setDue(5, 2, now.get() + 14_000, now.get() + 12_000, 12);   // 走廊外第二列（不挨着走廊 tile）
        assertEquals(List.of(5, 0, 7, 0, 5, 2), toList(order(q, 3)), "只补一列");
        // 第二列即使挨着走廊 tile（对角），离飞行线也超过 走廊 + 一个 tile，不补
        TaskQueue r = queue(1);
        r.enqueue(5, 0);
        r.enqueue(7, 0);
        r.enqueue(6, 1);
        r.setDue(5, 0, now.get() + 8_000, now.get() + 6_000, 1);
        r.setDue(7, 0, now.get() + 12_000, now.get() + 10_000, 1);
        r.setDue(6, 1, now.get() + 14_000, now.get() + 12_000, TaskQueue.CORRIDOR_CHUNKS + 5);
        assertEquals(List.of(5, 0, 7, 0, 6, 1), toList(order(r, 3)));
    }

    @Test
    void padNeedsItsOwnDeadline() {
        TaskQueue q = queue(1);
        q.enqueue(5, 0);
        q.enqueue(5, 1);
        q.setDue(5, 0, now.get() + 8_000, now.get() + 6_000, 1);
        q.setDue(5, 1, Long.MAX_VALUE, Long.MAX_VALUE, 8);   // 不会进视野
        next(q);
        assertTrue(q.assign(1, 8, due -> true).isEmpty(), "自己不会进视野的不补（侧后方本来就不派）");
    }

    @Test
    void pruneStillUsesOwnDeadline() {
        // 排得靠前了，但"来不来得及"仍按自己的截止时间。donor 4.5 秒交回、每 3 秒一个：
        // 第 1 个 5,0 在 4.5 秒做完（限 5 秒），第 2 个 5,1 在 6 秒做完：按邻居的 5 秒算会被剪，按自己的 11 秒不该剪
        TaskQueue q = line(1);
        List<Long> dropped = q.pruneHopeless(List.of(new TaskQueue.DonorEstimate(4_500, 1.0 / 3_000, 0)));
        assertTrue(dropped.isEmpty(), "都来得及：" + dropped);
    }

    @Test
    void countsPaddedLeases() {
        TaskQueue q = line(1);
        order(q, 3);
        assertEquals(1, q.paddedLeases(), "补齐派出的个数（A/B 看）");
    }

    private static List<Integer> toList(int[] a) {
        return java.util.Arrays.stream(a).boxed().toList();
    }
}
