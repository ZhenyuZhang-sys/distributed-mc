package mcdonate.coordinator.mixin;

import mcdonate.coordinator.DemandScanner;
import mcdonate.coordinator.Ownership;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 服务器自己存主世界的 chunk 时，清掉它的 donor 归属（存盘说明服务器加载过、可能改过）。 */
@Mixin(ChunkMap.class)
abstract class ChunkMapSaveMixin {
    @Shadow
    @Final
    ServerLevel level;

    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At("HEAD"))
    private void mcdonate$forgetOwner(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        if (level.dimension() == Level.OVERWORLD) {
            Ownership.forget(chunk.getPos().toLong());
            // 存档里的状态可能变了，找需求时重新查
            DemandScanner.diskUnknown(chunk.getPos().toLong());
        }
    }
}
