package mcdonate.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import mcdonate.bench.Counters.FullKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CountersTest {
    private static final String ZERO =
            "{\"full_generated\":0,\"full_loaded\":0,\"full_resumed\":0,\"noise\":0,\"features\":0}";

    @AfterEach
    void cleanUp() {
        Counters.setTracking(false);
        Counters.reset();
    }

    @Test
    void countsAndResets() {
        Counters.reset();
        assertEquals(ZERO, Counters.json());

        Counters.onFull(FullKind.GENERATED);
        Counters.onFull(FullKind.GENERATED);
        Counters.onFull(FullKind.LOADED);
        Counters.onFull(FullKind.RESUMED);
        Counters.onNoise(0, 0);
        Counters.onFeatures(0, 0);
        assertEquals("{\"full_generated\":2,\"full_loaded\":1,\"full_resumed\":1,\"noise\":1,\"features\":1}",
                Counters.json());

        Counters.reset();
        assertEquals(ZERO, Counters.json());
    }

    @Test
    void perChunkOnlyWhenTracking() {
        Counters.reset();
        Counters.onNoise(3, -4);
        assertEquals("{\"tracking\":false,\"noise\":0,\"features\":0}", Counters.ran(3, -4));

        Counters.setTracking(true);
        Counters.onNoise(3, -4);
        Counters.onFeatures(3, -4);
        Counters.onFeatures(3, -4);
        Counters.onNoise(-4, 3); // 别的 chunk 不混进来
        assertEquals("{\"tracking\":true,\"noise\":1,\"features\":2}", Counters.ran(3, -4));

        Counters.reset();
        assertEquals("{\"tracking\":true,\"noise\":0,\"features\":0}", Counters.ran(3, -4));
    }
}
