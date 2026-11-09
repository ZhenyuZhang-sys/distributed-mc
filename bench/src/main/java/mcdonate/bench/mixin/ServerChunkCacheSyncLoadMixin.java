package mcdonate.bench.mixin;

import mcdonate.bench.SyncLoads;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 主线程同步取 chunk 的守护计数（见 SyncLoads）。只看 load=true；不在主线程上的调用不管。 */
@Mixin(ServerChunkCache.class)
abstract class ServerChunkCacheSyncLoadMixin {
    @Shadow
    @Final
    Thread mainThread;

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"))
    private void mcdonate$syncLoadStart(int x, int z, ChunkStatus status, boolean load, CallbackInfoReturnable<ChunkAccess> cir) {
        if (load && Thread.currentThread() == mainThread) {
            SyncLoads.enter();
        }
    }

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("RETURN"))
    private void mcdonate$syncLoadEnd(int x, int z, ChunkStatus status, boolean load, CallbackInfoReturnable<ChunkAccess> cir) {
        if (load && Thread.currentThread() == mainThread) {
            SyncLoads.exit(x, z);
        }
    }
}
