package mcdonate.client;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 第 9 步 e：设置界面（暂停菜单里的"mc-donate"按钮打开）：开关、用几个线程、多少内存，和当前状态。 */
public final class SettingsScreen extends Screen {
    private final Screen parent;
    private Button toggle;

    public SettingsScreen(Screen parent) {
        super(Component.literal("mc-donate 设置"));
        this.parent = parent;
    }

    private static ClientSettings settings() {
        return ClientSettings.load(ClientMod.settingsFile());
    }

    private static void save(ClientSettings s) {
        try {
            s.save(ClientMod.settingsFile());
        } catch (java.io.IOException e) {
            ClientMod.LOG.warn("设置写不了：{}", e.toString());
        }
        ClientMod.settingsChanged();
    }

    @Override
    protected void init() {
        int x = width / 2 - 100, y = height / 4 + 10;
        toggle = addRenderableWidget(Button.builder(label(), b -> toggle()).bounds(x, y, 200, 20).build());
        addRenderableWidget(CycleButton.<Integer>builder(v -> Component.literal(v + " 个线程"))
                .withValues(List.of(1, 2, 3, 4, 6, 8)).withInitialValue(settings().threads())
                .create(x, y + 26, 200, 20, Component.literal("生成线程"), (b, v) -> save(settings().withThreads(v))));
        addRenderableWidget(CycleButton.<Integer>builder(v -> Component.literal(v + " MB"))
                .withValues(List.of(1024, 2048, 3072, 4096, 6144, 8192)).withInitialValue(settings().memoryMb())
                .create(x, y + 52, 200, 20, Component.literal("内存上限"), (b, v) -> save(settings().withMemoryMb(v))));
        addRenderableWidget(Button.builder(Component.literal("完成"), b -> onClose()).bounds(x, y + 104, 200, 20).build());
    }

    private static Component label() {
        return Component.literal("帮服务器生成地形：" + (settings().enabled() && Consent.load(ClientMod.consentFile()).given() ? "开" : "关"));
    }

    /** 按开关：打开且还没同意过，先弹同意界面；同意过就直接开。关就直接关。 */
    public void toggle() {
        ClientSettings s = settings();
        boolean consented = Consent.load(ClientMod.consentFile()).given();
        if (s.enabled() && consented) {
            save(s.withEnabled(false));
        } else if (consented) {
            save(s.withEnabled(true));
        } else {
            minecraft.setScreen(new ConsentScreen(this, s, ClientMod::settingsChanged));
            return;
        }
        toggle.setMessage(label());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        g.drawCenteredString(font, title, width / 2, 20, 0xFFFFFF);
        g.drawCenteredString(font, Component.literal(ClientMod.statusLine()), width / 2, height / 4 + 88, 0xAAAAAA);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
