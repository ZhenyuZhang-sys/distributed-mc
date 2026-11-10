package mcdonate.bench.mixin;

import mcdonate.bench.ChunkStages;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 按 chunk 记每个状态做完的时刻（ChunkStages）。这里不知道是哪个维度：飞行期间别的维度一般没有在生成的 chunk。 */
@Mixin(GenerationChunkHolder.class)
public abstract class GenerationChunkHolderStagesMixin {
    @Shadow
    public abstract ChunkPos getPos();

    @Inject(method = "completeFuture", at = @At("HEAD"))
    private void mcdonate$status(ChunkStatus status, ChunkAccess chunk, CallbackInfo ci) {
        if (ChunkStages.active()) {
            ChunkPos p = getPos();
            ChunkStages.report(p.x, p.z, ChunkStages.Kind.STATUS, BuiltInRegistries.CHUNK_STATUS.getKey(status).toString());
        }
    }
}
