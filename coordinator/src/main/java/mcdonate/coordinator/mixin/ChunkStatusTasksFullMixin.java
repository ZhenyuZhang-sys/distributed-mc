package mcdonate.coordinator.mixin;

import java.util.concurrent.CompletableFuture;
import mcdonate.coordinator.Ownership;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.server.level.GenerationChunkHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 导入的 chunk 第一次在服务器上变成成品（玩家能看到了）：标成"服务器加载过"（7.7 账本，第 8 步回滚用）。
 *
 * <p>挂在 ChunkStatusTasks.full 开头，两条路都覆盖：存档里读到的成品（ImposterProtoChunk），
 * 和存档里读到半成品、服务器接着做完的（导入的 chunk 停在 initialize_light，走的是这条）。
 * 原来只挂在存盘上：没改动的 chunk 加载以后可能不存盘，会被当成没加载过（kexu 11-09 核对）。存盘那个钩子保留。
 */
@Mixin(ChunkStatusTasks.class)
abstract class ChunkStatusTasksFullMixin {
    @Inject(method = "full", at = @At("HEAD"))
    private static void mcdonate$firstLoad(WorldGenContext ctx, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (ctx.level().dimension() == Level.OVERWORLD) {
            Ownership.LEDGER.serverSaved(chunk.getPos().toLong());
        }
    }
}
