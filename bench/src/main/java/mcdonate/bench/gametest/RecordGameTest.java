package mcdonate.bench.gametest;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * 跟踪真人玩家（第 10 步 7.2）：/bench record 只记录、不移动玩家，文件和飞行一样（地图回放、layer2.compose、面板都能直接用）。
 */
public class RecordGameTest implements FabricGameTest {

    /** 记录期间不改玩家位置；来源事件进得来；stop 以后 done.json 是 record 模式、没有速度和理想距离。 */
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 12000)
    public void recordDoesNotMovePlayerAndWritesFlightFiles(GameTestHelper h) {
        ServerPlayer p = FlightGameTest.player(h);
        Vec3 before = p.position();
        String run = FlightGameTest.newRun("rec");
        FlightGameTest.check(FlightGameTest.run(h, "bench record start " + p.getStringUUID() + " " + run) == 1, "record start 失败");
        // 强制生成一个新 chunk：来源事件（vanilla）要进 provenance.jsonl。GameTest 共用一个世界：原来只离 45 个 chunk，
        // 可能正好在别的测试的玩家视野或飞行路线上——已经在内存里（没有事件）或已经生成过一部分（事件是 resumed / loaded），
        // 11-09 fish 的 f3 偶发失败。改成离几千个 chunk、随机取，并先确认它不在内存里
        int[] t = freshChunk(h);
        int cx = t[0], cz = t[1];
        h.getLevel().getChunk(cx, cz);
        h.runAfterDelay(30, () -> {
            FlightGameTest.check(p.position().equals(before), "记录期间玩家被挪了：" + before + " → " + p.position());
            FlightGameTest.check(FlightGameTest.run(h, "bench record stop " + run) == 1, "record stop 失败");
            h.succeedWhen(() -> {
                JsonObject done = FlightGameTest.done(h, run);
                FlightGameTest.check("record".equals(FlightGameTest.field(done, "mode").getAsString()), "mode 不是 record：" + done);
                FlightGameTest.check(done.get("speed").isJsonNull() && done.get("ideal_distance").isJsonNull(), "record 不该有速度和理想距离：" + done);
                FlightGameTest.check(!FlightGameTest.field(done, "stopped").getAsBoolean(), "record 正常结束不算 stopped：" + done);
                FlightGameTest.check("command".equals(FlightGameTest.field(done, "stopped_reason").getAsString()), "结束原因：" + done);
                // runAfterDelay(30) 在第 30 个 tick 中间执行，那个 tick 的结束还没算上；留点余量
                FlightGameTest.check(FlightGameTest.field(done, "ticks").getAsLong() >= 25, "tick 数：" + done);
                List<JsonObject> prov = FlightGameTest.lines(h, run, "provenance.jsonl");
                List<String> kinds = prov.stream().filter(e -> e.get("cx").getAsInt() == cx && e.get("cz").getAsInt() == cz)
                        .map(e -> e.get("kind").getAsString()).toList();
                FlightGameTest.check(kinds.contains("vanilla"),
                        "强制生成的 chunk (" + cx + ", " + cz + ") 没有 vanilla 来源事件，这个 chunk 的事件：" + kinds + "，共 " + prov.size() + " 行");
                // 玩家没动：没有进出视野的事件，chunks.jsonl 可以是空的；逐秒行要有（和飞行一样的格式）
                List<JsonObject> secs = FlightGameTest.lines(h, run, "seconds.jsonl");
                FlightGameTest.check(!secs.isEmpty() && secs.size() == FlightGameTest.field(done, "rows").getAsInt()
                        && secs.get(0).has("stalled_ticks") && secs.get(0).has("void_rate"), "逐秒行：" + secs.size() + " " + done);
                FlightGameTest.delete(h, run);
            });
        });
    }

    /**
     * 按 chunk 记各阶段时刻（调度 11-10：导入过的 chunk 到达后还要 2～5 秒才做完，分不清是票准入、读盘还是续做）：stages.jsonl 里
     * 这个新 chunk 有加票（ticket）、读盘完成（read，带存档里的状态）、各状态完成（status），按时间先加票、再读盘、最后 full。
     */
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 12000)
    public void recordWritesPerChunkStages(GameTestHelper h) {
        ServerPlayer p = FlightGameTest.player(h);
        String run = FlightGameTest.newRun("stg");
        FlightGameTest.check(FlightGameTest.run(h, "bench record start " + p.getStringUUID() + " " + run) == 1, "record start 失败");
        int[] t = freshChunk(h);
        int cx = t[0], cz = t[1];
        h.getLevel().getChunk(cx, cz);
        h.runAfterDelay(5, () -> {
            FlightGameTest.check(FlightGameTest.run(h, "bench record stop " + run) == 1, "record stop 失败");
            h.succeedWhen(() -> {
                FlightGameTest.done(h, run);
                List<JsonObject> st = FlightGameTest.lines(h, run, "stages.jsonl").stream()
                        .filter(e -> e.get("cx").getAsInt() == cx && e.get("cz").getAsInt() == cz).toList();
                long ticket = first(st, "ticket", null), read = first(st, "read", null), full = first(st, "status", "minecraft:full");
                FlightGameTest.check(ticket >= 0 && read >= 0 && full >= 0, "这个 chunk 缺阶段：" + st);
                FlightGameTest.check(ticket <= read && read <= full, "阶段顺序不对：ticket " + ticket + " read " + read + " full " + full);
                FlightGameTest.check(st.stream().filter(e -> "read".equals(e.get("ev").getAsString())).allMatch(e -> e.has("disk")),
                        "read 要带存档里的状态：" + st);
                FlightGameTest.check(st.stream().filter(e -> "status".equals(e.get("ev").getAsString())).count() >= 5,
                        "新生成的 chunk 应该经过好几个状态：" + st);
                FlightGameTest.delete(h, run);
            });
        });
    }

    private static long first(List<JsonObject> st, String ev, String status) {
        return st.stream().filter(e -> ev.equals(e.get("ev").getAsString()) && (status == null || status.equals(e.get("status").getAsString())))
                .mapToLong(e -> e.get("t_ms").getAsLong()).min().orElse(-1);
    }

    /** 离测试位置几千个 chunk、随机取一个不在内存里的 chunk（别的测试碰不到；世界每轮 GameTest 都是新的）。 */
    static int[] freshChunk(GameTestHelper h) {
        ChunkPos c = new ChunkPos(h.absolutePos(net.minecraft.core.BlockPos.ZERO));
        java.util.Random rnd = new java.util.Random(UUID.randomUUID().getMostSignificantBits());
        for (int i = 0; i < 16; i++) {
            int x = c.x + 3000 + rnd.nextInt(3000), z = c.z - 3000 - rnd.nextInt(3000);
            if (h.getLevel().getChunkSource().getChunkNow(x, z) == null) {
                return new int[] {x, z};
            }
        }
        throw new AssertionError("找不到不在内存里的 chunk");
    }

    /** 玩家下线（这里是移除 mock 玩家）：自动结束这一轮，stopped_reason = left。 */
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 12000)
    public void recordEndsWhenPlayerLeaves(GameTestHelper h) {
        ServerPlayer p = FlightGameTest.player(h);
        String run = FlightGameTest.newRun("recleft");
        FlightGameTest.check(FlightGameTest.run(h, "bench record start " + p.getStringUUID() + " " + run) == 1, "record start 失败");
        h.runAfterDelay(5, () -> {
            h.getLevel().getServer().getPlayerList().remove(p);
            h.succeedWhen(() -> {
                JsonObject done = FlightGameTest.done(h, run);
                FlightGameTest.check("left".equals(FlightGameTest.field(done, "stopped_reason").getAsString()), "结束原因：" + done);
                FlightGameTest.check(done.get("aborted").isJsonNull(), "玩家下线是正常结束，不算 aborted：" + done);
                FlightGameTest.delete(h, run);
            });
        });
    }
}
