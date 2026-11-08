package mcdonate.bench;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import mcdonate.common.ModIds;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 测量 mod：生成计数、阶段计时。 */
public final class Bench implements ModInitializer {
    public static final String MOD_ID = ModIds.of("bench");
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // 只给 OP 和控制台（RCON）用：
        //   /bench stats          计数，一行 JSON
        //   /bench stages         各阶段计时，一行 JSON
        //   /bench timing on|off  阶段计时开关，默认关
        //   /bench reset          计数和计时都清零
        //   /bench track on|off   按 chunk 记录 noise、features（测试用，默认关）
        //   /bench chunk <x> <z>  这个 chunk 跑过几次 noise、features
        //   /bench view           每个玩家视距内已经做好（加载成成品）的 chunk 数，加上平均 MSPT（冒烟测试估虚空率用）
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("bench")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("stats").executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal(Counters.json()), false);
                            return 1;
                        }))
                        .then(Commands.literal("reset").executes(ctx -> {
                            Counters.reset();
                            Stages.reset();
                            ctx.getSource().sendSuccess(() -> Component.literal("bench counters reset"), false);
                            return 1;
                        }))
                        .then(Commands.literal("stages").executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal(Stages.json()), false);
                            return 1;
                        }))
                        .then(Commands.literal("track")
                                .then(Commands.literal("on").executes(ctx -> setTracking(ctx.getSource(), true)))
                                .then(Commands.literal("off").executes(ctx -> setTracking(ctx.getSource(), false))))
                        .then(Commands.literal("chunk")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> {
                                                    String s = Counters.ran(IntegerArgumentType.getInteger(ctx, "x"),
                                                            IntegerArgumentType.getInteger(ctx, "z"));
                                                    ctx.getSource().sendSuccess(() -> Component.literal(s), false);
                                                    return 1;
                                                }))))
                        .then(Commands.literal("view").executes(ctx -> {
                            String s = view(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> Component.literal(s), false);
                            return 1;
                        }))
                        .then(Commands.literal("timing")
                                .then(Commands.literal("on").executes(ctx -> setTiming(ctx.getSource(), true)))
                                .then(Commands.literal("off").executes(ctx -> setTiming(ctx.getSource(), false))))));
        LOG.info("{} loaded", MOD_ID);
    }

    /**
     * 简化的虚空率：视距内（方形，和原版发 chunk 的范围一样）还没有成品 chunk 的比例。
     * 第 4 步有正式的定义和测法，这个只给第 6 步冒烟用。在主线程上调用（命令就在主线程）。
     */
    static String view(MinecraftServer server) {
        int r = server.getPlayerList().getViewDistance();
        StringBuilder sb = new StringBuilder("{\"view\":").append(r).append(",\"players\":[");
        int i = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ServerLevel level = p.serverLevel();
            ChunkPos c = p.chunkPosition();
            int total = 0, ready = 0;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    total++;
                    if (level.getChunkSource().getChunkNow(c.x + dx, c.z + dz) != null) {
                        ready++;
                    }
                }
            }
            if (i++ > 0) {
                sb.append(',');
            }
            // tracked：ChunkMap 记录的玩家位置（chunk 坐标）。原版 tp 不更新它，要等 ServerChunkCache.move
            // （真玩家靠客户端回包，Carpet 假玩家每 10 tick 调一次）；它决定哪些 chunk 被加载、发给玩家。
            net.minecraft.core.SectionPos tracked = p.getLastSectionPos();
            sb.append("{\"name\":\"").append(p.getGameProfile().getName()).append("\",\"total\":").append(total)
                    .append(",\"ready\":").append(ready)
                    .append(",\"chunk\":[").append(c.x).append(',').append(c.z).append(']')
                    .append(",\"tracked\":[").append(tracked.x()).append(',').append(tracked.z()).append("]}");
        }
        return sb.append("],\"mspt\":").append(server.getAverageTickTimeNanos() / 1_000_000.0)
                .append(",\"tick\":").append(server.getTickCount()).append('}').toString();
    }

    private static int setTracking(CommandSourceStack source, boolean on) {
        Counters.setTracking(on);
        source.sendSuccess(() -> Component.literal("bench track " + (on ? "on" : "off")), false);
        return 1;
    }

    private static int setTiming(CommandSourceStack source, boolean on) {
        Stages.setEnabled(on);
        source.sendSuccess(() -> Component.literal("bench timing " + (on ? "on" : "off")), false);
        return 1;
    }
}
