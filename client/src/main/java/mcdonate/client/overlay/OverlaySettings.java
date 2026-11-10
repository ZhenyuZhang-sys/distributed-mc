package mcdonate.client.overlay;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * 游戏内来源上色（overlay）和算力面板（hud）的开关（docs/overlay-plan.md 第 5 节），存在
 * {@code .minecraft/config/mc-donate-overlay.properties}。和第 9 步的 mc-donate-settings.properties 分开，不碰那边。默认都关。
 *
 * <p>explore：小地图"探索"模式（用户 11-10），只给客户端加载过的 chunk 上色；默认开，老文件没有这一项也当开。
 * {@code -Dmcdonate.mapExplore=true|false} 启动时盖掉文件里的值（{@link #withOverride}）；之后用命令改任一开关时按当时的值存。
 */
public record OverlaySettings(boolean overlay, boolean hud, boolean explore) {
    public static final String FILE = "mc-donate-overlay.properties";

    public OverlaySettings(boolean overlay, boolean hud) {
        this(overlay, hud, true);
    }

    public OverlaySettings withExplore(boolean on) {
        return new OverlaySettings(overlay, hud, on);
    }

    /** JVM 参数 mcdonate.mapExplore（null = 没设）盖掉文件里的 explore。 */
    public static OverlaySettings withOverride(OverlaySettings s, String mapExplore) {
        return mapExplore == null ? s : s.withExplore(!"false".equalsIgnoreCase(mapExplore.trim()));
    }

    public static OverlaySettings load(Path file) {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            return new OverlaySettings(false, false);
        }
        return new OverlaySettings(bool(p, "overlay"), bool(p, "hud"), !"false".equalsIgnoreCase(p.getProperty("explore", "true").trim()));
    }

    /** 只认 true / false，别的（读不懂）当关。 */
    private static boolean bool(Properties p, String key) {
        return "true".equalsIgnoreCase(p.getProperty(key, "").trim());
    }

    public void save(Path file) throws IOException {
        Properties p = new Properties();
        p.setProperty("overlay", Boolean.toString(overlay));
        p.setProperty("hud", Boolean.toString(hud));
        p.setProperty("explore", Boolean.toString(explore));
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            p.store(w, "mc-donate 游戏内来源上色和算力面板");
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
