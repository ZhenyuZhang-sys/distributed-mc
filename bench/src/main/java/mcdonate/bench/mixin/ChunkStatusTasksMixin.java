package mcdonate.bench.mixin;

import java.util.concurrent.CompletableFuture;
import mcdonate.bench.Counters;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 每个生成阶段都是 ChunkStatusTasks 里的一个静态方法，在入口处计数。只算主世界。 */
@Mixin(ChunkStatusTasks.class)
abstract class ChunkStatusTasksMixin {

    // full（chunk 变成完整状态）的计数和来源事件挪进了 common 的 ChunkStatusTasksFullStatusMixin（overlay 计划 2.1），
    // bench 的 Counters 通过 Generation 注册成 FullStatus 的计数

    @Inject(method = "generateNoise", at = @At("HEAD"))
    private static void mcdonate$countNoise(WorldGenContext context, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (context.level().dimension() == Level.OVERWORLD) {
            Counters.onNoise(chunk.getPos().x, chunk.getPos().z);
        }
    }

    @Inject(method = "generateFeatures", at = @At("HEAD"))
    private static void mcdonate$countFeatures(WorldGenContext context, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (context.level().dimension() == Level.OVERWORLD) {
            Counters.onFeatures(chunk.getPos().x, chunk.getPos().z);
        }
    }
}
