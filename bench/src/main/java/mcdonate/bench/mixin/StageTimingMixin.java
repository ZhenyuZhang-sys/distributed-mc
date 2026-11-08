package mcdonate.bench.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.CompletableFuture;
import mcdonate.bench.Stages;
import mcdonate.bench.Stages.Stage;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 每个生成阶段在 ChunkStatusTasks 里是一个静态方法，包住整个方法计时，每次调用算一次。
 *
 * <p>biomes、noise、light 系列在这里只是把活交给别的线程，这里量到的是同步的那一小段；
 * 后台真正干活的部分见 {@link NoiseBasedChunkGeneratorMixin} 和 {@link ThreadedLevelLightEngineMixin}。
 * full 的主要工作在主线程的回调里，没有单独计。
 */
@Mixin(ChunkStatusTasks.class)
abstract class StageTimingMixin {

    @WrapMethod(method = "generateStructureStarts")
    private static CompletableFuture<ChunkAccess> mcdonate$timeStructureStarts(WorldGenContext context, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original) {
        return Stages.time(Stage.STRUCTURE_STARTS, true, () -> original.call(context, step, cache, chunk));
    }

    @WrapMethod(method = "generateStructureReferences")
    private static CompletableFuture<ChunkAccess> mcdonate$timeStructureReferences(WorldGenContext context, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original) {
        return Stages.time(Stage.STRUCTURE_REFERENCES, true, () -> original.call(context, step, cache, chunk));
    }

    @WrapMethod(method = "generateBiomes")
    private static CompletableFuture<ChunkAccess> mcdonate$timeBiomes(WorldGenContext context, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original) {
        return Stages.time(Stage.BIOMES, true, () -> original.call(context, step, cache, chunk));
    }

    @WrapMethod(method = "generateNoise")
    private static CompletableFuture<ChunkAccess> mcdonate$timeNoise(WorldGenContext context, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original) {
        return Stages.time(Stage.NOISE, true, () -> original.call(context, step, cache, chunk));
    }

    @WrapMethod(method = "generateSurface")
    private static CompletableFuture<ChunkAccess> mcdonate$timeSurface(WorldGenContext context, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original) {
        return Stages.time(Stage.SURFACE, true, () -> original.call(context, step, cache, chunk));
    }

    @WrapMethod(method = "generateCarvers")
    private static CompletableFuture<ChunkAccess> mcdonate$timeCarvers(WorldGenContext context, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original) {
        return Stages.time(Stage.CARVERS, true, () -> original.call(context, step, cache, chunk));
    }

    @WrapMethod(method = "generateFeatures")
    private static CompletableFuture<ChunkAccess> mcdonate$timeFeatures(WorldGenContext context, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original) {
        return Stages.time(Stage.FEATURES, true, () -> original.call(context, step, cache, chunk));
    }

    @WrapMethod(method = "initializeLight")
    private static CompletableFuture<ChunkAccess> mcdonate$timeInitializeLight(WorldGenContext context, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original) {
        return Stages.time(Stage.INITIALIZE_LIGHT, true, () -> original.call(context, step, cache, chunk));
    }

    @WrapMethod(method = "light")
    private static CompletableFuture<ChunkAccess> mcdonate$timeLight(WorldGenContext context, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original) {
        return Stages.time(Stage.LIGHT, true, () -> original.call(context, step, cache, chunk));
    }

    @WrapMethod(method = "generateSpawn")
    private static CompletableFuture<ChunkAccess> mcdonate$timeSpawn(WorldGenContext context, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original) {
        return Stages.time(Stage.SPAWN, true, () -> original.call(context, step, cache, chunk));
    }

    @WrapMethod(method = "full")
    private static CompletableFuture<ChunkAccess> mcdonate$timeFull(WorldGenContext context, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original) {
        return Stages.time(Stage.FULL, true, () -> original.call(context, step, cache, chunk));
    }
}
