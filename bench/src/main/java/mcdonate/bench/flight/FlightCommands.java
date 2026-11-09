package mcdonate.bench.flight;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /bench fly 子命令（docs/step4-plan.md 3.1）：
 * <pre>
 *   /bench fly start &lt;玩家&gt; &lt;格/秒&gt; &lt;秒数&gt; &lt;run名&gt; [wall|tick]   从玩家当前位置沿 +x 飞，高度不变
 *   /bench fly stop &lt;run名&gt;                                   提前结束，done.json 标 stopped
 *   /bench fly status                                         正在飞的，一行 JSON；返回值是个数
 *   /bench fly ready &lt;玩家&gt;                                   视野里没就绪的 chunk 数（一行 JSON；返回值就是这个数，出错 −1）
 *   /bench record start &lt;玩家&gt; [run名]                          跟踪真人玩家：只记录不移动（第 10 步 7.2），文件和飞行一样
 *   /bench record stop [run名]                                结束跟踪（不给 run 名就结束全部跟踪）
 * </pre>
 * 输出在服务器目录的 bench-runs/&lt;run名&gt;/。同一个玩家同时只能有一次飞行；run 名不能重复（目录已存在就拒绝）。
 * 多架假玩家（第 7 步容量测试）= 每架一个 run 名。
 */
public final class FlightCommands {
    private static final Logger LOG = LoggerFactory.getLogger("mcdonate-bench");
    private static final Pattern RUN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Map<String, FlightRecorder> ACTIVE = new LinkedHashMap<>();
    private static ExecutorService writer;

    private FlightCommands() {}

