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
        ChunkPos c = p.chunkPosition();
        int dz = 40 + Math.floorMod(UUID.randomUUID().hashCode(), 200);
        h.getLevel().getChunk(c.x + 45, c.z + dz);   // 强制生成一个新 chunk：来源事件要进 provenance.jsonl
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
                int cx = c.x + 45, cz = c.z + dz;
                FlightGameTest.check(prov.stream().anyMatch(e -> e.get("cx").getAsInt() == cx && e.get("cz").getAsInt() == cz
                        && "vanilla".equals(e.get("kind").getAsString())), "强制生成的 chunk 没有来源事件");
                // 玩家没动：没有进出视野的事件，chunks.jsonl 可以是空的；逐秒行要有（和飞行一样的格式）
                List<JsonObject> secs = FlightGameTest.lines(h, run, "seconds.jsonl");
                FlightGameTest.check(!secs.isEmpty() && secs.size() == FlightGameTest.field(done, "rows").getAsInt()
                        && secs.get(0).has("stalled_ticks") && secs.get(0).has("void_rate"), "逐秒行：" + secs.size() + " " + done);
                FlightGameTest.delete(h, run);
            });
        });
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
