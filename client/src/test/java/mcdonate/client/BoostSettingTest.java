package mcdonate.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import mcdonate.common.net.DonorInvite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 11-09 加速模式（客户端）：设置里的开关、启动参数、运行中改了马上告诉 worker。 */
class BoostSettingTest {
    @Test
    void defaultOffAndSaved(@TempDir Path dir) throws Exception {
        assertFalse(ClientSettings.DEFAULTS.boost());
        Path f = dir.resolve("mc-donate-settings.properties");
        ClientSettings s = ClientSettings.DEFAULTS.withEnabled(true).withBoost(true);
        s.save(f);
        assertTrue(ClientSettings.load(f).boost());
        Files.writeString(f, "enabled=true\nthreads=2\n");   // 旧设置文件没有 boost
        assertFalse(ClientSettings.load(f).boost());
        assertEquals(new ClientSettings(true, 3, 3072, 33), new ClientSettings(true, 3, 3072, 33, false), "旧的 4 个参数构造照旧可用");
    }

    @Test
    void launchPassesBoost() {
        WorkerLaunch.Options on = new WorkerLaunch.Options(Path.of("/jre/bin/java"), 2048, 2, 4242, "h:25580", -77, true);
        assertTrue(WorkerLaunch.command(on, "Linux").contains("-Dmcdonate.boost=true"));
        WorkerLaunch.Options off = new WorkerLaunch.Options(Path.of("/jre/bin/java"), 2048, 2, 4242, "h:25580", -77);
        assertTrue(WorkerLaunch.command(off, "Linux").contains("-Dmcdonate.boost=false"));
    }

    @Test
    void changingBoostWhileRunningTellsWorker() {
        List<String> events = new ArrayList<>();
        ClientSettings[] settings = {ClientSettings.DEFAULTS.withEnabled(true)};
        boolean[] running = {false};
        DonorController c = new DonorController(new DonorController.Env() {
            public boolean consent() { return true; }
            public ClientSettings settings() { return settings[0]; }
            public boolean prepared() { return true; }
            public void prepareAsync() { }
            public boolean workerRunning() { return running[0]; }
            public void startWorker(DonorInvite invite, ClientSettings s) { events.add("start boost=" + s.boost()); running[0] = true; }
            public void stopWorker() { events.add("stop"); running[0] = false; }
            public void sendToWorker(String line) { events.add("send " + line); }
            public void requestInvite() { }
            public long now() { return 1_000; }
        });
        c.onInvite(new DonorInvite("127.0.0.1", 25585, "tok1", -77));
        assertEquals(List.of("start boost=false"), events);
        settings[0] = settings[0].withBoost(true);
        c.onSettingsChanged();
        assertEquals("send BOOST on", events.get(events.size() - 1), "运行中打开：马上告诉 worker，不重起");
        int n = events.size();
        c.onSettingsChanged();
        assertEquals(n, events.size(), "没变不重复发");
        settings[0] = settings[0].withBoost(false);
        c.onSettingsChanged();
        assertEquals("send BOOST off", events.get(events.size() - 1));
        assertFalse(events.contains("stop"));
    }
}
