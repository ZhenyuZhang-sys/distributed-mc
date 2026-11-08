package mcdonate.bench.mixin;

import java.util.concurrent.CompletableFuture;
import mcdonate.bench.Counters;
import mcdonate.bench.FromDisk;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
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

    // 存档里读出的成品包在 ImposterProtoChunk 里；存档里读出的半成品带 FromDisk 标记；
    // 其余是这台服务器从头生成的
    @Inject(method = "full", at = @At("HEAD"))
    private static void mcdonate$countFull(WorldGenContext context, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (context.level().dimension() == Level.OVERWORLD) {
            Counters.FullKind kind = chunk instanceof ImposterProtoChunk ? Counters.FullKind.LOADED
                    : chunk instanceof FromDisk d && d.mcdonate$isFromDisk() ? Counters.FullKind.RESUMED
                    : Counters.FullKind.GENERATED;
            Counters.onFull(kind);
        }
    }

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
