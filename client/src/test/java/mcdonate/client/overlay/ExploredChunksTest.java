package mcdonate.client.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 小地图"探索"模式（用户 11-10：提前算好的航线不在小地图上剧透，只显示客户端真正加载过的 chunk）。 */
class ExploredChunksTest {
    private static final int RED = 0xFF0000;

    @Test
    void exploreHidesChunksNeverLoadedAndRemembersLoadedOnes() {
        ExploredChunks e = new ExploredChunks();
        assertEquals(-1, e.color(RED, true, 5, 5, false));    // 知道来源、但客户端没加载过：不画
        assertEquals(RED, e.color(RED, true, 5, 5, true));    // 现在加载着：画，并记住
        assertEquals(RED, e.color(RED, true, 5, 5, false));   // 后来卸掉了（飞远了）：还画
        assertEquals(-1, e.color(-1, true, 6, 6, true));      // 不知道来源的照样不画，也不记
        assertEquals(1, e.size());
    }

    @Test
    void allModeDrawsEverythingKnown() {
        ExploredChunks e = new ExploredChunks();
        assertEquals(RED, e.color(RED, false, 5, 5, false));
        assertEquals(-1, e.color(-1, false, 5, 5, true));
        assertEquals(0, e.size());   // all 模式不记
    }

    @Test
    void reconnectClears() {
        ExploredChunks e = new ExploredChunks();
        e.color(RED, true, 1, 1, true);
        e.clear();
        assertEquals(-1, e.color(RED, true, 1, 1, false));
    }

    @Test
    void cappedByDroppingChunksFarFromThePlayer() {
        // 和服务器的 A14、客户端来源表一样：超过上限时，离玩家超过 keep 个 chunk 的删掉，不然一路飞会一直涨
        ExploredChunks e = new ExploredChunks();
        for (int x = 0; x < 300; x++) {
            e.color(RED, true, x, 0, true);
        }
        e.prune(0, 0, 64, 1000);   // 没超上限：不动
        assertEquals(300, e.size());
        e.prune(0, 0, 64, 100);
        assertEquals(65, e.size());   // 留下 0～64
        assertEquals(RED, e.color(RED, true, 64, 0, false));
        assertEquals(-1, e.color(RED, true, 65, 0, false));
    }

    @Test
    void exploreSettingSavedDefaultOnAndTogglesKeepIt(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(OverlaySettings.FILE);
        assertTrue(OverlaySettings.load(f).explore());                       // 没有文件：默认开
        OverlaySettings s = OverlayToggles.explore(new OverlaySettings(true, false), false);
        assertFalse(s.explore());
        s.save(f);
        assertFalse(OverlaySettings.load(f).explore());
        assertFalse(OverlayToggles.show(s).explore());                      // 别的命令不把它改回来
        assertFalse(OverlayToggles.overlay(s, false).explore());
        assertFalse(OverlayToggles.hud(s, true).explore());
        Files.writeString(f, "overlay=true\nhud=false\n");                   // 老文件没有这一项：开
        assertTrue(OverlaySettings.load(f).explore());
    }

    @Test
    void jvmPropertyOverridesForThisSessionOnly() {
        OverlaySettings on = new OverlaySettings(true, true, true);
        assertFalse(OverlaySettings.withOverride(on, "false").explore());   // -Dmcdonate.mapExplore=false
        assertTrue(OverlaySettings.withOverride(new OverlaySettings(true, true, false), "true").explore());
        assertEquals(on, OverlaySettings.withOverride(on, null));            // 没设：照文件
    }
}
