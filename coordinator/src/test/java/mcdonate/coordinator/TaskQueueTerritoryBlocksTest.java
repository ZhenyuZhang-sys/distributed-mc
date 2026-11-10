package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import mcdonate.coordinator.TaskQueue.State;
import org.junit.jupiter.api.Test;

/**
 * 11-10 用户在演示服务器上两人一起飞（Mac M5 + Windows，1 核服务器）："两人一起跑前面一段比一个人还卡"。
 * 两个 donor 在线时领地被交错切开（codex-server 量到 86 片），接缝多，夹在两个 donor 中间的 tile 全落给 1 核服务器。
 * 要：领地按连续大块分，两块之间接缝短；手动 /donor enqueue 的一批也一样。
 */
class TaskQueueTerritoryBlocksTest {
    /** stuck：最后还在排队、谁也领不了的（也落给服务器）。 */
    record Outcome(int done, int dropped, int stuck, int pieces, int tiles) {}

    /** 按 donor 世界数 DONE 的 tile 连成几片（上下左右相邻算一片）。 */
    static int pieces(Map<Long, String> doneWorld) {
        int n = 0;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (var e : doneWorld.entrySet()) {
            if (!seen.add(e.getKey())) {
                continue;
            }
            n++;
            ArrayDeque<Long> q = new ArrayDeque<>(List.of(e.getKey()));
            while (!q.isEmpty()) {
                long k = q.poll();
                int x = (int) (k >> 32), z = (int) k;
                for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    long nk = key(x + d[0], z + d[1]);
                    if (e.getValue().equals(doneWorld.get(nk)) && seen.add(nk)) {
                        q.add(nk);
                    }
                }
            }
        }
        return n;
    }

    static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    /**
     * 两个 donor（W 快、V 慢），各最多 2 个在途，每 200 毫秒走一步。cols × width 个 tile；
     * corridor：玩家沿 x 方向往前飞，每 3 秒前进一列，前方 4 列入队、截止按列；否则一开始全部入队、没有截止（手动 enqueue 一批）。
     */
    static Outcome run(int cols, int width, boolean corridor) {
        AtomicLong now = new AtomicLong(1_000_000);
        TaskQueue q = new TaskQueue(now::get, 30_000, 3, new Features("deadline,territory"));
        q.setWorkerWorld(1, "W");
        q.setWorkerWorld(2, "V");
        long[] latency = {0, 1_500, 3_000};
        Map<Long, long[]> running = new HashMap<>();   // 任务 → {worker, 做完时刻}
        Map<Long, int[]> tile = new HashMap<>();
        List<int[]> all = new ArrayList<>();
        for (int x = 0; x < cols; x++) {
            for (int z = 0; z < width; z++) {
                all.add(new int[] {x, z});
            }
        }
        long start = now.get();
        int enqueuedCols = 0;
        if (!corridor) {
            for (int[] t : all) {
                q.enqueue(t[0], t[1]);
            }
            enqueuedCols = cols;
        }
        for (int step = 0; step < 2_000; step++) {
            long t = now.get();
            if (corridor) {
                int playerCol = (int) ((t - start) / 3_000);
                while (enqueuedCols < Math.min(cols, playerCol + 4)) {
                    for (int z = 0; z < width; z++) {
                        q.enqueue(enqueuedCols, z);
                        long due = start + (enqueuedCols + 1) * 3_000L + 6_000;
                        q.setDue(enqueuedCols, z, due, due, z);
                    }
                    enqueuedCols++;
                }
            }
            boolean assigned = false, idle = running.isEmpty();
            for (int w = 1; w <= 2; w++) {
                var a = q.assign(w, 2, d -> true);
                while (a.isPresent()) {
                    assigned = true;
                    running.put(a.get().taskId(), new long[] {w, t + latency[w]});
                    tile.put(a.get().taskId(), new int[] {a.get().tx(), a.get().tz()});
                    a = q.assign(w, 2, d -> true);
                }
            }
            for (var it = running.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (e.getValue()[1] <= t) {
                    int w = (int) e.getValue()[0];
                    q.onResult(w, e.getKey(), true);
                    q.markImported(e.getKey(), w == 1 ? "W" : "V");
                    it.remove();
                }
            }
            now.addAndGet(200);
            if (enqueuedCols == cols && idle && !assigned) {
                break;   // 都做完了，剩下的谁也领不了
            }
        }
        Map<Long, String> doneWorld = new HashMap<>();
        int done = 0, dropped = 0, stuck = 0;
        for (int[] t : all) {
            long id = q.find(t[0], t[1]);
            State s = id < 0 ? null : q.state(id);
            if (s == State.DONE) {
                done++;
            } else if (s == State.DROPPED) {
                dropped++;
            } else if (s == State.QUEUED) {
                stuck++;
            }
        }
        for (var e : tile.entrySet()) {
            if (q.state(e.getKey()) == State.DONE) {
                long id = e.getKey();
                doneWorld.put(key(e.getValue()[0], e.getValue()[1]), q.doneWorld(id));
            }
        }
        return new Outcome(done, dropped, stuck, pieces(doneWorld), all.size());
    }

    @Test
    void twoDonorsFlyingSplitIntoFewPieces() {
        Outcome o = run(24, 4, true);
        System.out.println("走廊 24×4、两个 donor：" + o);
        assertTrue(o.pieces() <= 4, "连续大块，不交错（原来 5）：" + o);
        assertTrue(o.dropped() + o.stuck() <= 12, "接缝少（落给服务器的；原来 27）：" + o);
    }

    @Test
    void manualBatchSplitIntoFewPieces() {
        Outcome o = run(12, 12, false);
        System.out.println("手动一批 12×12、两个 donor：" + o);
        assertTrue(o.pieces() <= 2, "连续大块（原来 3）：" + o);
        assertTrue(o.dropped() + o.stuck() <= 14, "接缝少（原来 21）：" + o);
    }
}
