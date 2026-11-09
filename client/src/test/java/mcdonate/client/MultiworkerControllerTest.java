package mcdonate.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import mcdonate.common.net.DonorInvite;
import org.junit.jupiter.api.Test;

class MultiworkerControllerTest {
    static class Slot implements DonorController.Env {
        final ClientSettings settings;
        final List<String> sent = new ArrayList<>();
        int requests, starts, stops;
        boolean running, busy;
        long clock = 100000;
        String token;
        Slot(ClientSettings settings) { this.settings = settings; }
        public boolean consent() { return true; }
        public ClientSettings settings() { return settings; }
        public boolean prepared() { return true; }
        public void prepareAsync() { fail("准备已完成"); }
        public boolean workerRunning() { return running; }
        public boolean busy() { return busy; }
        public void startWorker(DonorInvite invite, ClientSettings s) { running = true; starts++; token = invite.token(); }
        public void stopWorker() { running = false; stops++; }
        public void sendToWorker(String line) { sent.add(line); }
        public void requestInvite() { requests++; }
        public long now() { return clock; }
    }

    @Test void independentInvitesReconnectAndAllStop() {
        var budget = new ClientSettings(true, 8, 4096, 50);
        var a = new Slot(budget.forWorker(0)); var b = new Slot(budget.forWorker(1));
        var ca = new DonorController(a); var cb = new DonorController(b);
        ca.onJoin(); cb.onJoin();
        ca.onInvite(new DonorInvite("host", 1, "fixture-a", 42));
        cb.onInvite(new DonorInvite("host", 1, "fixture-b", 42));
        assertNotEquals(a.token, b.token);
        assertEquals(1, a.starts); assertEquals(1, b.starts);
        b.clock += 10000;
        cb.onWorkerLine("NEED_TOKEN");
        assertEquals(0, a.requests); assertEquals(1, b.requests);
        cb.onInvite(new DonorInvite("host", 1, "fixture-fresh-b", 42));
        assertTrue(a.sent.isEmpty()); assertEquals(List.of("TOKEN fixture-fresh-b"), b.sent);
        ca.onThrottle(FrameThrottle.Action.PAUSE); cb.onThrottle(FrameThrottle.Action.PAUSE);
        assertEquals("PAUSE", a.sent.getLast()); assertEquals("PAUSE", b.sent.getLast());
        ca.onDisconnect(); cb.onDisconnect(); ca.tick(); cb.tick();
        assertEquals(1, a.stops); assertEquals(1, b.stops);
        assertEquals(1, a.starts); assertEquals(1, b.starts);
    }

    @Test void menuDoesNotPrepareAndStopFenceRetainsInvite() {
        var env = new Slot(new ClientSettings(true, 4, 2048, 50));
        var c = new DonorController(env);
        c.tick(); assertEquals(0, env.requests); assertEquals(0, env.starts);
        c.onJoin(); env.busy = true;
        c.onInvite(new DonorInvite("host", 1, "fixture", 42));
        assertEquals(0, env.starts);
        env.busy = false; c.tick(); assertEquals(1, env.starts);
    }
}