    /** 注册 tick 和停服事件。Bench.onInitialize 调一次。 */
    public static void registerEvents() {
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            mcdonate.bench.SyncLoads.onTickStart();
            mcdonate.bench.TickTimes.onTickStart();
            for (FlightRecorder r : new ArrayList<>(ACTIVE.values())) r.startTick(server);
            prune();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (FlightRecorder r : new ArrayList<>(ACTIVE.values())) r.endTick(server);
            prune();
            mcdonate.bench.TickTimes.onTickEnd();  // 放最后：飞行记录自己的开销也算进 tick
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (FlightRecorder r : new ArrayList<>(ACTIVE.values())) {
                if (r.recording()) r.finishRecord("server_stopping");
                else r.finish(false, "server_stopping");
            }
            ACTIVE.clear();
            if (writer != null) {
                writer.shutdown();
                try {
                    if (!writer.awaitTermination(10, TimeUnit.SECONDS)) LOG.warn("飞行输出 10 秒内没写完");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                writer = null;
            }
        });
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("fly")
                .then(Commands.literal("start")
                        .then(Commands.argument("player", EntityArgument.entity())
                                .then(Commands.argument("speed", DoubleArgumentType.doubleArg(0.001, 1000))
                                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 3600))
                                                .then(Commands.argument("run", StringArgumentType.word())
                                                        .executes(ctx -> start(ctx, Flight.Mode.WALL))
                                                        .then(Commands.literal("wall").executes(ctx -> start(ctx, Flight.Mode.WALL)))
                                                        .then(Commands.literal("tick").executes(ctx -> start(ctx, Flight.Mode.TICK))))))))
                .then(Commands.literal("stop")
                        .then(Commands.argument("run", StringArgumentType.word()).executes(FlightCommands::stop)))
                .then(Commands.literal("status").executes(FlightCommands::status))
                .then(Commands.literal("ready")
                        .then(Commands.argument("player", EntityArgument.entity()).executes(FlightCommands::ready)));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> recordNode() {
        return Commands.literal("record")
                .then(Commands.literal("start")
                        .then(Commands.argument("player", EntityArgument.entity())
                                .executes(ctx -> record(ctx, null))
                                .then(Commands.argument("run", StringArgumentType.word())
                                        .executes(ctx -> record(ctx, StringArgumentType.getString(ctx, "run"))))))
                .then(Commands.literal("stop")
                        .executes(ctx -> stopRecord(ctx, null))
                        .then(Commands.argument("run", StringArgumentType.word())
                                .executes(ctx -> stopRecord(ctx, StringArgumentType.getString(ctx, "run")))));
    }

    private static int record(CommandContext<CommandSourceStack> ctx, String run) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        if (player == null) return fail(ctx.getSource(), "不是玩家");
        if (run == null) {
            run = "rec-" + player.getGameProfile().getName() + "-"
                    + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now());
        }
        return begin(ctx.getSource(), player, run, null);
    }

    private static int stopRecord(CommandContext<CommandSourceStack> ctx, String run) {
        List<String> stopped = new ArrayList<>();
        for (FlightRecorder r : new ArrayList<>(ACTIVE.values())) {
            if (r.recording() && (run == null || r.run.equals(run))) {
                ACTIVE.remove(r.run);
                r.finishRecord("command");
                stopped.add(r.run);
            }
        }
        if (stopped.isEmpty()) return fail(ctx.getSource(), run == null ? "没有在跟踪的玩家" : "没有在跟踪的 run：" + run);
        ctx.getSource().sendSuccess(() -> Component.literal("bench record stopped " + String.join(" ", stopped)), false);
        return stopped.size();
    }

    private static int start(CommandContext<CommandSourceStack> ctx, Flight.Mode mode) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = player(ctx);
        if (player == null) return fail(src, "不是玩家");
        String run = StringArgumentType.getString(ctx, "run");
        Flight flight = Flight.eastward(player.getX(), player.getZ(), player.getY(),
                DoubleArgumentType.getDouble(ctx, "speed"), IntegerArgumentType.getInteger(ctx, "seconds"), mode);
        return begin(src, player, run, flight);
    }

    /** flight == null：跟踪真人玩家。 */
    private static int begin(CommandSourceStack src, ServerPlayer player, String run, Flight flight) {
        if (!RUN.matcher(run).matches()) return fail(src, "run 名只能用字母、数字、. _ -，最长 64：" + run);
        if (ACTIVE.containsKey(run)) return fail(src, "run 正在进行：" + run);
        for (FlightRecorder r : ACTIVE.values())
            if (r.playerId().equals(player.getUUID())) return fail(src, "这个玩家已经在飞或在被跟踪：" + r.run);
        MinecraftServer server = src.getServer();
        int view = server.getPlayerList().getViewDistance();
        if (view < 2) return fail(src, "服务器视距小于 2：" + view);
        Path dir = server.getServerDirectory().resolve("bench-runs").resolve(run);
        try {
            Files.createDirectories(dir.getParent());
            Files.createDirectory(dir);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            return fail(src, "bench-runs/" + run + " 已存在，换个 run 名");
        } catch (IOException e) {
            LOG.error("建飞行目录失败：{}", dir, e);
            return fail(src, "建目录失败：" + e.getMessage());
        }
        FlightRecorder r;
        try {
            FlightOutput out = new FlightOutput(dir, writer());
            r = flight == null ? FlightRecorder.record(run, player, view, out) : new FlightRecorder(run, player, flight, view, out);
        } catch (RuntimeException e) {
            LOG.error("起飞失败：{}", run, e);
            try {
                Files.deleteIfExists(dir);
            } catch (IOException ignored) {
                // 目录里已经有文件就留着，方便排查
            }
            return fail(src, "起飞失败：" + e.getMessage());
        }
        ACTIVE.put(run, r);
        String what = flight == null ? "bench record started " : "bench fly started ";
        src.sendSuccess(() -> Component.literal(what + run), false);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        String run = StringArgumentType.getString(ctx, "run");
        FlightRecorder r = ACTIVE.remove(run);
        if (r == null) return fail(ctx.getSource(), "没有正在飞的 run：" + run);
        r.finish(true, null);
        ctx.getSource().sendSuccess(() -> Component.literal("bench fly stopped " + run), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        JsonArray a = new JsonArray();
        for (FlightRecorder r : ACTIVE.values()) a.add(r.status());
        JsonObject o = new JsonObject();
        o.add("active", a);
        String s = o.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(s), false);
        return a.size();
    }

    private static int ready(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = player(ctx);
        if (p == null) {
            ctx.getSource().sendFailure(Component.literal("不是玩家"));
            return -1;
        }
        ViewTracker.Snapshot s = FlightRecorder.readiness(p, ctx.getSource().getServer().getPlayerList().getViewDistance());
        String json = "{\"player\":\"" + p.getGameProfile().getName() + "\",\"view\":" + s.view() + ",\"not_ready\":" + s.notReady() + "}";
        ctx.getSource().sendSuccess(() -> Component.literal(json), false);
        return s.notReady();
    }

    /** 玩家名或 UUID 都行（UUID 在原版算实体选择器，所以参数用 entity，再检查是不是玩家）。 */
    private static ServerPlayer player(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return EntityArgument.getEntity(ctx, "player") instanceof ServerPlayer p ? p : null;
    }

    private static int fail(CommandSourceStack src, String message) {
        src.sendFailure(Component.literal(message));
        return 0;
    }

    private static void prune() {
        ACTIVE.values().removeIf(FlightRecorder::finished);
    }

    private static synchronized ExecutorService writer() {
        if (writer == null) {
            writer = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mcdonate-bench-flight-writer");
                t.setDaemon(true);
                return t;
            });
        }
        return writer;
    }
}
