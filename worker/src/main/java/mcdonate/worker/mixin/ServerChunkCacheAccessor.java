package mcdonate.worker.mixin;

import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 给 tile 的 chunk 加、去自己的票（TileExporter.prefetch / release）。 */
@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheAccessor {
    @Accessor("distanceManager")
    DistanceManager mcdonate$distanceManager();
}
