package mcdonate.client;

import java.time.Instant;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 第 9 步 a：同意界面（第一次打开"帮服务器生成地形"时弹出）。说明会用多少 CPU 和内存、会下载什么；
 * 玩家本人勾选"我已阅读并同意 Minecraft EULA"才能按"同意"。同意后记录（{@link Consent}）、打开开关、回到上一个界面。
 */
public final class ConsentScreen extends Screen {
    private final Screen parent;
    private final ClientSettings settings;
    private final Runnable onAccepted;
    private Checkbox eula;
    private Button agree;
    private MultiLineLabel text;

    public ConsentScreen(Screen parent, ClientSettings settings, Runnable onAccepted) {
        super(Component.literal("mc-donate：帮服务器生成地形"));
        this.parent = parent;
        this.settings = settings;
        this.onAccepted = onAccepted;
    }

    @Override
    protected void init() {
        text = MultiLineLabel.create(font, Component.literal(
                "打开以后，在你玩这个服务器的时候，你的电脑会帮服务器提前生成地形。\n\n"
                        + "· 会用最多 " + settings.threads() + " 个 CPU 线程、" + settings.memoryMb() + " MB 内存（设置里能改）；"
                        + "帧率低于 " + (1000 / settings.frameTargetMs()) + " 帧/秒时自动暂停。\n"
                        + "· 第一次会从 Mojang 官方地址下载 Minecraft 服务器（约 50 MB），放在 .minecraft/mc-donate/worker/。\n"
                        + "· 后台起一个 Java 进程（低优先级），退出世界或关游戏时停止。\n"
                        + "· 运行 Minecraft 服务器需要你本人同意 Minecraft EULA：https://aka.ms/MinecraftEULA"), width - 40);
        int y = height / 2 + 30;
        eula = addRenderableWidget(Checkbox.builder(Component.literal("我已阅读并同意 Minecraft EULA"), font)
                .pos(width / 2 - 100, y).onValueChange((cb, on) -> agree.active = on).build());
        agree = addRenderableWidget(Button.builder(Component.literal("同意并打开"), b -> accept())
                .bounds(width / 2 - 102, y + 28, 100, 20).build());
        agree.active = false;
        addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose()).bounds(width / 2 + 2, y + 28, 100, 20).build());
        for (ConsentDriver d : FabricLoader.getInstance().getEntrypoints("mcdonate:consent_driver", ConsentDriver.class)) {
            d.onConsentScreen(this);
        }
    }

    private void accept() {
        if (!eula.selected()) {
            return;
        }
        try {
            Consent.accept(ClientMod.consentFile(), Instant.now());
            settings.withEnabled(true).save(ClientMod.settingsFile());
        } catch (java.io.IOException e) {
            ClientMod.LOG.warn("同意记录写不了：{}", e.toString());
            return;
        }
        ClientMod.LOG.info("玩家同意了（含 Minecraft EULA），打开 donor");
        onClose();
        onAccepted.run();
    }

    /** 测试钩子用：勾选框和同意按钮（按它们走的是和玩家点击一样的 onPress）。 */
    public Checkbox eulaCheckbox() {
        return eula;
    }

    public Button agreeButton() {
        return agree;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        g.drawCenteredString(font, title, width / 2, 20, 0xFFFFFF);
        text.renderCentered(g, width / 2, 45);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
