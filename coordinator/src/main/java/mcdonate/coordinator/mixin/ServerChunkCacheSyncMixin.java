package mcdonate.coordinator.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import java.util.concurrent.CompletableFuture;
import mcdonate.coordinator.WaitDonor;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 7.6：主线程同步要 chunk（getChunk 里的 managedBlock）时不能等 donor，否则服务器卡住整个等待时间
 * （7.6a 实测生成假玩家时 rcon 卡 2.9 秒）。真的要阻塞时（要的东西还没好），先打开所有闸门、
 * 期间不建新闸门：这些 chunk 由服务器自己生成，donor 的结果回来时被丢弃，记进 abandoned。
 *
 * <p>MainThreadExecutor 是包内私有类，不能在这里写它的类型，所以不包 managedBlock，而是在它前后各插一段。
 */
@Mixin(ServerChunkCache.class)
abstract class ServerChunkCacheSyncMixin {
    private static final String BLOCK =
            "Lnet/minecraft/server/level/ServerChunkCache$MainThreadExecutor;managedBlock(Ljava/util/function/BooleanSupplier;)V";

    @Unique
    private boolean mcdonate$syncEntered;

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At(value = "INVOKE", target = BLOCK))
    private void mcdonate$beforeBlock(int x, int z, ChunkStatus status, boolean require,
            CallbackInfoReturnable<ChunkAccess> cir, @Local CompletableFuture<ChunkResult<ChunkAccess>> future) {
        if (!future.isDone()) {
            mcdonate$syncEntered = true;
            WaitDonor.enterSync();
        }
    }

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At(value = "INVOKE", target = BLOCK, shift = At.Shift.AFTER))
    private void mcdonate$afterBlock(int x, int z, ChunkStatus status, boolean require, CallbackInfoReturnable<ChunkAccess> cir) {
        if (mcdonate$syncEntered) {
            mcdonate$syncEntered = false;
            WaitDonor.leaveSync();
        }
    }
}
