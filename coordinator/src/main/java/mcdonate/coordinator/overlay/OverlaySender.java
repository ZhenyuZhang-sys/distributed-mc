package mcdonate.coordinator.overlay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import mcdonate.common.Provenance;
import mcdonate.common.net.OverlaySubscribe;
import mcdonate.common.net.ProvenanceUpdate;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 游戏内来源上色的发送器（docs/overlay-plan.md 第 4 节）：把 {@link OverlayCore} 接到网络和 tick 上。
 *
 * <p>coordinator 初始化时调一次 {@link #install}（fish 加的那一行）：登记两个 payload、收订阅、在 Provenance 上挂监听、
 * 每 20 tick 在主线程上算一次并发给订阅了的玩家。只给 {@code canSend} 为真的玩家发（原版客户端不注册频道，也不会订阅）。
 * 两样都关的玩家不在 OverlayCore 里；没人订阅时每秒只把事件记进来源表，不发。
 */
public final class OverlaySender {
    private static final Logger LOG = LoggerFactory.getLogger("mcdonate-overlay");
    private static final int EVERY_TICKS = 20;
    private static OverlayCore core;

    private OverlaySender() {}

    /**
     * names：worker 编号 → 名字（主线程上调，比如 fish 的 {@code Coordinator.workerName(id).map(n -> new OverlayCore.Name(n.stable(), n.display()))}）；
     * donorsOnline：现在连着的 donor 个数。只能调一次。
     */
    public static synchronized OverlayCore install(IntFunction<Optional<OverlayCore.Name>> names, IntSupplier donorsOnline) {
        if (core != null) {
            return core;
        }
        OverlayCore c = new OverlayCore(names, donorsOnline);
        core = c;
        PayloadTypeRegistry.playC2S().register(OverlaySubscribe.TYPE, OverlaySubscribe.CODEC);
        PayloadTypeRegistry.playS2C().register(ProvenanceUpdate.TYPE, ProvenanceUpdate.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(OverlaySubscribe.TYPE, (payload, context) -> {
            UUID id = context.player().getUUID();
            // 能收来源数据的才记；在主线程上改订阅表
            if (ServerPlayNetworking.canSend(context.player(), ProvenanceUpdate.TYPE)) {
                context.server().execute(() -> {
                    c.subscribe(id, payload);
                    LOG.info("overlay 订阅：{} overlay={} hud={}", context.player().getGameProfile().getName(), payload.overlay(), payload.hud());
                });
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> server.execute(() -> c.remove(handler.getPlayer().getUUID())));
        // 换维度、重生（codex-audit K7）：每 20 tick 才看一次所在维度，1 秒内去别的维度又回来会漏掉重同步；换的那一刻就记下，
        // 下一次从整片快照重来（这两个事件都在主线程上）
        net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(
                (player, origin, destination) -> c.away(player.getUUID()));
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register(
                (oldPlayer, newPlayer, alive) -> c.away(newPlayer.getUUID()));
        Provenance.addListener(c::onEvent);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % EVERY_TICKS == 0) {
                send(server, c);
            }
        });
        return c;
    }

    /** 没装或没人订阅时是 null / 空的；给日志和测试命令用。 */
    public static OverlayCore core() {
        return core;
    }

    private static void send(MinecraftServer server, OverlayCore c) {
        int radius = server.getPlayerList().getViewDistance();
        List<OverlayCore.View> views = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) {
                c.away(p.getUUID());   // 来源只记主世界：在别的维度不发，回来从整片快照重来
                continue;
            }
            views.add(new OverlayCore.View(p.getUUID(), p.chunkPosition().x, p.chunkPosition().z, radius));
        }
        for (Map.Entry<UUID, List<ProvenanceUpdate>> e : c.tick(views).entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null || !ServerPlayNetworking.canSend(p, ProvenanceUpdate.TYPE)) {
                continue;
            }
            for (ProvenanceUpdate u : e.getValue()) {
                ServerPlayNetworking.send(p, u);
            }
        }
    }
}
