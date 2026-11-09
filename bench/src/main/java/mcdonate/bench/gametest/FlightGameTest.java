package mcdonate.bench.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * 第 4 步 4.2：/bench fly 和 FlightRecorder（docs/step4-plan.md 3.1、4 节）。
 * 全部通过命令字符串驱动，和 Python 端用 RCON 的方式一样。
 */
/* GameTest 服务器不按 20 TPS 限速（实测约 100 TPS），墙钟飞行的超时要按 tick 数放宽。 */
public class FlightGameTest implements FabricGameTest {
    static final int VIEW = 10;

    static int run(GameTestHelper h, String command) {
        MinecraftServer server = h.getLevel().getServer();
        CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
        try {
            return server.getCommands().getDispatcher().execute(command, source);
        } catch (CommandSyntaxException e) {
            throw new GameTestAssertException("命令语法错误：" + command + "：" + e.getMessage());
        }
    }

    static Path dir(GameTestHelper h, String run) {
        return h.getLevel().getServer().getServerDirectory().resolve("bench-runs").resolve(run);
    }

    static String newRun(String prefix) {
        return "gametest-" + prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 放一个模拟玩家到某个 chunk 的最东一格（往 +x 走一格就跨进下一个 chunk）。 */
    static ServerPlayer player(GameTestHelper h) {
        // GameTest 服务器的视距小于 2（不是正式配置）；换成协议的 10，视野才是 453 个 chunk。GameTest 世界是超平坦，生成很快。
        if (h.getLevel().getServer().getPlayerList().getViewDistance() != VIEW) h.getLevel().getServer().getPlayerList().setViewDistance(VIEW);
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        BlockPos at = h.absolutePos(new BlockPos(1, 2, 1));
        double x = Math.floorDiv(at.getX(), 16) * 16 + 15.5;
        p.teleportTo(h.getLevel(), x, at.getY() + 40, at.getZ() + 0.5, 0, 0);
        // 原版同维度 teleportTo 不更新 ChunkMap 里的玩家位置（真客户端回移动包时才更新；Carpet 假玩家每 tick 自己更新）
        h.getLevel().getChunkSource().move(p);
        return p;
    }

    static JsonObject done(GameTestHelper h, String run) {
        Path f = dir(h, run).resolve("done.json");
        if (!Files.exists(f)) throw new GameTestAssertException("还没写 done.json：" + run);
        try {
            return JsonParser.parseString(Files.readString(f)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<JsonObject> lines(GameTestHelper h, String run, String file) {
        Path f = dir(h, run).resolve(file);
        // 没有文件按断言失败处理（重试到超时）；别的异常会让 GameTest 服务器整个崩掉
        if (!Files.exists(f)) throw new GameTestAssertException("没有 " + file + "：" + run);
        try {
            List<JsonObject> out = new ArrayList<>();
            for (String line : Files.readAllLines(f))
                if (!line.isBlank()) out.add(JsonParser.parseString(line).getAsJsonObject());
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 取字段；没有就按断言失败（重试到超时），不抛空指针让 GameTest 服务器崩掉。 */
    static com.google.gson.JsonElement field(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) throw new GameTestAssertException("缺字段 " + key + "：" + o);
        return o.get(key);
    }

    static void check(boolean ok, String message) {
        if (!ok) throw new GameTestAssertException(message);
    }

    static void delete(GameTestHelper h, String run) {
        try (Stream<Path> s = Files.walk(dir(h, run))) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 失败时看是哪几圈没就绪：圈号 → 个数，以及其中有 ChunkHolder 但还没到可发送状态的个数。 */
    static String notReadyByRing(GameTestHelper h, ServerPlayer p) {
        DIAG.setLength(0);
        java.util.TreeMap<Integer, int[]> rings = new java.util.TreeMap<>();
        ChunkPos c = p.chunkPosition();
        var map = (mcdonate.bench.mixin.ChunkMapAccess) h.getLevel().getChunkSource().chunkMap;
        ChunkTrackingView.of(c, VIEW).forEach(q -> {
            var holder = map.mcdonate$visibleHolder(q.toLong());
            if (holder == null || holder.getChunkToSend() == null) {
                int[] n = rings.computeIfAbsent(Math.max(Math.abs(q.x - c.x), Math.abs(q.z - c.z)), k -> new int[2]);
                n[0]++;
                if (holder != null) n[1]++;
                if (holder != null && DIAG.length() < 1500) DIAG.append(String.format(" (%d,%d)L%d %s t=%s", q.x - c.x, q.z - c.z,
                        holder.getTicketLevel(), holder.getFullStatus(), holder.getTickingChunk() != null));
            }
        });
        StringBuilder sb = new StringBuilder();
        rings.forEach((d, n) -> sb.append(d).append(':').append(n[0]).append("(有holder ").append(n[1]).append(") "));
        return sb.append(DIAG).toString();
    }

    static final StringBuilder DIAG = new StringBuilder();

    static int viewSize(GameTestHelper h) {
        int[] n = {0};
        ChunkTrackingView.of(new ChunkPos(0, 0), h.getLevel().getServer().getPlayerList().getViewDistance()).forEach(p -> n[0]++);
        return n[0];
    }

    /** 墙钟模式走路速度飞 3 秒：玩家按速度往 +x 走，seconds.jsonl 正好 3 行，视野数等于原版发包范围。 */
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 12000)
    public void wallFlightWritesOneRowPerSecond(GameTestHelper h) {
        ServerPlayer p = player(h);
        double x0 = p.getX(), z0 = p.getZ();
        String run = newRun("wall");
        int view = viewSize(h);
        check(view == 453, "视距 10 时原版发包范围应是 453 个 chunk：" + view);
        // 起飞前等初始视野全部就绪（和 layer2.fly 一样）；飞行只走进已就绪的 chunk
        h.startSequence().thenWaitUntil(() -> check(run(h, "bench fly ready " + p.getStringUUID()) == 0, "初始视野没就绪"))
                .thenExecute(() -> check(run(h, "bench fly start " + p.getStringUUID() + " 4.3 3 " + run + " wall") == 1, "fly start 失败"))
                .thenWaitUntil(() -> {
            JsonObject done = done(h, run);
            List<JsonObject> rows = lines(h, run, "seconds.jsonl");
            check(rows.size() == 3, "seconds.jsonl 应有 3 行，实际 " + rows.size());
            for (int i = 0; i < 3; i++) {
                JsonObject r = rows.get(i);
                check(r.get("t_s").getAsInt() == i + 1, "t_s 不连续：" + r);
                check(r.get("view_chunks").getAsInt() == view, "视野数应为 " + view + "：" + r);
                double dx = r.get("x").getAsDouble() - x0;
                check(Math.abs(dx - 4.3 * (i + 1)) < 1.0, "第 " + (i + 1) + " 秒位置不对：dx=" + dx);
                check(Math.abs(r.get("z").getAsDouble() - z0) < 1e-6, "z 不该变：" + r);
                check(r.get("ticks").getAsInt() > 0, "这一秒没有 tick：" + r);
            }
            check(Math.abs(p.getX() - x0 - 12.9) < 0.5, "玩家实际位置没跟上：dx=" + (p.getX() - x0));
            check(done.get("rows").getAsInt() == 3 && !done.get("stopped").getAsBoolean() && done.get("aborted").isJsonNull(),
                    "done.json 不对：" + done);
            check(done.get("view_distance").getAsInt() == h.getLevel().getServer().getPlayerList().getViewDistance(), "视距：" + done);
            check(done.has("requested_view_distance") && done.has("available_processors") && done.has("initial"), "done.json 缺字段：" + done);
            // 第一个 tick 的 System.nanoTime：宿主机用同一个单调时钟采 cgroup，靠它对齐每秒 CPU
            check(done.has("t0_nanotime") && done.get("t0_nanotime").getAsLong() > 0, "done.json 缺 t0_nanotime：" + done);
            check(done.get("scan_every_ticks").getAsInt() == 1, "done.json 缺 scan_every_ticks：" + done);
            List<JsonObject> events = lines(h, run, "chunks.jsonl");
            long enters = events.stream().filter(e -> e.get("ev").getAsString().equals("enter")).count();
            check(enters > 0, "跨过 chunk 边界却没有 enter 事件");
            long last = Long.MIN_VALUE;
            for (JsonObject e : events) {
                long t = e.get("t_ms").getAsLong();
                check(t >= last && t <= done.get("end_ms").getAsLong(), "事件时间倒退或晚于结束：" + e);
                last = t;
            }
            check(FlightGameTest.field(done, "stalled_ticks").getAsInt() == 0 && FlightGameTest.field(done, "unready_ticks").getAsInt() == 0, "走路速度不该卡住：" + done);
            check(Math.abs(FlightGameTest.field(done, "distance").getAsDouble() - 12.9) < 0.5, "实际距离：" + done);
            delete(h, run);
        }).thenSucceed();
    }

    /** tick 模式：位置只跟 tick 数走，飞满 1 秒正好前进 速度 × 1 格。 */
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 12000)
    public void tickFlightMovesByTicks(GameTestHelper h) {
        ServerPlayer p = player(h);
        double x0 = p.getX();
        String run = newRun("tick");
        h.startSequence().thenWaitUntil(() -> check(run(h, "bench fly ready " + p.getStringUUID()) == 0, "初始视野没就绪"))
                .thenExecute(() -> check(run(h, "bench fly start " + p.getStringUUID() + " 20 1 " + run + " tick") == 1, "fly start 失败"))
                .thenWaitUntil(() -> {
            JsonObject done = done(h, run);
            check(done.get("mode").getAsString().equals("tick"), "mode：" + done);
            check(done.get("ticks").getAsInt() >= 20, "tick 模式飞 1 秒至少 20 tick：" + done);
            // 1 秒 20 格还在已就绪的视野里，不会卡住：正好前进 20 格
            check(FlightGameTest.field(done, "stalled_ticks").getAsInt() == 0, "不该卡住：" + done);
            check(Math.abs(done.get("end_x").getAsDouble() - x0 - 20) < 1e-6, "应正好前进 20 格：" + done);
            check(Math.abs(FlightGameTest.field(done, "distance").getAsDouble() - 20) < 1e-6, "distance：" + done);
            delete(h, run);
        }).thenSucceed();
    }

    /**
     * 像真实客户端一样（11-09，看门狗问题）：前面的 chunk 没就绪就停在边缘，不进去，所以玩家的实体 tick 不会在主线程上同步生成。
     * tick 模式每 tick 50 格，生成跟不上：一定会卡住，但每个 tick 结束时玩家所在的 chunk 都已就绪。
     */
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 12000)
    public void fastFlightStallsAtEdgeInsteadOfEnteringUnreadyChunks(GameTestHelper h) {
        ServerPlayer p = player(h);
        String run = newRun("edge");
        h.startSequence().thenWaitUntil(() -> check(run(h, "bench fly ready " + p.getStringUUID()) == 0, "初始视野没就绪"))
                .thenExecute(() -> check(run(h, "bench fly start " + p.getStringUUID() + " 1000 2 " + run + " tick") == 1, "fly start 失败"))
                .thenWaitUntil(() -> {
            JsonObject done = done(h, run);
            check(FlightGameTest.field(done, "unready_ticks").getAsInt() == 0, "有 tick 结束时玩家在没就绪的 chunk 里：" + done);
            check(FlightGameTest.field(done, "stalled_ticks").getAsInt() > 0 && FlightGameTest.field(done, "stall_ms").getAsDouble() > 0, "生成跟不上却没卡住：" + done);
            check(FlightGameTest.field(done, "distance").getAsDouble() < FlightGameTest.field(done, "ideal_distance").getAsDouble(), "卡住了距离应该比理想的短：" + done);
            check(Math.abs(FlightGameTest.field(done, "ideal_distance").getAsDouble() - 2000) < 1e-6, "理想距离 = 速度 × 时长：" + done);
            JsonObject sync = FlightGameTest.field(done, "sync_loads").getAsJsonObject();
            check(sync.has("count") && sync.has("max_ms") && sync.has("stack"), "缺同步加载计数：" + done);
            long stalled = lines(h, run, "seconds.jsonl").stream().mapToLong(r -> FlightGameTest.field(r, "stalled_ticks").getAsLong()).sum();
            check(stalled == FlightGameTest.field(done, "stalled_ticks").getAsLong(), "逐秒的 stalled_ticks 加起来不等于总数");
            delete(h, run);
        }).thenSucceed();
    }

    /**
     * 守护计数分两类（11-09）：玩家实体 tick（ServerPlayer.tick / doTick）里的同步加载算 count，必须是 0；
     * 别的（原版流体 tick、chunk 变成可 tick 时的后处理读到还没做完的邻居）算 world，只记录。
     * 这里让一个模拟玩家站在没生成的地方，手动调一次 doTick：它查脚下流体，同步生成，count 至少 1。
     */
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 12000)
    public void playerTickSyncLoadIsCountedSeparately(GameTestHelper h) {
        ServerPlayer flyer = player(h);
        ServerPlayer stray = h.makeMockServerPlayerInLevel();
        // 远处没生成过的地方；不调 move，ChunkMap 不给它加票据，脚下的 chunk 只能在 doTick 里同步生成
        stray.teleportTo(h.getLevel(), flyer.getX() + 30_000 + (UUID.randomUUID().hashCode() & 4095) * 16, 120, flyer.getZ(), 0, 0);
        String run = newRun("sync");
        check(run(h, "bench fly start " + flyer.getStringUUID() + " 0.001 600 " + run) == 1, "fly start 失败");
        stray.doTick();
        check(run(h, "bench fly stop " + run) == 1, "fly stop 失败");
        h.succeedWhen(() -> {
            JsonObject sync = field(done(h, run), "sync_loads").getAsJsonObject();
            check(field(sync, "count").getAsInt() >= 1 && field(sync, "max_ms").getAsDouble() >= 1, "玩家 tick 里的同步加载没被记成 count：" + sync);
            check(field(sync, "world").getAsJsonObject().has("count"), "缺 world：" + sync);
            delete(h, run);
        });
    }

    /** 中途 stop：done.json 标 stopped，飞行不再推玩家。 */
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 12000)
    public void stopEndsFlightEarly(GameTestHelper h) {
        ServerPlayer p = player(h);
        String run = newRun("stop");
        check(run(h, "bench fly start " + p.getStringUUID() + " 4.3 60 " + run) == 1, "fly start 失败");
        check(run(h, "bench fly start " + p.getStringUUID() + " 4.3 60 " + run) == 0, "同名 run 不能再起");
        h.runAfterDelay(30, () -> check(run(h, "bench fly stop " + run) == 1, "fly stop 失败"));
        h.succeedWhen(() -> {
            JsonObject done = done(h, run);
            check(done.get("stopped").getAsBoolean(), "应标 stopped：" + done);
            check(done.get("end_ms").getAsLong() < 60_000, "应提前结束：" + done);
            check(run(h, "bench fly stop " + run) == 0, "已结束的 run 不能再 stop");
            delete(h, run);
        });
    }

    /** /bench fly ready：返回值是视野里还没就绪的 chunk 数（Python 起飞前等它变 0）。 */
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 12000)
    public void readyCountsDownToZero(GameTestHelper h) {
        ServerPlayer p = player(h);
        h.succeedWhen(() -> {
            int notReady = run(h, "bench fly ready " + p.getStringUUID());
            check(notReady == 0, "初始视野还没全部就绪：" + notReady + " 个，按圈（切比雪夫距离）：" + notReadyByRing(h, p));
        });
    }
}
