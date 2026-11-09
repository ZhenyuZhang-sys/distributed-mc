package mcdonate.worker;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.io.InputStream;
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
        //   /donor worldid           这个 donor 世界的编号（第 6 步）
        //   /donor hold on|off       测试用：做完的结果先扣着不发，off 时一起发（7.6a）
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("donor")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("worldid").executes(ctx -> {
                            String id = WorldId.of(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> Component.literal(id), false);
                            return 1;
                        }))
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
                        .then(Commands.literal("hold")
                                .requires(source -> source.hasPermission(4))
                                .then(Commands.literal("on").executes(ctx -> hold(ctx.getSource(), true)))
                                .then(Commands.literal("off").executes(ctx -> hold(ctx.getSource(), false))))
                        .then(Commands.literal("export")
                                .then(Commands.argument("tx", IntegerArgumentType.integer())
                                        .then(Commands.argument("tz", IntegerArgumentType.integer())
                                                .executes(ctx -> export(ctx.getSource(),
                                                        new TilePos(IntegerArgumentType.getInteger(ctx, "tx"),
                                                                IntegerArgumentType.getInteger(ctx, "tz")), false))
                                                // 对照用：按原来的办法一个一个同步要（11-09）
                                                .then(Commands.literal("serial").executes(ctx -> export(ctx.getSource(),
                                                        new TilePos(IntegerArgumentType.getInteger(ctx, "tx"),
                                                                IntegerArgumentType.getInteger(ctx, "tz")), true)))
                                                // 测吞吐用：沿 x 每隔 3 个 tile 共 n 个，先全部排上再一个一个导出（和收到几个任务时一样）
                                                .then(Commands.literal("batch").then(Commands.argument("n", IntegerArgumentType.integer(1, 16))
                                                        .executes(ctx -> exportBatch(ctx.getSource(),
                                                                new TilePos(IntegerArgumentType.getInteger(ctx, "tx"),
                                                                        IntegerArgumentType.getInteger(ctx, "tz")),
                                                                IntegerArgumentType.getInteger(ctx, "n"))))))))));
        // 第 5 步：配了 coordinator（系统属性 mcdonate.coordinator=host:port）就连上去领任务
        // 客户端起的 worker：服务器起来之前把标准输入拿给控制通道，不让原版控制台读走（ControlStdin）
        InputStream control = mcdonate.common.Config.getLong("parentPid", -1) > 0 ? ControlStdin.claim() : null;
        String address = SeedHandshake.address();
        if (address != null) {
            ServerLifecycleEvents.SERVER_STARTED.register(server -> {
                client = new WorkerClient(server, address);
                client.start();
                startParentWatch(server, control);
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

    /**
     * 第 9 步：客户端 mod 起的 worker（系统属性 mcdonate.parentPid）：游戏没了（PID 不在、标准输入 EOF）就停服务器退出；
     * 标准输入同时是控制通道（ControlLine：PAUSE、RESUME、STOP、STATUS、TOKEN）。Docker 里的 worker 没有 parentPid，不启动。
     */
    private static void startParentWatch(net.minecraft.server.MinecraftServer server, InputStream control) {
        long pid = mcdonate.common.Config.getLong("parentPid", -1);
        if (pid <= 0 || control == null) {
            return;
        }
        Runnable exit = () -> {
            LOG.info("游戏没了或要求停止，worker 退出");
            server.execute(() -> server.halt(false));
        };
        new ParentWatch(ParentWatch.pidAlive(pid), control, line -> {
            ControlLine.Command c = ControlLine.parse(line);
            if (c == null) {
                LOG.warn("读不懂的控制命令：{}", ControlLine.redact(line));
            } else if (c.kind() == ControlLine.Kind.STOP) {
                exit.run();
            } else if (client != null) {
                client.control(c);
            }
        }, exit).start();
        LOG.info("看着父进程 {}（游戏），标准输入是控制通道", pid);
    }

    private static int hold(CommandSourceStack source, boolean on) {
        WorkerClient c = client;
        if (c == null) {
            source.sendFailure(Component.literal("没连 coordinator"));
            return 0;
        }
        int sent = c.setHold(on);
        LOG.info("扣结果：{}（测试用）", on ? "开" : "关，发出 " + sent + " 个");
        source.sendSuccess(() -> Component.literal(on ? "holding" : "released " + sent), false);
        return 1;
    }

    private static int exportBatch(CommandSourceStack source, TilePos first, int n) {
        net.minecraft.server.level.ServerLevel level = source.getServer().overworld();
        long t0 = System.nanoTime();
        try {
            java.util.List<TilePos> tiles = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                tiles.add(new TilePos(first.x() + 3 * i, first.z()));
            }
            tiles.forEach(t -> TileExporter.prefetch(level, t));
            for (TilePos t : tiles) {
                TileExporter.export(level, t);
            }
        } catch (Exception e) {
            source.sendFailure(Component.literal("batch failed: " + e));
            return 0;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        source.sendSuccess(() -> Component.literal("{\"tiles\":" + n + ",\"ms\":" + ms + "}"), false);
        return 1;
    }

    private static int export(CommandSourceStack source, TilePos tile, boolean serial) {
        try {
            TileExporter.Result r = TileExporter.export(source.getServer().overworld(), tile, serial);
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
