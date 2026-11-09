package mcdonate.worker.mixin;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 在主线程上把一个 chunk 的生成排上、不等（公开的 getChunk、getChunkFuture 在主线程上都会原地等它做完）。
 * TileExporter 先把 tile 的 16 个都排上，再一个一个等，世界生成线程池就能同时做（11-09）。
 */
@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheInvoker {
    @Invoker("getChunkFutureMainThread")
    CompletableFuture<ChunkResult<ChunkAccess>> mcdonate$schedule(int x, int z, ChunkStatus status, boolean load);
}
