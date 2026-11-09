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
 */
public record OverlaySettings(boolean overlay, boolean hud) {
    public static final String FILE = "mc-donate-overlay.properties";

    public static OverlaySettings load(Path file) {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            return new OverlaySettings(false, false);
        }
        return new OverlaySettings(bool(p, "overlay"), bool(p, "hud"));
    }

    /** 只认 true / false，别的（读不懂）当关。 */
    private static boolean bool(Properties p, String key) {
        return "true".equalsIgnoreCase(p.getProperty(key, "").trim());
    }

    public void save(Path file) throws IOException {
        Properties p = new Properties();
        p.setProperty("overlay", Boolean.toString(overlay));
        p.setProperty("hud", Boolean.toString(hud));
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            p.store(w, "mc-donate 游戏内来源上色和算力面板");
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
