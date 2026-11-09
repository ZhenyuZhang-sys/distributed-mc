package mcdonate.bench.mixin;

import mcdonate.bench.SyncLoads;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
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
            // 先不阻塞地查一下：已经到了要的状态的，慢了也不是同步加载（主线程被调度走之类）。
            // 不能用 getChunk(load=false)：holder 在的时候它也会 managedBlock 等。
            ChunkHolder holder = ((ChunkMapAccess) ((ServerChunkCache) (Object) this).chunkMap).mcdonate$visibleHolder(ChunkPos.asLong(x, z));
            SyncLoads.enter(holder != null && holder.getChunkIfPresent(status) != null);
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
