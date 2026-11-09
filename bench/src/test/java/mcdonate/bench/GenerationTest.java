package mcdonate.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import mcdonate.common.Provenance;
import org.junit.jupiter.api.Test;

/**
 * 第 4 步 4.3：chunk 变成完整状态时，计数和来源事件一起发（Generation.onFull）。
 * 监听开始 / 结束时拿到的计数快照，和监听器在这段时间收到的事件数严格相等，即使生成线程在并发上报。
 */
class GenerationTest {

    @Test
    void eventsBetweenListenAndUnlistenEqualCounterDelta() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            int id = t;
            Thread th = new Thread(() -> {
                int i = 0;
                while (!stop.get()) {
                    Counters.FullKind kind = Counters.FullKind.values()[(i + id) % 3];
                    Generation.onFull(kind, i, id);
                    i++;
                }
            });
            th.start();
            workers.add(th);
        }
        try {
            for (int round = 0; round < 50; round++) {
                AtomicLong vanilla = new AtomicLong(), loaded = new AtomicLong(), resumed = new AtomicLong();
                Provenance.Listener l = (cx, cz, kind, donor) -> {
                    if (kind == Provenance.Kind.VANILLA) vanilla.incrementAndGet();
                    if (kind == Provenance.Kind.LOADED) loaded.incrementAndGet();
                    if (kind == Provenance.Kind.RESUMED) resumed.incrementAndGet();
                };
                Generation.Snapshot a = Generation.listen(l);
                Thread.sleep(2);
                Generation.Snapshot b = Generation.unlisten(l);
                // 三类分开（fish 11-09）：VANILLA 只算从头生成；读盘的半成品被接着做完（导入的 tile 就是这种）是 RESUMED；读盘的成品是 LOADED
                assertEquals(b.generated() - a.generated(), vanilla.get(), "第 " + round + " 轮");
                assertEquals(b.resumed() - a.resumed(), resumed.get(), "第 " + round + " 轮");
                assertEquals(b.loaded() - a.loaded(), loaded.get(), "第 " + round + " 轮");
            }
        } finally {
            stop.set(true);
            for (Thread th : workers) th.join();
        }
    }

    @Test
    void resumedIsItsOwnKind() {
        List<Provenance.Kind> got = new ArrayList<>();
        Provenance.Listener l = (cx, cz, kind, donor) -> got.add(kind);
        Generation.listen(l);
        try {
            Generation.onFull(Counters.FullKind.GENERATED, 0, 0);
            Generation.onFull(Counters.FullKind.RESUMED, 0, 1);
            Generation.onFull(Counters.FullKind.LOADED, 0, 2);
        } finally {
            Generation.unlisten(l);
        }
        assertEquals(List.of(Provenance.Kind.VANILLA, Provenance.Kind.RESUMED, Provenance.Kind.LOADED), got);
    }

    @Test
    void withoutListenerOnlyCounts() {
        Generation.Snapshot a = Generation.snapshot();
        Generation.onFull(Counters.FullKind.GENERATED, 1, 2);
        Generation.onFull(Counters.FullKind.LOADED, 1, 3);
        Generation.Snapshot b = Generation.snapshot();
        assertEquals(1, b.generated() - a.generated());
        assertEquals(1, b.loaded() - a.loaded());
    }
}
