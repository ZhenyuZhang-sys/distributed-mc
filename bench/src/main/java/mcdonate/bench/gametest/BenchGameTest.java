package mcdonate.bench.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import mcdonate.bench.TickTimes;

/**
 * 服务器内测试：要调用原版代码的逻辑放在这里。
 * 由 scripts/gametest.sh 在一台专门跑 GameTest 的服务器上运行，结果写成 JUnit XML。
 */
public class BenchGameTest implements FabricGameTest {

    /** 什么都不做，证明框架本身能跑。 */
    @GameTest(template = EMPTY_STRUCTURE)
    public void emptyTest(GameTestHelper helper) {
        helper.succeed();
    }

    /** 放一个方块，断言它在。坐标相对于测试区域。 */
    @GameTest(template = EMPTY_STRUCTURE)
    public void placeBlock(GameTestHelper helper) {
        BlockPos pos = new BlockPos(0, 1, 0);
        helper.setBlock(pos, Blocks.DIAMOND_BLOCK);
        helper.assertBlockPresent(Blocks.DIAMOND_BLOCK, pos);
        helper.succeed();
    }

    /**
     * 演示面板的 /bench tick（第 10 步 7.1）：命令能跑；过 25 个 tick 以后做完的 tick 数变大、最近 20 个都有值。
     * 这段在 tick 里执行，读到的仍是做完的 tick（自己计时，不读原版 tick 里还没写的 tickTimesNanos）。
     */
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 12000)
    public void tickCommandReadsRecentTicks(GameTestHelper helper) {
        FlightGameTest.check(FlightGameTest.run(helper, "bench tick") == 1, "bench tick 失败");
        long start = TickTimes.recent().completed();
        helper.runAfterDelay(25, () -> {
            TickTimes.Summary s = TickTimes.recent();
            FlightGameTest.check(s.completed() >= start + 25, "做完的 tick 数没变大：" + start + " → " + s.completed());
            FlightGameTest.check(s.ticks() == 20 && s.meanMs() > 0 && s.maxMs() >= s.meanMs(), "最近 20 个 tick：" + s);
            helper.succeed();
        });
    }

    /** 真实场景要按用户正式服的视距测（调度 11-09）：/bench viewdistance 改服务器视距（和 server.properties 的 view-distance 一样）。 */
    @GameTest(template = EMPTY_STRUCTURE)
    public void viewDistanceCommand(GameTestHelper helper) {
        net.minecraft.server.players.PlayerList list = helper.getLevel().getServer().getPlayerList();
        int before = list.getViewDistance();
        try {
            FlightGameTest.check(FlightGameTest.run(helper, "bench viewdistance 16") == 16, "bench viewdistance 失败");
            FlightGameTest.check(list.getViewDistance() == 16, "视距没改：" + list.getViewDistance());
            // 超出 2～32：参数范围在命令语法里，原版直接报语法错误（FlightGameTest.run 抛 GameTestAssertException），视距不变
            boolean rejected;
            try {
                rejected = FlightGameTest.run(helper, "bench viewdistance 99") != 16;
            } catch (net.minecraft.gametest.framework.GameTestAssertException e) {
                rejected = true;
            }
            FlightGameTest.check(rejected && list.getViewDistance() == 16, "超出 2～32 应该拒绝");
        } finally {
            list.setViewDistance(before);
        }
        helper.succeed();
    }
}
