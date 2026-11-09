package mcdonate.coordinator;

import mcdonate.common.Config;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import mcdonate.common.ModIds;
import net.fabricmc.api.ModInitializer;
import java.io.IOException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import mcdonate.common.net.DonorInvite;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 服务器 mod：需求、调度、写入、验证。 */
public final class Coordinator implements ModInitializer {
    /** 每隔几个 tick 找一次需求（20 tick = 1 秒）。 */
    static final int SCAN_TICKS = Math.max(1, Config.getInt("scanTicks", 20));

    public static final String MOD_ID = ModIds.of("coordinator");
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // 调试命令，只给 OP 和控制台（RCON）用：
        //   /donor import <文件名>  把交换目录里的 tile 文件写进存档
        //   /donor peek <x> <z>     存档里这个 chunk 的状态（不加载）
        //   /donor enqueue <tx> <tz>  放一个任务（第 5 步）
        //   /donor status           worker 注册表和任务数，一行 JSON
        //   /donor imports [from]   导入记录（tile、donor 世界、是否整个丢弃），每页 30 条，测试用
        //   /donor owner <x> <z>    这个 chunk 归哪个 donor 世界（none = 没有归属）
        //   /donor assign <tx> <tz> <worker>  测试用：放任务并指定给某个 worker
        //   /donor rules strict|legacy        测试用：导入规则（默认 strict；legacy 是第 3 步的旧规则）
        //   /donor demand on|off    自动找需求的开关（正式默认开；测试服务器默认关）
        //   /donor feature <名字> on|off  第 7 步的开关（territory、deadline、throughput、hedge、waitdonor）
        //   /donor provenance       测试用：最近 64 条来源事件（coordinator 报给 common.Provenance 的 DONOR/REJECTED）
        //   /donor syncload <x> <z>  测试用：在主线程上同步要这个 chunk（FULL），回复用了多少毫秒（7.6a）
        //   /donor waitimport chunkcache|server  测试用：等 donor 的导入走哪个队列（7.6a，正确的是 chunkcache）
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
                        .then(Commands.literal("assign")
                                .then(Commands.argument("tx", IntegerArgumentType.integer())
                                        .then(Commands.argument("tz", IntegerArgumentType.integer())
                                                .then(Commands.argument("worker", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> reply(ctx.getSource(), network == null ? "not running"
                                                                : network.tasks.enqueueFor(IntegerArgumentType.getInteger(ctx, "tx"),
                                                                        IntegerArgumentType.getInteger(ctx, "tz"),
                                                                        IntegerArgumentType.getInteger(ctx, "worker"))
                                                                ? "queued" : "already queued or done"))))))
                        .then(Commands.literal("demand")
                                .then(Commands.literal("on").executes(ctx -> setDemand(ctx.getSource(), true)))
                                .then(Commands.literal("off").executes(ctx -> setDemand(ctx.getSource(), false))))
                        .then(Commands.literal("feature")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.literal("on").executes(ctx -> setFeature(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"), true)))
                                        .then(Commands.literal("off").executes(ctx -> setFeature(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"), false)))))
                        .then(Commands.literal("provenance").executes(ctx -> reply(ctx.getSource(), recentProvenance())))
                        .then(Commands.literal("syncload")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> {
                                                    long t0 = System.nanoTime();
                                                    ctx.getSource().getServer().overworld().getChunk(
                                                            IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "z"));
                                                    return reply(ctx.getSource(), "syncload " + (System.nanoTime() - t0) / 1_000_000 + " ms");
                                                }))))
                        .then(Commands.literal("waitimport")
                                .then(Commands.literal("chunkcache").executes(ctx -> setWaitImport(ctx.getSource(), true)))
                                .then(Commands.literal("server").executes(ctx -> setWaitImport(ctx.getSource(), false))))
                        .then(Commands.literal("rules")
                                .then(Commands.literal("strict").executes(ctx -> setStrict(ctx.getSource(), true)))
                                .then(Commands.literal("legacy").executes(ctx -> setStrict(ctx.getSource(), false))))
                        .then(Commands.literal("imports")
                                .executes(ctx -> reply(ctx.getSource(), network == null ? "{\"total\":0,\"items\":[]}"
                                        : network.importsJson(0)))
                                .then(Commands.argument("from", IntegerArgumentType.integer(0))
                                        .executes(ctx -> reply(ctx.getSource(), network == null
                                                ? "{\"total\":0,\"items\":[]}"
                                                : network.importsJson(IntegerArgumentType.getInteger(ctx, "from"))))))
                        .then(Commands.literal("owner")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> {
                                                    String o = Ownership.owner(net.minecraft.world.level.ChunkPos.asLong(
                                                            IntegerArgumentType.getInteger(ctx, "x"),
                                                            IntegerArgumentType.getInteger(ctx, "z")));
                                                    return reply(ctx.getSource(), o == null ? "none" : o);
                                                }))))
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
        // 导入账本（7.7）：启动时从世界目录读回来，每 5 秒把改过的 region 写盘，关服时（存完世界以后）再写一次
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                int n = Ownership.LEDGER.load(ledgerDir(server));
                LOG.info("导入账本：读回 {} 个 chunk", n);
            } catch (IOException e) {
                LOG.warn("导入账本读不了，按空的处理：{}", e.toString());
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 100 == 0) {
                flushLedger(server);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(Coordinator::flushLedger);
        // 第 9 步：玩家进服时，装了客户端 mod 的（注册了 mcdonate:invite 频道）收到 coordinator 地址、一次性 token、seed；
        // 原版客户端不注册这个频道，canSend 为假，不发。没配公开地址（mcdonate.publicHost）就不发。玩家退出：作废没用过的 token，断开他的 worker。
        PayloadTypeRegistry.playS2C().register(DonorInvite.TYPE, DonorInvite.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            CoordinatorServer n = network;
            if (n == null || PUBLIC_HOST.isEmpty() || !ServerPlayNetworking.canSend(handler.player, DonorInvite.TYPE)) {
                return;
            }
            String token = n.tokens.issue(handler.player.getUUID());
            DonorInvite invite = new DonorInvite(PUBLIC_HOST, PUBLIC_PORT, token, server.overworld().getSeed());
            if (!invite.valid()) {
                LOG.warn("公开地址 {}:{} 不合法，不发 donor 邀请", PUBLIC_HOST, PUBLIC_PORT);
                n.tokens.revoke(handler.player.getUUID());
                return;
            }
            ServerPlayNetworking.send(handler.player, invite);
            LOG.info("给 {} 发了 donor 邀请（token {}）", handler.player.getGameProfile().getName(), TokenRegistry.redact(token));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            CoordinatorServer n = network;
            if (n != null) {
                n.playerLeft(handler.player.getUUID());
            }
        });
        // 网络：服务器起来后监听，关服时停。端口 0 表示不开（系统属性 mcdonate.port，默认 25580）
        int port = Config.getInt("port", 25580);
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
            // 找需求：每秒（20 tick）在主线程上扫一次；没有 donor 连着就不扫
            ServerTickEvents.END_SERVER_TICK.register(server -> {
                CoordinatorServer n = network;
                if (n != null && server.getTickCount() % SCAN_TICKS == 0 && n.shouldScan()) {
                    n.demand.scan();
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

    /** 第 9 步：发给玩家的 coordinator 公开地址，空 = 不发邀请；端口默认和监听端口一样。 */
    static final String PUBLIC_HOST = Config.get("publicHost", "");
    static final int PUBLIC_PORT = Config.getInt("publicPort", Config.getInt("port", 25580));

    /** 最近的来源事件（测试用；实时地图的正式记录在 bench 的 provenance.jsonl，第 4 步 4.3）。 */
    private static final java.util.Deque<String> PROVENANCE = new java.util.concurrent.ConcurrentLinkedDeque<>();

    static {
        mcdonate.common.Provenance.addListener((cx, cz, kind, donor) -> {
            PROVENANCE.addLast("[" + cx + "," + cz + ",\"" + kind.name().toLowerCase() + "\"," + donor + "]");
            while (PROVENANCE.size() > 64) {
                PROVENANCE.pollFirst();
            }
        });
    }

    private static String recentProvenance() {
        return "[" + String.join(",", PROVENANCE) + "]";
    }

    static java.nio.file.Path ledgerDir(net.minecraft.server.MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("mcdonate").resolve("ledger");
    }

    private static void flushLedger(net.minecraft.server.MinecraftServer server) {
        try {
            Ownership.LEDGER.flush(ledgerDir(server));
        } catch (IOException e) {
            LOG.warn("导入账本写盘失败：{}", e.toString());
        }
    }

    private static int reply(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int setWaitImport(CommandSourceStack source, boolean chunkCache) {
        if (network != null) {
            network.waitDonor.importViaChunkCache = chunkCache;
        }
        return reply(source, "waitimport " + (chunkCache ? "chunkcache" : "server"));
    }

    private static int setFeature(CommandSourceStack source, String name, boolean on) {
        if (network == null) {
            return reply(source, "not running");
        }
        try {
            network.features.set(name, on);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        return reply(source, "features " + network.features.json());
    }

    private static int setDemand(CommandSourceStack source, boolean on) {
        if (network != null) {
            network.demandOn = on;
        }
        return reply(source, "demand " + (on ? "on" : "off"));
    }

    private static int setStrict(CommandSourceStack source, boolean on) {
        if (network != null) {
            network.strict = on;
        }
        return reply(source, "rules " + (on ? "strict" : "legacy"));
    }

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
