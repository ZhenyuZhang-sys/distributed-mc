package mcdonate.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import mcdonate.common.net.DonorInvite;
import org.junit.jupiter.api.Test;

/** 第 9 步 c/d：什么时候起 worker、停 worker、要新 token、暂停恢复。不起真的进程。 */
class DonorControllerTest {
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<String> events = new ArrayList<>();
    private boolean consent;
    private ClientSettings settings = ClientSettings.DEFAULTS;
    private boolean prepared = true;
    private boolean running;

    private final DonorController.Env env = new DonorController.Env() {
        public boolean consent() { return consent; }
        public ClientSettings settings() { return settings; }
        public boolean prepared() { return prepared; }
        public void prepareAsync() { events.add("prepare"); }
        public boolean workerRunning() { return running; }
        public void startWorker(DonorInvite invite, ClientSettings s) { events.add("start " + invite.token() + " threads=" + s.threads()); running = true; }
        public void stopWorker() { events.add("stop"); running = false; }
        public void sendToWorker(String line) { events.add("send " + line); }
        public void requestInvite() { events.add("request"); }
        public long now() { return now.get(); }
    };

    private final DonorController c = new DonorController(env);
    private final DonorInvite invite = new DonorInvite("127.0.0.1", 25585, "tok1", -77);

    @Test
    void nothingWithoutConsent() {
        settings = settings.withEnabled(true);
        c.onInvite(invite);
        assertTrue(events.isEmpty(), "9.2：没同意不起进程、不下载：" + events);
    }

    @Test
    void nothingWhenDisabled() {
        consent = true;
        c.onInvite(invite);
        assertTrue(events.isEmpty(), events.toString());
    }

    @Test
    void startsAfterConsentAndPrepares() {
        consent = true;
        settings = settings.withEnabled(true).withThreads(3);
        prepared = false;
        c.onInvite(invite);
        assertEquals(List.of("prepare"), events, "先准备（后台下载），准备好再起");
        prepared = true;
        c.onPrepared();
        assertEquals(List.of("prepare", "start tok1 threads=3"), events);
    }

    @Test
    void enablingLaterUsesTheInviteIfStillFresh() {
        c.onInvite(invite);
        consent = true;
        settings = settings.withEnabled(true);
        c.onSettingsChanged();
        assertEquals(List.of("start tok1 threads=2"), events);
    }

    @Test
    void enablingLaterWithStaleInviteAsksForNewOne() {
        c.onInvite(invite);
        now.addAndGet(61_000); // token 60 秒过期
        consent = true;
        settings = settings.withEnabled(true);
        c.onSettingsChanged();
        assertEquals(List.of("request"), events, "邀请过期了，再要一次");
        c.onInvite(new DonorInvite("127.0.0.1", 25585, "tok2", -77));
        assertEquals(List.of("request", "start tok2 threads=2"), events);
    }

    @Test
    void needTokenRequestsInviteAndForwardsIt() {
        consent = true;
        settings = settings.withEnabled(true);
        c.onInvite(invite);
        events.clear();
        c.onWorkerLine("NEED_TOKEN");
        c.onWorkerLine("NEED_TOKEN"); // 10 秒内再要不发
        assertEquals(List.of("request"), events);
        c.onInvite(new DonorInvite("127.0.0.1", 25585, "tok2", -77));
        assertEquals(List.of("request", "send TOKEN tok2"), events, "worker 在跑：把新 token 转给它，不重起");
        now.addAndGet(10_000);
        c.onWorkerLine("NEED_TOKEN");
        assertEquals("request", events.get(events.size() - 1));
    }

    @Test
    void disconnectStopsAndThrottleForwards() {
        consent = true;
        settings = settings.withEnabled(true);
        c.onInvite(invite);
        c.onThrottle(FrameThrottle.Action.PAUSE);
        c.onThrottle(FrameThrottle.Action.RESUME);
        c.onThrottle(FrameThrottle.Action.NONE);
        c.onDisconnect();
        assertEquals(List.of("start tok1 threads=2", "send PAUSE", "send RESUME", "stop"), events);
        // 再进服：服务器发新的邀请（新 token），照常起
        c.onInvite(new DonorInvite("127.0.0.1", 25585, "tok3", -77));
        assertEquals("start tok3 threads=2", events.get(events.size() - 1));
    }

    @Test
    void disablingStops() {
        consent = true;
        settings = settings.withEnabled(true);
        c.onInvite(invite);
        settings = settings.withEnabled(false);
        c.onSettingsChanged();
        assertEquals("stop", events.get(events.size() - 1));
    }

    @Test
    void invalidInviteIgnored() {
        consent = true;
        settings = settings.withEnabled(true);
        c.onInvite(new DonorInvite(2, "h", 1, "t", 1));
        assertTrue(events.isEmpty());
    }
}
