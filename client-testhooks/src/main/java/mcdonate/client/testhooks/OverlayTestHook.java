package mcdonate.client.testhooks;

import mcdonate.client.overlay.OverlayClient;
import mcdonate.client.overlay.OverlaySettings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 只给测试：游戏内来源上色的端到端（tests/step9/test_20_overlay.py）。按系统属性，进服后：
 * <ul>
 *   <li>mcdonate.testhooks.overlayOnAfter=秒：像打 /mcdonate show 那样打开 overlay 和 hud（走命令调的同一个方法）；
 *   <li>mcdonate.testhooks.hudLogAfter=秒：把算力面板现在的几行字记进日志；
 *   <li>mcdonate.testhooks.overlayOffAfter=秒：两样都关，10 秒后记下关掉以后又收到了几包。
 * </ul>
 * 和第 9 步的 TestConsentDriver 分开一个入口，不碰那边。
 */
public final class OverlayTestHook implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("mcdonate-client-testhooks");

    @Override
    public void onInitializeClient() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            long on = Long.getLong("mcdonate.testhooks.overlayOnAfter", -1);
            if (on > 0) {
                later(on, () -> {
                    LOG.info("测试钩子：打开 overlay 和 hud");
                    OverlayClient.set(new OverlaySettings(true, true));
                }, client);
            }
            long hud = Long.getLong("mcdonate.testhooks.hudLogAfter", -1);
            if (hud > 0) {
                later(hud, () -> LOG.info("测试钩子：算力面板 {}", String.join(" | ", OverlayClient.hudLines())), client);
            }
            long off = Long.getLong("mcdonate.testhooks.overlayOffAfter", -1);
            if (off > 0) {
                later(off, () -> {
                    LOG.info("测试钩子：关掉 overlay 和 hud");
                    OverlayClient.set(new OverlaySettings(false, false));
                }, client);
                later(off + 10, () -> LOG.info("测试钩子：关掉以后又收到 {} 包", OverlayClient.receivedWhileOff()), client);
            }
        });
    }

    private static void later(long seconds, Runnable r, Minecraft client) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(seconds * 1000);
            } catch (InterruptedException e) {
                return;
            }
            client.execute(r);
        }, "mcdonate-testhooks-overlay");
        t.setDaemon(true);
        t.start();
    }
}
