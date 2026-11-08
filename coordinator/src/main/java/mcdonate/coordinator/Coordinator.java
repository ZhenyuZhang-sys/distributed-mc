package mcdonate.coordinator;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import mcdonate.common.ModIds;
import net.fabricmc.api.ModInitializer;
import java.io.IOException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 服务器 mod：需求、调度、写入、验证。 */
public final class Coordinator implements ModInitializer {
    public static final String MOD_ID = ModIds.of("coordinator");
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // 调试命令，只给 OP 和控制台（RCON）用：
        //   /donor import <文件名>  把交换目录里的 tile 文件写进存档
        //   /donor peek <x> <z>     存档里这个 chunk 的状态（不加载）
        //   /donor enqueue <tx> <tz>  放一个任务（第 5 步）
        //   /donor status           worker 注册表和任务数，一行 JSON
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("donor")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("import")
                                .then(Commands.argument("file", StringArgumentType.greedyString())
                                        .executes(ctx -> importTile(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "file")))))
                        .then(Commands.literal("enqueue")
                                .then(Commands.argument("tx", IntegerArgumentType.integer())
                                        .then(Commands.argument("tz", IntegerArgumentType.integer())
                                                .executes(ctx -> enqueue(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "tx"),
                                                        IntegerArgumentType.getInteger(ctx, "tz"))))))
                        .then(Commands.literal("status").executes(ctx -> {
                            String s = network == null ? "{\"workers\":[],\"tasks\":null}" : network.statusJson();
                            ctx.getSource().sendSuccess(() -> Component.literal(s), false);
                            return 1;
                        }))
                        .then(Commands.literal("peek")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> {
                                                    String s = TileImporter.peek(ctx.getSource().getServer().overworld(),
                                                            IntegerArgumentType.getInteger(ctx, "x"),
                                                            IntegerArgumentType.getInteger(ctx, "z"));
                                                    ctx.getSource().sendSuccess(() -> Component.literal(s), false);
                                                    return 1;
                                                }))))));
        // 网络：服务器起来后监听，关服时停。端口 0 表示不开（系统属性 mcdonate.port，默认 25580）
        int port = Integer.getInteger("mcdonate.port", 25580);
        if (port > 0) {
            ServerLifecycleEvents.SERVER_STARTED.register(server -> {
                network = new CoordinatorServer(server, port);
                try {
                    network.start();
                } catch (IOException e) {
                    LOG.warn("coordinator 端口 {} 开不了：{}", port, e.toString());
                    network = null;
                }
            });
            ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
                if (network != null) {
                    network.stop();
                    network = null;
                }
            });
        }
        LOG.info("{} loaded", MOD_ID);
    }

    private static volatile CoordinatorServer network;

    private static int enqueue(CommandSourceStack source, int tx, int tz) {
        if (network == null) {
            source.sendFailure(Component.literal("coordinator network not running"));
            return 0;
        }
        boolean added = network.tasks.enqueue(tx, tz);
        String msg = added ? "queued " + tx + " " + tz : "already queued or done " + tx + " " + tz;
        source.sendSuccess(() -> Component.literal(msg), false);
        return added ? 1 : 0;
    }

    private static int importTile(CommandSourceStack source, String name) {
        try {
            TileImporter.Result r = TileImporter.importFile(source.getServer().overworld(), name);
            LOG.info("导入 {}：written={} loaded={} exists={}", name, r.written(), r.loaded(), r.exists());
            String json = r.json();
            source.sendSuccess(() -> Component.literal(json), false);
            return 1;
        } catch (TileImporter.Refused e) {
            LOG.warn("导入 {} 被拒绝：{}", name, e.getMessage());
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        } catch (Exception e) {
            LOG.error("导入 {} 失败", name, e);
            source.sendFailure(Component.literal("import failed: " + e));
            return 0;
        }
    }
}
