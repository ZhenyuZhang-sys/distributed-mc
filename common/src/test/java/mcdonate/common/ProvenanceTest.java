package mcdonate.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProvenanceTest {
    @Test
    void noListenerIsNoop() {
        Provenance.report(1, 2, Provenance.Kind.VANILLA, -1);
    }

    @Test
    void listenersReceiveEventsAndCanBeRemoved() {
        List<String> got = new ArrayList<>();
        Provenance.Listener l = (x, z, k, d) -> got.add(x + "," + z + "," + k + "," + d);
        Provenance.addListener(l);
        try {
            Provenance.report(3, -4, Provenance.Kind.DONOR, 7);
            Provenance.report(5, 6, Provenance.Kind.REJECTED, 7);
        } finally {
            Provenance.removeListener(l);
        }
        Provenance.report(0, 0, Provenance.Kind.VANILLA, -1);
        assertEquals(List.of("3,-4,DONOR,7", "5,6,REJECTED,7"), got);
    }

    @Test
    void failingListenerDoesNotBreakOthers() {
        List<Integer> got = new ArrayList<>();
        Provenance.Listener bad = (x, z, k, d) -> { throw new IllegalStateException("boom"); };
        Provenance.Listener good = (x, z, k, d) -> got.add(x);
        Provenance.addListener(bad);
        Provenance.addListener(good);
        try {
            Provenance.report(9, 9, Provenance.Kind.LOADED, -1);
        } finally {
            Provenance.removeListener(bad);
            Provenance.removeListener(good);
        }
        assertEquals(List.of(9), got);
    }

    @Test
    void nullKindRejected() {
        assertThrows(IllegalArgumentException.class, () -> Provenance.report(0, 0, null, -1));
    }
}
