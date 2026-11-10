package mcdonate.bench.mixin;

import java.util.concurrent.CompletableFuture;
import mcdonate.bench.ChunkStages;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 按 chunk 记加票和读盘完成的时刻（ChunkStages），只记主世界。 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapStagesMixin {
    @Shadow @Final ServerLevel level;

    @Inject(method = "updateChunkScheduling", at = @At("HEAD"))
    private void mcdonate$ticket(long pos, int newLevel, @Nullable ChunkHolder holder, int oldLevel, CallbackInfoReturnable<ChunkHolder> cir) {
        if (ChunkStages.active() && level.dimension() == Level.OVERWORLD && !ChunkLevel.isLoaded(oldLevel) && ChunkLevel.isLoaded(newLevel)) {
            ChunkStages.report(ChunkPos.getX(pos), ChunkPos.getZ(pos), ChunkStages.Kind.TICKET, null);
        }
    }

    @Inject(method = "scheduleChunkLoad", at = @At("RETURN"))
    private void mcdonate$read(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!ChunkStages.active() || level.dimension() != Level.OVERWORLD) {
            return;
        }
        cir.getReturnValue().whenComplete((chunk, err) -> {
            if (chunk != null) {
                ChunkStages.report(pos.x, pos.z, ChunkStages.Kind.READ, BuiltInRegistries.CHUNK_STATUS.getKey(chunk.getPersistedStatus()).toString());
            }
        });
    }
}
