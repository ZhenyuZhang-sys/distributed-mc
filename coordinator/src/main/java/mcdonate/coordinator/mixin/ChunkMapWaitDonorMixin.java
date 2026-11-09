package mcdonate.coordinator.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import mcdonate.coordinator.WaitDonor;
import net.minecraft.Util;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 7.6 等 donor：在原版第一次读一个 chunk 之前插等待（docs/step7-waitdonor-notes.md 第 2 节）。
 * 等待是异步的 future，不卡任何线程；等完在后台线程上调原来的 scheduleChunkLoad（它先异步读存档，
 * 再自己回到主线程，和原来一样）。
 */
@Mixin(ChunkMap.class)
abstract class ChunkMapWaitDonorMixin {
    @Shadow
    @Final
    ServerLevel level;

    @WrapMethod(method = "scheduleChunkLoad")
    private CompletableFuture<ChunkAccess> mcdonate$waitDonor(ChunkPos pos, Operation<CompletableFuture<ChunkAccess>> original) {
        if (level.dimension() != Level.OVERWORLD) {
            return original.call(pos);
        }
        long key = pos.toLong();
        CompletableFuture<Void> gate = WaitDonor.gate(key);
        if (gate == null) {
            if (WaitDonor.on()) {
                WaitDonor.started(key);
            }
            return original.call(pos);
        }
        return gate.thenApply(v -> Boolean.FALSE)
                .completeOnTimeout(Boolean.TRUE, WaitDonor.MAX_WAIT_MS, TimeUnit.MILLISECONDS)
                .thenComposeAsync(timedOut -> {
                    WaitDonor.started(key);
                    WaitDonor.leave(key, timedOut);
                    return original.call(pos);
                }, Util.backgroundExecutor());
    }
}
