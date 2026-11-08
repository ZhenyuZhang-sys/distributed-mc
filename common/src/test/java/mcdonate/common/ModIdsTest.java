package mcdonate.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ModIdsTest {
    @Test
    void addsPrefix() {
        assertEquals("mcdonate-bench", ModIds.of("bench"));
    }

    @Test
    void rejectsBadName() {
        assertThrows(IllegalArgumentException.class, () -> ModIds.of("Bench"));
    }
}
