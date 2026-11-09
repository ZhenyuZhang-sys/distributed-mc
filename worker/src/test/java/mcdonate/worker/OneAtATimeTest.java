package mcdonate.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.Test;

/**
 * 11-09 f3 实测：开 7.3（一个 donor 同时领几个任务）时，worker 主线程在 getChunk 的 managedBlock 里等 chunk，
 * 等的时候会执行排着的任务——下一个 tile 的生成就嵌套在上一个里面跑，越嵌越深，60 秒看门狗杀掉 worker。
 * OneAtATime：同一时间只做一个，嵌套进来的直接返回，做完再排下一个。
 */
class OneAtATimeTest {
    /** 模拟主线程的任务队列：run() 里"等 chunk"时会把队列里排着的任务也执行掉（像 managedBlock）。 */
    private final Queue<Runnable> mainQueue = new ArrayDeque<>();

    private void drainMain() {
        Runnable r;
        while ((r = mainQueue.poll()) != null) {
            r.run();
        }
    }

    @Test
    void nestedSubmitDoesNotNest() {
        OneAtATime<String> one = new OneAtATime<>(mainQueue::add);
        List<String> log = new ArrayList<>();
        int[] depth = {0};
        int[] maxDepth = {0};
        OneAtATime.Job<String> job = name -> {
            depth[0]++;
            maxDepth[0] = Math.max(maxDepth[0], depth[0]);
            log.add("start " + name);
            drainMain(); // 等 chunk：主线程顺便执行排着的任务
            log.add("end " + name);
            depth[0]--;
        };
        one.submit("a", job);
        one.submit("b", job);
        one.submit("c", job);
        drainMain();
        assertEquals(1, maxDepth[0], "一次只做一个，不嵌套");
        assertEquals(List.of("start a", "end a", "start b", "end b", "start c", "end c"), log);
    }

    @Test
    void failureDoesNotBlockTheNext() {
        OneAtATime<String> one = new OneAtATime<>(mainQueue::add);
        List<String> done = new ArrayList<>();
        one.submit("bad", n -> { throw new IllegalStateException("boom"); });
        one.submit("good", done::add);
        try {
            drainMain();
        } catch (IllegalStateException expected) {
            drainMain();
        }
        assertEquals(List.of("good"), done);
    }
}
