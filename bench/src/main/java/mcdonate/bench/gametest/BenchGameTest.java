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
}
