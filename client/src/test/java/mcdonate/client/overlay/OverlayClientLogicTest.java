package mcdonate.client.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import mcdonate.common.net.ProvenanceUpdate;
import mcdonate.common.net.ProvenanceUpdate.Donor;
import mcdonate.common.net.ProvenanceUpdate.Entry;
import mcdonate.common.net.ProvenanceUpdate.Source;
import mcdonate.common.net.ProvenanceUpdate.Summary;
import mcdonate.common.overlay.DonorColors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 游戏内来源上色和算力面板的客户端逻辑（docs/overlay-plan.md 第 5 节），不起游戏。 */
class OverlayClientLogicTest {

    // ---- 设置：单独一个文件，不碰第 9 步的 mc-donate-settings.properties ----

    @Test
    void settingsDefaultOffRoundTripAndCorrupt(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("mc-donate-overlay.properties");
        assertEquals(new OverlaySettings(false, false), OverlaySettings.load(f));
        new OverlaySettings(true, false).save(f);
        assertEquals(new OverlaySettings(true, false), OverlaySettings.load(f));
        Files.writeString(f, "overlay=maybe\nhud=true\n");
        assertEquals(new OverlaySettings(false, true), OverlaySettings.load(f));
        assertEquals("mc-donate-overlay.properties", OverlaySettings.FILE);
    }

    // ---- 命令：/mcdonate show | overlay on|off | hud on|off ----

    @Test
    void commandsToggle() {
        OverlaySettings off = new OverlaySettings(false, false);
        assertEquals(new OverlaySettings(true, true), OverlayToggles.show(off));
        assertEquals(new OverlaySettings(true, true), OverlayToggles.show(new OverlaySettings(true, false)));   // 有一样关着：两样都开
        assertEquals(off, OverlayToggles.show(new OverlaySettings(true, true)));                              // 两样都开着：两样都关
        assertEquals(new OverlaySettings(true, false), OverlayToggles.overlay(off, true));
        assertEquals(new OverlaySettings(false, true), OverlayToggles.hud(off, true));
        assertTrue(OverlayToggles.describe(new OverlaySettings(true, false)).contains("上色：开"));
        assertTrue(OverlayToggles.describe(new OverlaySettings(true, false)).contains("算力面板：关"));
    }

    // ---- 收到的来源数据 ----

    static ProvenanceUpdate snap() {
        return new ProvenanceUpdate(true, 0, 0, List.of(new Donor(0, "Alice", "player:a")),
                List.of(new Entry(0, 0, Source.SERVER, -1), new Entry(1, 0, Source.DONOR, 0), new Entry(2, 0, Source.REJECTED, -1)), null);
    }

    @Test
    void sourcesFromSnapshotAndIncrements() {
        ChunkSources s = new ChunkSources();
        s.apply(snap());
        assertEquals(DonorColors.of(Source.SERVER), s.color(0, 0));
        assertEquals(DonorColors.donor("player:a"), s.color(1, 0));
        assertEquals(-1, s.color(5, 5));                                    // 没数据：不画
        assertEquals(List.of("Alice"), s.donorNames());
        // 增量：donor 表之前给过，这一包只带编号
        s.apply(new ProvenanceUpdate(false, 0, 0, List.of(), List.of(new Entry(3, 0, Source.DONOR, 0), new Entry(0, 0, Source.RESUMED, -1)), null));
        assertEquals(DonorColors.donor("player:a"), s.color(3, 0));
        assertEquals(DonorColors.of(Source.RESUMED), s.color(0, 0));
        // 新的整片快照：先清空
        s.apply(new ProvenanceUpdate(true, 10, 10, List.of(), List.of(new Entry(10, 10, Source.SERVER, -1)), null));
        assertEquals(-1, s.color(1, 0));
        assertEquals(1, s.size());
        // 坏数据丢掉
        s.apply(new ProvenanceUpdate(9, true, 0, 0, List.of(), List.of(), null));
        assertEquals(1, s.size());
        s.clear();
        assertEquals(0, s.size());
        assertTrue(s.updates() >= 3);
    }

    @Test
    void summaryKeptForHud() {
        ChunkSources s = new ChunkSources();
        s.apply(new ProvenanceUpdate(false, 0, 0, List.of(), List.of(), new Summary(3, 200, 50)));
        assertEquals(new Summary(3, 200, 50), s.summary());
    }

    // ---- 算力面板：每一行来自 worker 的 STATUS 和服务器的摘要 ----

    @Test
    void hudLinesFromWorkerStatus() {
        String status = "{\"state\":\"running\",\"paused\":false,\"threads\":2,\"cpuLoad\":0.25,\"cpus\":8,\"heapUsedMb\":900,"
                + "\"heapMaxMb\":2048,\"tiles\":12,\"chunks\":432,\"bytes\":3145728,\"task\":5,\"connected\":true,\"needToken\":false}";
        List<String> lines = HudLines.of(status, new Summary(3, 200, 50));
        String all = String.join("\n", lines);
        assertTrue(all.contains("在跑"), all);
        assertTrue(all.contains("2 线程"), all);
        assertTrue(all.contains("2.0 核"), all);            // 0.25 × 8
        assertTrue(all.contains("900 / 2048 MB"), all);
        assertTrue(all.contains("12 tile") && all.contains("432 chunk") && all.contains("3.0 MB"), all);
        assertTrue(all.contains("在线 donor 3") && all.contains("你的贡献 25%"), all);
    }

    @Test
    void hudLinesWithMissingData() {
        String all = String.join("\n", HudLines.of("{\"state\":\"paused\",\"cpuLoad\":-1}", null));
        assertTrue(all.contains("暂停"), all);
        assertTrue(all.contains("CPU —"), all);             // cpuLoad 是 -1：不知道
        assertTrue(all.contains("在线 donor —"), all);
        String none = String.join("\n", HudLines.of(null, null));
        assertTrue(none.contains("worker 没在跑"), none);
        assertFalse(String.join("\n", HudLines.of("不是 JSON", null)).isEmpty());
    }
}
