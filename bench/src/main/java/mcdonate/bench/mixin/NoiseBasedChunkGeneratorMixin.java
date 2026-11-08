package mcdonate.bench.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import mcdonate.bench.Stages;
import mcdonate.bench.Stages.Stage;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;

/** biomes 和 noise 的实际计算在后台线程上，包住真正干活的方法。只加时间，次数由同步入口算。 */
@Mixin(NoiseBasedChunkGenerator.class)
abstract class NoiseBasedChunkGeneratorMixin {

    @WrapMethod(method = "doCreateBiomes")
    private void mcdonate$timeBiomes(Blender blender, RandomState random, StructureManager structureManager,
            ChunkAccess chunk, Operation<Void> original) {
        Stages.time(Stage.BIOMES, false, () -> original.call(blender, random, structureManager, chunk));
    }

    @WrapMethod(method = "doFill")
    private ChunkAccess mcdonate$timeNoise(Blender blender, StructureManager structureManager, RandomState random,
            ChunkAccess chunk, int minCellY, int cellCountY, Operation<ChunkAccess> original) {
        return Stages.time(Stage.NOISE, false,
                () -> original.call(blender, structureManager, random, chunk, minCellY, cellCountY));
    }
}
