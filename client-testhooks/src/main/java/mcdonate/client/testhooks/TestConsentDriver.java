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
 * </ul>
 */
public final class TestConsentDriver implements ConsentDriver {
    private static final Logger LOG = LoggerFactory.getLogger("mcdonate-client-testhooks");

    @Override
    public void onJoin(Minecraft client) {
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
