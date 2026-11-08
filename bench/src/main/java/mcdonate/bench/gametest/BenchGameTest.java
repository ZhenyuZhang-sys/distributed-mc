package mcdonate.bench.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

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
}
