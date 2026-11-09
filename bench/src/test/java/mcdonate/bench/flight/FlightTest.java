package mcdonate.bench.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FlightTest {
    @Test
    void wallClockIgnoresTicks() {
        Flight f = Flight.eastward(100, 50, 200, 30, 120, Flight.Mode.WALL);
        double t = f.elapsed(2_000_000_000L, 20);   // 2 秒墙钟，但只跑了 20 tick（服务器卡）
        assertEquals(2.0, t, 1e-9);
        assertEquals(160.0, f.x(t), 1e-9);
        assertEquals(50.0, f.z(t), 1e-9);
    }

    @Test
    void tickModeFollowsTicks() {
        Flight f = Flight.eastward(0, 0, 200, 30, 120, Flight.Mode.TICK);
        assertEquals(1.0, f.elapsed(2_000_000_000L, 20), 1e-9);
    }

    @Test
    void clampedToDuration() {
        Flight f = Flight.eastward(0, 0, 200, 4.3, 120, Flight.Mode.WALL);
        double t = f.elapsed(500_000_000_000L, 0);
        assertEquals(120.0, t, 1e-9);
        assertEquals(516.0, f.x(t), 1e-9);
        assertTrue(f.finished(t));
        assertFalse(f.finished(f.elapsed(-5, 0)));
        assertEquals(0.0, f.elapsed(-5, 0));
    }

    @Test
    void directionNormalised() {
        Flight f = new Flight(0, 0, 200, 3, 4, 10, 1, Flight.Mode.WALL);
        assertEquals(6.0, f.x(1), 1e-9);
        assertEquals(8.0, f.z(1), 1e-9);
    }

    @Test
    void invalidRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Flight(0, 0, 0, 0, 0, 1, 1, Flight.Mode.WALL));
        assertThrows(IllegalArgumentException.class, () -> Flight.eastward(0, 0, 0, 0, 1, Flight.Mode.WALL));
        assertThrows(IllegalArgumentException.class, () -> Flight.eastward(0, 0, 0, 1, 0, Flight.Mode.WALL));
    }
}
