package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    // ---- codex-audit 第二十三节（审 40dd535）：块是"有时间时的偏好"，紧任务谁能做谁做；真实的 tile 亲缘高于块 ----

    static final class S {
        final AtomicLong now = new AtomicLong(1_000_000);
        final TaskQueue q = new TaskQueue(now::get, 30_000, 3, new Features("territory,deadline"));

        S() {
            q.setWorkerWorld(1, "W");
            q.setWorkerWorld(2, "V");
        }

        long seed(int worker, int x, int z, boolean imported) {
            assertTrue(q.enqueue(x, z));
            var a = q.assign(worker, 1, d -> true).orElseThrow();
            assertEquals(x, a.tx());
            q.onResult(worker, a.taskId(), true);
            if (imported) {
                q.markImported(a.taskId(), worker == 1 ? "W" : "V");
            }
            return a.taskId();
        }

        void due(int x, int z, long remaining) {
            q.setDue(x, z, now.get() + remaining, now.get() + remaining + TaskQueue.IMPORT_MARGIN_MS, 0);
        }
    }

    @Test
    void resetClearsBlocks() {
        // T4：reset 以后旧的块不挡人
        S s = new S();
        s.seed(1, 0, 0, false);
        s.q.reset();
        s.q.enqueue(2, 0);
        assertTrue(s.q.assign(2).isPresent());
    }

    @Test
    void urgentTaskInAnotherDonorsBlockCanBeRescued() {
        // T1：W 领过块 0 的一个 tile（到期收回、没导入），同块里只剩 1 秒的任务：空着的快 donor V 能接
        S s = new S();
        s.q.enqueue(0, 0);
        s.q.assign(1).orElseThrow();
        s.now.addAndGet(30_001);
        assertEquals(1, s.q.expireLeases().size());
        s.q.enqueue(2, 0);
        s.due(2, 0, 1_000);
        assertTrue(s.q.assign(2, 1, d -> s.now.get() + 500 <= d).isPresent());
    }

    @Test
    void backgroundBlockDoesNotLockUrgentForegroundTiles() {
        // T1：W 只做了一个后台 tile 就拿下整块，前方只剩 1 秒的 (2,0) 空着的 V 照样能接
        S s = new S();
        s.q.enqueueBackground(0, 0, null);
        var bg = s.q.assign(1, 2, d -> true, true).orElseThrow();
        s.q.onResult(1, bg.taskId(), true);
        s.q.markImported(bg.taskId(), "W");
        s.q.enqueue(2, 0);
        s.due(2, 0, 1_000);
        assertTrue(s.q.assign(2, 1, d -> s.now.get() + 500 <= d, false).isPresent());
    }

    @Test
    void nonUrgentTaskInAnotherDonorsBlockIsLeftToIt() {
        // 有时间的照旧留给块的主人（不然又交错）
        S s = new S();
        s.seed(1, 0, 0, false);
        s.q.enqueue(2, 0);
        s.due(2, 0, 20_000);
        assertTrue(s.q.assign(2, 1, d -> true).isEmpty(), "V 不进 W 的块拿不急的");
        assertEquals(2, s.q.assign(1, 1, d -> true).orElseThrow().tx());
    }

    @Test
    void urgentTaskBeatsOwnBlockPreference() {
        // T2：自己块里 10 秒的和挨着的空块里 0.6 秒的：先做紧的（和剪枝按截止的顺序一致）
        S s = new S();
        s.seed(1, 0, 0, false);
        s.q.enqueue(2, 0);
        s.due(2, 0, 10_000);
        int b = TaskQueue.TERRITORY_BLOCK;
        s.q.enqueue(b, 0);
        s.due(b, 0, 600);
        assertEquals(b, s.q.assign(1, 1, d -> s.now.get() + 500 <= d).orElseThrow().tx());
    }

    @Test
    void realTileAffinityBeatsCoarseBlock() {
        // T3：W 拿下块 0；硬认 V 的 (6,0) 在块 0 里导入成 V 的；排着的 (5,0) 真邻居只有 V：V 要能领（不能两层都否决）
        S s = new S();
        s.seed(1, 0, 0, true);
        s.q.enqueue(6, 0, "V");
        s.q.enqueue(5, 0);
        var b = s.q.assign(2, 1, d -> true).orElseThrow();
        assertEquals(6, b.tx());
        s.q.onResult(2, b.taskId(), true);
        s.q.markImported(b.taskId(), "V");
        assertTrue(s.q.assign(1).isEmpty(), "(5,0) 挨着 V 导入的，不给 W");
        assertEquals(5, s.q.assign(2).orElseThrow().tx(), "V 能领");
    }

    @Test
    void backgroundHonorsBlockPreference() {
        // T6：后台的也先做自己块里的
        S s = new S();
        s.seed(1, 0, 0, false);
        s.q.enqueueBackground(16, 0, null);
        s.q.enqueueBackground(2, 0, null);
        assertEquals(2, s.q.assign(1, 2, d -> true, true).orElseThrow().tx());
    }

    @Test
    void worldChangeOnlyMovesItsOwnUnknownAlias() {
        // T5：worker 1 和 3 都是 W，各领一块；worker 1 换成 NEW，worker 3 的块还是 W 的
        S s = new S();
        s.q.setWorkerWorld(3, "W");
        s.seed(1, 0, 0, false);
        s.seed(3, 16, 0, false);
        s.q.setWorkerWorld(1, "NEW");
        s.q.enqueue(18, 0);
        assertTrue(s.q.assign(3).isPresent());
    }

    @Test
    void unknownWorkerBlocksMoveToItsFirstWorld() {
        // 不知道世界时领的块（worker:1），知道世界后归这个世界；断开后同世界的新编号接着用
        AtomicLong now = new AtomicLong(1_000_000);
        TaskQueue q = new TaskQueue(now::get, 30_000, 3, new Features("territory"));
        q.enqueue(0, 0);
        long id = q.assign(1).orElseThrow().taskId();
        q.setWorkerWorld(1, "W");
        q.onResult(1, id, true);
        q.markImported(id, "W");
        q.workerGone(1);
        q.setWorkerWorld(9, "W");
        q.enqueue(1, 0);
        assertEquals(1, q.assign(9).orElseThrow().tx());
    }

    @Test
    void negativeCoordinatesUseFloorDivision() {
        S s = new S();
        s.seed(1, -1, 0, false);
        s.q.enqueue(-8, 0);
        assertTrue(s.q.assign(2).isEmpty(), "-8～-1 同一块（W 的），不急的不给 V");
        s.q.enqueue(0, 0);
        assertEquals(0, s.q.assign(2).orElseThrow().tx());
    }
}
