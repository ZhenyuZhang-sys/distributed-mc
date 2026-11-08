package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FeaturesTest {
    @Test
    void allOffByDefault() {
        Features f = new Features("");
        for (String n : Features.ALL) {
            assertFalse(f.on(n), n);
        }
        assertEquals("[]", f.json());
    }

    @Test
    void initialAndToggle() {
        Features f = new Features("territory, deadline");
        assertTrue(f.on(Features.TERRITORY));
        assertTrue(f.on(Features.DEADLINE));
        assertEquals("[\"deadline\",\"territory\"]", f.json());
        f.set(Features.TERRITORY, false);
        assertFalse(f.on(Features.TERRITORY));
        assertEquals("[\"deadline\"]", f.json());
    }

    @Test
    void unknownNameRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Features("nope"));
        assertThrows(IllegalArgumentException.class, () -> new Features("").set("nope", true));
    }
}
