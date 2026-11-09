package mcdonate.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 第 9 步 e：设置（用几个核、多少内存、开关、帧时间目标）。默认关；值超出范围就夹到范围里。 */
class ClientSettingsTest {
    @Test
    void defaults(@TempDir Path dir) {
        ClientSettings s = ClientSettings.load(dir.resolve("mc-donate-settings.properties"));
        assertFalse(s.enabled(), "默认关");
        assertEquals(2, s.threads());
        assertEquals(2048, s.memoryMb());
        assertEquals(50, s.frameTargetMs());
    }

    @Test
    void roundTripAndClamp(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("mc-donate-settings.properties");
        new ClientSettings(true, 3, 3072, 33).save(f);
        assertEquals(new ClientSettings(true, 3, 3072, 33), ClientSettings.load(f));
        Files.writeString(f, "enabled=true\nthreads=99\nmemoryMb=10\nframeTargetMs=abc\n");
        ClientSettings s = ClientSettings.load(f);
        assertEquals(ClientSettings.MAX_THREADS, s.threads());
        assertEquals(ClientSettings.MIN_MEMORY_MB, s.memoryMb());
        assertEquals(50, s.frameTargetMs(), "读不懂的用默认值");
        assertTrue(s.enabled());
    }

    @Test
    void withersMakeCopies() {
        ClientSettings s = ClientSettings.DEFAULTS.withEnabled(true).withThreads(1).withMemoryMb(1024);
        assertEquals(new ClientSettings(true, 1, 1024, 50), s);
        assertFalse(ClientSettings.DEFAULTS.enabled());
    }

    @Test
    void frameTargetChoices() {
        // 设置界面的选项（11-09）：33/50/100/200 ms
        assertEquals(java.util.List.of(33, 50, 100, 200), ClientSettings.FRAME_TARGETS);
        assertEquals(100, ClientSettings.DEFAULTS.withFrameTargetMs(100).frameTargetMs());
        assertEquals(200, ClientSettings.DEFAULTS.withFrameTargetMs(500).frameTargetMs(), "最多 200");
    }
    @Test
    void splitsTotalBudgetsAndPersistsWorkerCount(@TempDir Path dir) throws Exception {
        ClientSettings s = new ClientSettings(true, 8, 4096, 50);
        assertEquals(2, s.effectiveWorkerCount());
        assertEquals(4, s.forWorker(0).threads());
        assertEquals(4, s.forWorker(1).threads());
        assertEquals(2048, s.forWorker(0).memoryMb());
        assertFalse(s.forWorker(2).enabled());
        s = s.withWorkerCount(3).withMemoryMb(3072);
        assertEquals(3, s.effectiveWorkerCount());
        assertEquals(8, s.forWorker(0).threads() + s.forWorker(1).threads() + s.forWorker(2).threads());
        assertEquals(3072, s.forWorker(0).memoryMb() + s.forWorker(1).memoryMb() + s.forWorker(2).memoryMb());
        Path f = dir.resolve("settings.properties");
        s.save(f);
        assertEquals(s, ClientSettings.load(f));
        assertEquals(1, ClientSettings.DEFAULTS.effectiveWorkerCount());
    }
}
