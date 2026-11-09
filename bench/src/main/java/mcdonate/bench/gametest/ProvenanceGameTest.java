package mcdonate.bench.gametest;

import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import mcdonate.common.Provenance;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * 第 4 步 4.3：来源事件（docs/step4-plan.md 3.3）。飞行期间 bench 把所有来源事件写进 provenance.jsonl。
 * 用 FlightGameTest 的工具函数（命令、读文件）。
 */
public class ProvenanceGameTest implements FabricGameTest {

    static List<JsonObject> provenance(GameTestHelper h, String run) {
        return FlightGameTest.lines(h, run, "provenance.jsonl");
    }

    /** 强制生成几个没生成过的 chunk：每个在 provenance.jsonl 里正好一条 vanilla；原版事件数等于计数的增量。 */
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 12000)
    public void generatedChunksAreVanilla(GameTestHelper h) {
        ServerPlayer p = FlightGameTest.player(h);
        String run = FlightGameTest.newRun("prov");
        FlightGameTest.check(FlightGameTest.run(h, "bench fly start " + p.getStringUUID() + " 0.001 600 " + run) == 1, "fly start 失败");
        ChunkPos c = p.chunkPosition();
        // 离视野 40 个 chunk，和别的测试也错开（每个测试结构相隔不到 1 个 chunk，取 z 方向随机偏移）
        int dz = 40 + Math.floorMod(UUID.randomUUID().hashCode(), 200);
        for (int i = 0; i < 3; i++) h.getLevel().getChunk(c.x + 40 + i, c.z + dz);
        FlightGameTest.check(FlightGameTest.run(h, "bench fly stop " + run) == 1, "fly stop 失败");
        h.succeedWhen(() -> {
            JsonObject done = FlightGameTest.done(h, run);
            List<JsonObject> events = provenance(h, run);
            for (int i = 0; i < 3; i++) {
                int cx = c.x + 40 + i, cz = c.z + dz;
                long n = events.stream().filter(e -> e.get("cx").getAsInt() == cx && e.get("cz").getAsInt() == cz).count();
                long v = events.stream().filter(e -> e.get("cx").getAsInt() == cx && e.get("cz").getAsInt() == cz
                        && e.get("kind").getAsString().equals("vanilla") && e.get("donor").getAsInt() == -1).count();
                FlightGameTest.check(n == 1 && v == 1, "强制生成的 chunk " + cx + "," + cz + " 应正好一条 vanilla，实际 " + n + " 条、vanilla " + v);
            }
            JsonObject counts = done.getAsJsonObject("provenance"), delta = done.getAsJsonObject("counters");
            long vanilla = events.stream().filter(e -> e.get("kind").getAsString().equals("vanilla")).count();
            FlightGameTest.check(counts.get("vanilla").getAsLong() == vanilla, "done.json 的计数和文件不符：" + counts);
            FlightGameTest.check(vanilla == delta.get("full_generated").getAsLong() + delta.get("full_resumed").getAsLong(),
                    "原版事件 " + vanilla + " 不等于计数增量 " + delta);
            for (JsonObject e : events) FlightGameTest.check(e.has("t_ms"), "事件缺 t_ms：" + e);
            FlightGameTest.delete(h, run);
        });
    }

    /** 跨 mod：common 只加载一份；coordinator 一侧拿到的 Provenance 类和 bench 的是同一个；从那边调 report，bench 收得到。/bench mark 也走 Provenance。 */
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 12000)
    public void oneCommonAcrossModsAndMarkGoesThroughProvenance(GameTestHelper h) throws Exception {
        long commons = FabricLoader.getInstance().getAllMods().stream()
                .filter(m -> m.getMetadata().getId().equals("mcdonate-common")).count();
        FlightGameTest.check(commons == 1, "mcdonate-common 应只加载一份，实际 " + commons);
        Class<?> fromCoordinator = Class.forName("mcdonate.coordinator.Coordinator").getClassLoader().loadClass("mcdonate.common.Provenance");
        FlightGameTest.check(fromCoordinator == Provenance.class, "coordinator 和 bench 看到的 Provenance 不是同一个类");

        ServerPlayer p = FlightGameTest.player(h);
        String run = FlightGameTest.newRun("mark");
        FlightGameTest.check(FlightGameTest.run(h, "bench fly start " + p.getStringUUID() + " 0.001 600 " + run) == 1, "fly start 失败");
        Class<?> kind = Class.forName("mcdonate.common.Provenance$Kind", true, fromCoordinator.getClassLoader());
        Method report = fromCoordinator.getMethod("report", int.class, int.class, kind, int.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object donor = Enum.valueOf((Class) kind, "DONOR");
        report.invoke(null, 900001, -900001, donor, 7);
        FlightGameTest.check(FlightGameTest.run(h, "bench mark 900002 -900002 donor 3") == 1, "bench mark donor 失败");
        FlightGameTest.check(FlightGameTest.run(h, "bench mark 900003 -900003 rejected") == 1, "bench mark rejected 失败");
        FlightGameTest.check(FlightGameTest.run(h, "bench fly stop " + run) == 1, "fly stop 失败");
        h.succeedWhen(() -> {
            FlightGameTest.done(h, run);
            List<JsonObject> events = provenance(h, run);
            FlightGameTest.check(has(events, 900001, -900001, "donor", 7), "coordinator 一侧 report 的 DONOR 没写进文件：" + events.size());
            FlightGameTest.check(has(events, 900002, -900002, "donor", 3), "bench mark donor 没写进文件");
            FlightGameTest.check(has(events, 900003, -900003, "rejected", -1), "bench mark rejected 没写进文件");
            FlightGameTest.delete(h, run);
        });
    }

    static boolean has(List<JsonObject> events, int cx, int cz, String kind, int donor) {
        return events.stream().anyMatch(e -> e.get("cx").getAsInt() == cx && e.get("cz").getAsInt() == cz
                && e.get("kind").getAsString().equals(kind) && e.get("donor").getAsInt() == donor);
    }
}
