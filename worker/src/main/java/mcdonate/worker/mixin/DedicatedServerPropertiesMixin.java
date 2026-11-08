package mcdonate.worker.mixin;

import mcdonate.worker.WorkerSeed;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 建世界前把 level-seed 换成 coordinator 给的（第 5 步 3.1）。
 * 1.21.1 源码：构造函数里 level-seed 读出来后交给 WorldOptions.parseSeed。
 * 没配 coordinator（mcdonate.coordinator）时原样返回。
 */
@Mixin(DedicatedServerProperties.class)
abstract class DedicatedServerPropertiesMixin {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/WorldOptions;parseSeed(Ljava/lang/String;)Ljava/util/OptionalLong;"))
    private String mcdonate$seedFromCoordinator(String original) {
        return WorkerSeed.levelSeed(original);
    }
}
