package mcdonate.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CountersTest {
    @Test
    void countsAndResets() {
        Counters.reset();
        assertEquals("{\"full_generated\":0,\"full_loaded\":0,\"noise\":0}", Counters.json());

        Counters.onFull(false);
        Counters.onFull(false);
        Counters.onFull(true);
        Counters.onNoise();
        assertEquals("{\"full_generated\":2,\"full_loaded\":1,\"noise\":1}", Counters.json());

        Counters.reset();
        assertEquals("{\"full_generated\":0,\"full_loaded\":0,\"noise\":0}", Counters.json());
    }
}
