package mcdonate.client.overlay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import mcdonate.common.net.OverlaySubscribe;
import mcdonate.common.net.ProvenanceUpdate;
import mcdonate.common.net.ProvenanceUpdate.Donor;
import mcdonate.common.net.ProvenanceUpdate.Source;
import mcdonate.common.overlay.DonorColors;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 游戏内来源上色（overlay）和算力面板（hud）的客户端（docs/overlay-plan.md 第 5 节，第一期：角落小地图 + 图例 + 算力面板；
 * 地面彩色边框是第二期）。第 9 步的 ClientMod 初始化时调一次 {@link #install}，worker 的输出行转给 {@link #onWorkerLine}。
 *
 * <ul>
 *   <li>命令：/mcdonate show、/mcdonate overlay on|off、/mcdonate hud on|off，只在自己的客户端生效，开关存进 mc-donate-overlay.properties。
 *   <li>开关变了、或者进服后服务器的频道就绪了，发一次订阅（mcdonate:overlay）；两样都关时服务器不发、这边也不算。
 *   <li>hud 开着时每秒往 worker 写一行 STATUS，读回的 JSON 进算力面板。
 * </ul>
 */
public final class OverlayClient {
    private static final Logger LOG = LoggerFactory.getLogger("mcdonate-overlay");
    private static final int MAP_PX = 128;
    private static final int MAP_RADIUS = 10;

    /** 和第 9 步的 worker 子进程打交道（ClientMod 实现）：在不在跑、写一行到它的标准输入。 */
    public interface WorkerLink {
        boolean running();

        void send(String line);
    }

    private static OverlaySettings settings = new OverlaySettings(false, false);
    private static final ChunkSources SOURCES = new ChunkSources();
    private static WorkerLink link;
    private static volatile String latestStatus;
    private static boolean subscribedThisConnection;
    private static long receivedWhileOff;   // 两样都关着时还收到的包（端到端测试看服务器有没有停）
    private static boolean incrementLogged;
    private static int ticks;

    private OverlayClient() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(OverlaySettings.FILE);
    }

    public static void install(WorkerLink workerLink) {
        link = workerLink;
        settings = OverlaySettings.load(file());
        PayloadTypeRegistry.playS2C().register(ProvenanceUpdate.TYPE, ProvenanceUpdate.CODEC);
        PayloadTypeRegistry.playC2S().register(OverlaySubscribe.TYPE, OverlaySubscribe.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(ProvenanceUpdate.TYPE, (update, context) -> onUpdate(update));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            subscribedThisConnection = false;
            SOURCES.clear();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(SOURCES::clear));
        ClientTickEvents.END_CLIENT_TICK.register(OverlayClient::onTick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("mcdonate")
                        .then(ClientCommandManager.literal("show").executes(ctx -> set(OverlayToggles.show(settings))))
                        .then(ClientCommandManager.literal("overlay")
                                .then(ClientCommandManager.literal("on").executes(ctx -> set(OverlayToggles.overlay(settings, true))))
                                .then(ClientCommandManager.literal("off").executes(ctx -> set(OverlayToggles.overlay(settings, false)))))
                        .then(ClientCommandManager.literal("hud")
                                .then(ClientCommandManager.literal("on").executes(ctx -> set(OverlayToggles.hud(settings, true))))
                                .then(ClientCommandManager.literal("off").executes(ctx -> set(OverlayToggles.hud(settings, false)))))));
        HudRenderCallback.EVENT.register((graphics, tick) -> render(graphics));
    }

    /** 测试钩子和命令都走这里：改开关、存盘、通知服务器。返回 1（brigadier 的成功）。 */
    public static int set(OverlaySettings s) {
        boolean overlayWasOn = settings.overlay();
        settings = s;
        try {
            s.save(file());
        } catch (IOException e) {
            LOG.warn("存 overlay 设置失败：{}", e.toString());
        }
        if (overlayWasOn && !s.overlay()) {
            SOURCES.clear();
        }
        if (!s.overlay() && !s.hud()) {
            receivedWhileOff = 0;
        }
        subscribedThisConnection = false;   // 下一个 tick 重新发订阅
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(OverlayToggles.describe(s)), false);
        }
        LOG.info("overlay 开关：overlay={} hud={}", s.overlay(), s.hud());
        return 1;
    }

    public static OverlaySettings settings() {
        return settings;
    }

    /** 算力面板现在的几行字（测试钩子记日志用）。 */
    public static java.util.List<String> hudLines() {
        return HudLines.of(latestStatus, SOURCES.summary());
    }

    /** 两样都关着时还收到的包数。 */
    public static long receivedWhileOff() {
        return receivedWhileOff;
    }

    public static ChunkSources sources() {
        return SOURCES;
    }

    /** worker 标准输出里给游戏看的一行（去掉 MCDONATE 前缀以后）。 */
    public static void onWorkerLine(String body) {
        if (body.startsWith("STATUS ")) {
            latestStatus = body.substring("STATUS ".length());
        }
    }

    private static void onUpdate(ProvenanceUpdate u) {
        if (!settings.overlay() && !settings.hud()) {
            receivedWhileOff++;
            return;   // 都关了还在路上的包：不算
        }
        boolean first = SOURCES.updates() == 0 || u.full();
        SOURCES.apply(u);
        if (first) {
            incrementLogged = false;
            LOG.info("overlay 收到来源快照：{} 个 chunk，{} 个 donor", SOURCES.size(), SOURCES.donorNames().size());
        } else if (!incrementLogged && !u.chunks().isEmpty()) {
            incrementLogged = true;
            LOG.info("overlay 收到来源增量：第一包 {} 个 chunk，现在一共 {} 个", u.chunks().size(), SOURCES.size());
        } else if (SOURCES.updates() % 30 == 0) {
            LOG.info("overlay 收到来源增量：累计 {} 包，现在 {} 个 chunk", SOURCES.updates(), SOURCES.size());
        }
    }

    private static void onTick(Minecraft client) {
        if (client.player == null) {
            return;
        }
        if (!subscribedThisConnection && ClientPlayNetworking.canSend(OverlaySubscribe.TYPE)) {
            ClientPlayNetworking.send(new OverlaySubscribe(settings.overlay(), settings.hud()));
            subscribedThisConnection = true;
        }
        if (++ticks % 100 == 0) {   // 每 5 秒：来源表超过 2 万个就删掉离玩家 64 圈以外的
            SOURCES.prune(client.player.chunkPosition().x, client.player.chunkPosition().z, 64, 20_000);
        }
        if (settings.hud() && ticks % 20 == 0 && link != null && link.running()) {
            link.send("STATUS");
        }
        if (link != null && !link.running()) {
            latestStatus = null;
        }
    }

    private static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        if (settings.hud()) {
            int y = 6;
            for (String line : HudLines.of(latestStatus, SOURCES.summary())) {
                g.drawString(mc.font, line, 6, y, 0xFFFFFFFF, true);
                y += 10;
            }
        }
        if (settings.overlay()) {
            drawMap(g, mc);
        }
    }

    private static void drawMap(GuiGraphics g, Minecraft mc) {
        int cells = 2 * MAP_RADIUS + 1, cell = Math.max(1, MAP_PX / cells), size = cell * cells;
        int x0 = g.guiWidth() - size - 6, y0 = 6;
        int pcx = mc.player.chunkPosition().x, pcz = mc.player.chunkPosition().z;
        g.fill(x0 - 1, y0 - 1, x0 + size + 1, y0 + size + 1, 0x90000000);
        for (int dx = -MAP_RADIUS; dx <= MAP_RADIUS; dx++) {
            for (int dz = -MAP_RADIUS; dz <= MAP_RADIUS; dz++) {
                int c = SOURCES.color(pcx + dx, pcz + dz);
                if (c != -1) {
                    int x = x0 + (dx + MAP_RADIUS) * cell, y = y0 + (dz + MAP_RADIUS) * cell;
                    g.fill(x, y, x + cell - (cell > 3 ? 1 : 0), y + cell - (cell > 3 ? 1 : 0), 0xD0000000 | c);
                }
            }
        }
        // 玩家：所在 chunk 里按朝向画一个箭头（用户 11-09：鞘翅飞的时候看出往哪飞），北朝上，地图不转。
        // 位置按玩家在 chunk 里的实际坐标，深色描边加白色箭头
        float px = x0 + (MAP_RADIUS + (float) (mc.player.getX() / 16 - pcx)) * cell;
        float py = y0 + (MAP_RADIUS + (float) (mc.player.getZ() / 16 - pcz)) * cell;
        float len = PlayerArrow.size(cell);
        g.flush();   // 先把上面攒着的格子画出去，箭头才压在格子上面
        triangle(g, PlayerArrow.outline(mc.player.getYRot(), px, py, len), 0xFF101010);
        triangle(g, PlayerArrow.triangle(mc.player.getYRot(), px, py, len), 0xFFFFFFFF);
        // 图例：四类 + donor（最多 8 个，多的折成"其他 N 个"）
        int y = y0 + size + 4;
        y = legend(g, mc, x0, y, DonorColors.of(Source.SERVER), "服务器自己生成");
        y = legend(g, mc, x0, y, DonorColors.of(Source.RESUMED), "导入后服务器接着做完");
        y = legend(g, mc, x0, y, DonorColors.of(Source.REJECTED), "被打回");
        List<Donor> donors = SOURCES.donors();
        for (int i = 0; i < Math.min(8, donors.size()); i++) {
            y = legend(g, mc, x0, y, DonorColors.donor(donors.get(i).key()), "donor " + donors.get(i).name());
        }
        if (donors.size() > 8) {
            g.drawString(mc.font, "其他 " + (donors.size() - 8) + " 个 donor", x0, y, 0xFFCCCCCC, true);
            y += 10;
        }
        g.drawString(mc.font, "没颜色 = 来源不明", x0, y, 0xFFAAAAAA, true);
    }

    private static void triangle(GuiGraphics g, float[] t, int argb) {
        org.joml.Matrix4f m = g.pose().last().pose();
        com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.disableCull();   // 朝向不同时顶点的绕向会反过来
        com.mojang.blaze3d.vertex.BufferBuilder b = com.mojang.blaze3d.vertex.Tesselator.getInstance().begin(
                com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < 6; i += 2) {
            b.addVertex(m, t[i], t[i + 1], 0).setColor(argb);
        }
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(b.buildOrThrow());
        com.mojang.blaze3d.systems.RenderSystem.enableCull();
    }

    private static int legend(GuiGraphics g, Minecraft mc, int x, int y, int rgb, String text) {
        g.fill(x, y + 1, x + 7, y + 8, 0xFF000000 | rgb);
        g.drawString(mc.font, text, x + 10, y, 0xFFFFFFFF, true);
        return y + 10;
    }

}
