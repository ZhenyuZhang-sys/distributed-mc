package mcdonate.common.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import mcdonate.common.Provenance;
import org.junit.jupiter.api.Test;

/**
 * chunk 变成完整状态（docs/overlay-plan.md 2.1）：挪进 common，部署时没有 bench 也有 VANILLA、RESUMED、LOADED 事件。
 * 计数（bench 注册的 Counter）和来源事件在同一把读锁里发；listen / unlisten 拿写锁读快照，所以监听期间收到的每类事件数
 * 严格等于计数的增量（第 4 步 4.3 的保证，原来在 bench 的 Generation 里）。
 */
class FullStatusTest {
    static final AtomicLongArray COUNTS = new AtomicLongArray(3);

    static long[] read() {
        return new long[] {COUNTS.get(0), COUNTS.get(1), COUNTS.get(2)};
    }

    @Test
    void eventsBetweenListenAndUnlistenEqualCounterDelta() throws Exception {
        FullStatus.setCounter(k -> COUNTS.incrementAndGet(k.ordinal()));
        AtomicBoolean stop = new AtomicBoolean();
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            int id = t;
            Thread th = new Thread(() -> {
                int i = 0;
                while (!stop.get()) {
                    FullStatus.onFull(FullStatus.Kind.values()[(i + id) % 3], i, id);
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
                long[] a = FullStatus.listen(l, FullStatusTest::read);
                Thread.sleep(2);
                long[] b = FullStatus.unlisten(l, FullStatusTest::read);
                assertEquals(b[0] - a[0], vanilla.get(), "第 " + round + " 轮");
                assertEquals(b[1] - a[1], loaded.get(), "第 " + round + " 轮");
                assertEquals(b[2] - a[2], resumed.get(), "第 " + round + " 轮");
            }
        } finally {
            stop.set(true);
            for (Thread th : workers) th.join();
        }
    }

    @Test
    void kindsMapToProvenanceKinds() {
        List<Provenance.Kind> got = new ArrayList<>();
        Provenance.Listener l = (cx, cz, kind, donor) -> got.add(kind);
        FullStatus.listen(l, () -> null);
        try {
            FullStatus.onFull(FullStatus.Kind.GENERATED, 0, 0);
            FullStatus.onFull(FullStatus.Kind.RESUMED, 0, 1);
            FullStatus.onFull(FullStatus.Kind.LOADED, 0, 2);
        } finally {
            FullStatus.unlisten(l, () -> null);
        }
        assertEquals(List.of(Provenance.Kind.VANILLA, Provenance.Kind.RESUMED, Provenance.Kind.LOADED), got);
    }

    @Test
    void plainProvenanceListenerAlsoSeesEvents() {
        // coordinator 的来源发送器直接挂在 Provenance 上（不走 listen 的快照），也收得到
        List<Provenance.Kind> got = new ArrayList<>();
        Provenance.Listener l = (cx, cz, kind, donor) -> got.add(kind);
        Provenance.addListener(l);
        try {
            FullStatus.onFull(FullStatus.Kind.GENERATED, 5, 5);
        } finally {
            Provenance.removeListener(l);
        }
        assertEquals(List.of(Provenance.Kind.VANILLA), got);
    }
}
