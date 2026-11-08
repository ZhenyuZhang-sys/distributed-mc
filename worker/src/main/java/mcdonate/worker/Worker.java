package mcdonate.worker;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import mcdonate.common.ModIds;
import mcdonate.common.TilePos;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** donor mod：领任务、生成、导出。 */
public final class Worker implements ModInitializer {
    public static final String MOD_ID = ModIds.of("worker");
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // 调试命令，只给 OP 和控制台（RCON）用：
        //   /donor export <tx> <tz>  第 3 步：生成 tile 写进交换目录
        //   /donor sleep <秒>        测试用：让主线程卡住，验证网络线程照常发心跳
        //   /donor loaded            测试用：内存里的 chunk 数
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("donor")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("loaded").executes(ctx -> {
                            // 内存里的 chunk 数：测试 worker 做完任务后有没有把区域放掉
                            int n = ctx.getSource().getServer().overworld().getChunkSource().getLoadedChunksCount();
                            ctx.getSource().sendSuccess(() -> Component.literal(Integer.toString(n)), false);
                            return 1;
                        }))
                        .then(Commands.literal("sleep")
                                .requires(source -> source.hasPermission(4))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 60))
                                        .executes(ctx -> {
                                            int sec = IntegerArgumentType.getInteger(ctx, "seconds");
                                            LOG.info("主线程按要求卡 {} 秒（测试用）", sec);
                                            try {
                                                Thread.sleep(sec * 1000L);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                            ctx.getSource().sendSuccess(() -> Component.literal("slept " + sec), false);
                                            return 1;
                                        })))
                        .then(Commands.literal("export")
                                .then(Commands.argument("tx", IntegerArgumentType.integer())
                                        .then(Commands.argument("tz", IntegerArgumentType.integer())
                                                .executes(ctx -> export(ctx.getSource(),
                                                        new TilePos(IntegerArgumentType.getInteger(ctx, "tx"),
                                                                IntegerArgumentType.getInteger(ctx, "tz")))))))));
        // 第 5 步：配了 coordinator（系统属性 mcdonate.coordinator=host:port）就连上去领任务
        String address = SeedHandshake.address();
        if (address != null) {
            ServerLifecycleEvents.SERVER_STARTED.register(server -> {
                client = new WorkerClient(server, address);
                client.start();
            });
            ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
                if (client != null) {
                    client.stop();
                }
            });
        }
        LOG.info("{} loaded", MOD_ID);
    }

    private static volatile WorkerClient client;

    private static int export(CommandSourceStack source, TilePos tile) {
        try {
            TileExporter.Result r = TileExporter.export(source.getServer().overworld(), tile);
            LOG.info("导出 tile {}：{} 个 chunk，{} ms，{}", tile, r.chunks(), r.millis(), r.file());
            String json = r.json();
            source.sendSuccess(() -> Component.literal(json), false);
            return 1;
        } catch (TileExporter.Refused e) {
            LOG.warn("导出 tile {} 被拒绝：{}", tile, e.getMessage());
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        } catch (Exception e) {
            LOG.error("导出 tile {} 失败", tile, e);
            source.sendFailure(Component.literal("export failed: " + e));
            return 0;
        }
    }
}
