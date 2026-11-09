package mcdonate.bench.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 真实场景的飞行路线（调度 11-09：会转弯的鞘翅、来回飞、走路骑马混合加停顿）：若干段（朝向、速度、秒数），按墙钟时间走。
 * 朝向用 Minecraft 的 yaw：0 南（+z），90 西（-x），180 北（-z），-90 东（+x）。速度 0 = 停在原地。
 */
class RouteTest {
    @Test
    void parseAndTotals() {
        Route r = Route.parse("-90,30,20;0,30,10;0,0,5;90,14,10");
        assertEquals(4, r.segments().size());
        assertEquals(45, r.seconds(), 1e-9);
        assertEquals(30 * 20 + 30 * 10 + 0 + 14 * 10, r.idealDistance(), 1e-9);
        Route.Segment east = r.segments().get(0);
        assertEquals(1, east.dirX(), 1e-9);
        assertEquals(0, east.dirZ(), 1e-9);
        Route.Segment south = r.segments().get(1);
        assertEquals(0, south.dirX(), 1e-9);
        assertEquals(1, south.dirZ(), 1e-9);
        assertEquals("-90,30,20;0,30,10;0,0,5;90,14,10", r.spec());
    }

    @Test
    void piecesSplitAtSegmentBoundaries() {
        Route r = Route.parse("-90,30,2;0,10,3");
        List<Route.Piece> p = r.pieces(1.5, 2.5);   // 跨过 2 秒那个边界
        assertEquals(2, p.size());
        assertEquals(0, p.get(0).index());
        assertEquals(0.5, p.get(0).seconds(), 1e-9);
        assertEquals(1, p.get(1).index());
        assertEquals(0.5, p.get(1).seconds(), 1e-9);
        assertTrue(r.pieces(4, 9).stream().mapToDouble(Route.Piece::seconds).sum() <= 1 + 1e-9);   // 到 5 秒就结束
        assertTrue(r.pieces(6, 9).isEmpty());
        assertEquals(1, r.segmentAt(2.0));
        assertEquals(0, r.segmentAt(0));
    }

    @Test
    void invalidSpecsRejected() {
        for (String bad : new String[] {"", "abc", "-90,30", "-90,-1,5", "-90,30,0", "-90,30,-2", "-90,2000,5",
                "nan,30,5", String.join(";", java.util.Collections.nCopies(65, "-90,30,1"))}) {
            assertThrows(IllegalArgumentException.class, () -> Route.parse(bad), bad);
        }
    }

    @Test
    void pauseSegmentHasNoDirectionNeed() {
        Route r = Route.parse("0,0,3");
        assertEquals(0, r.idealDistance(), 1e-9);
        assertEquals(3, r.seconds(), 1e-9);
    }
}
