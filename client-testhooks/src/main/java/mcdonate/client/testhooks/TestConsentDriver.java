package mcdonate.client.testhooks;

import mcdonate.client.ConsentDriver;
import mcdonate.client.ConsentScreen;
import mcdonate.client.SettingsScreen;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 只给测试：替测试做玩家会做的事，走和点击完全一样的代码路径。
 * <ul>
 *   <li>进服后 3 秒：打开设置界面、按开关（就是按钮调的 toggle）；
 *   <li>同意界面打开：勾选 EULA（复选框的 onPress）、按"同意并打开"（按钮的 onPress）；
 *   <li>系统属性 mcdonate.testhooks.leaveAfter=秒：进服这么久以后退出世界（测"退出世界后 worker 停止"）。
 *   <li>系统属性 mcdonate.testhooks.slowFrom=秒、slowFor=秒、slowMs=毫秒：进服 slowFrom 秒后的 slowFor 秒里，每帧多睡 slowMs
 *       （人为拉长帧时间，测 9.6 限速：worker 收到 PAUSE，恢复后收到 RESUME）。
 *   <li>系统属性 mcdonate.testhooks.frameTargetMs=毫秒：进服时把设置里的帧时间目标改成这个（按开关之前，worker 起来时读）。
 *       整机忙时无头客户端平时的 p99 就有 36～64 ms，默认目标 50 ms 的"恢复"（低于 40 ms）等不到（11-09 f1）。
 * </ul>
 */
public final class TestConsentDriver implements ConsentDriver {
    private static final Logger LOG = LoggerFactory.getLogger("mcdonate-client-testhooks");

    private static volatile long slowUntil;
    private static volatile long slowStart = Long.MAX_VALUE;
    private static boolean hudHooked;

    @Override
    public void onJoin(Minecraft client) {
        long slowFrom = Long.getLong("mcdonate.testhooks.slowFrom", -1);
        if (slowFrom > 0) {
            long now = System.currentTimeMillis();
            slowStart = now + slowFrom * 1000;
            slowUntil = slowStart + Long.getLong("mcdonate.testhooks.slowFor", 20) * 1000;
            long ms = Long.getLong("mcdonate.testhooks.slowMs", 80);
            if (!hudHooked) {
                hudHooked = true;
                net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((g, t) -> {
                    long n = System.currentTimeMillis();
                    if (n >= slowStart && n < slowUntil) {
                        try {
                            Thread.sleep(ms);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
            }
            LOG.info("测试钩子：进服 {} 秒后拉长帧时间 {} ms，持续 {} 秒", slowFrom, ms, (slowUntil - slowStart) / 1000);
        }
        int target = Integer.getInteger("mcdonate.testhooks.frameTargetMs", -1);
        if (target > 0) {
            java.nio.file.Path f = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("mc-donate-settings.properties");
            mcdonate.client.ClientSettings s = mcdonate.client.ClientSettings.load(f);
            try {
                new mcdonate.client.ClientSettings(s.enabled(), s.threads(), s.memoryMb(), target).save(f);
                LOG.info("测试钩子：帧时间目标改成 {} ms", target);
            } catch (java.io.IOException e) {
                LOG.warn("测试钩子：改帧时间目标失败：{}", e.toString());
            }
        }
        later(3, () -> {
            LOG.info("测试钩子：打开设置、按开关");
            SettingsScreen s = new SettingsScreen(null);
            client.setScreen(s);
            s.toggle();
        }, client);
        long leave = Long.getLong("mcdonate.testhooks.leaveAfter", -1);
        if (leave > 0) {
            later(leave, () -> {
                LOG.info("测试钩子：退出世界");
                if (client.level != null) {
                    client.level.disconnect();
                }
                client.disconnect();
            }, client);
        }
    }

    @Override
    public void onConsentScreen(ConsentScreen screen) {
        LOG.info("测试钩子：同意界面，勾选 EULA、按同意（代替玩家点击）");
        screen.eulaCheckbox().onPress();
        screen.agreeButton().onPress();
    }

    private static void later(long seconds, Runnable r, Minecraft client) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(seconds * 1000);
            } catch (InterruptedException e) {
                return;
            }
            client.execute(r);
        }, "mcdonate-testhooks");
        t.setDaemon(true);
        t.start();
    }
}
