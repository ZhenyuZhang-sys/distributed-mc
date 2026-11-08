package mcdonate.bench.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import mcdonate.bench.Stages;
import mcdonate.bench.Stages.Stage;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 光照线程的实际工作：每次 runUpdate 处理一批光照任务，按批次计数。
 * 包括生成时的光照，也包括方块变化引起的光照更新；只跑 Chunky 时基本都是前者。
 */
@Mixin(ThreadedLevelLightEngine.class)
abstract class ThreadedLevelLightEngineMixin {

    @WrapMethod(method = "runUpdate")
    private void mcdonate$timeLightEngine(Operation<Void> original) {
        Stages.time(Stage.LIGHT_ENGINE, true, () -> original.call());
    }
}
