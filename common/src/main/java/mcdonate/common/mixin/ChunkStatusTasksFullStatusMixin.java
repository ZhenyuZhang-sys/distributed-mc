package mcdonate.common.mixin;

import java.util.concurrent.CompletableFuture;
import mcdonate.common.gen.FromDisk;
import mcdonate.common.gen.FullStatus;
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

/**
 * chunk 变成完整状态（docs/overlay-plan.md 2.1，原来在 bench 的 ChunkStatusTasksMixin）：只算主世界。
 * 存档里读出的成品包在 ImposterProtoChunk 里；存档里读出的半成品带 FromDisk 标记；其余是这台服务器从头生成的。
 * coordinator 的 ChunkStatusTasksFullMixin（首次加载钩子）也挂在这个方法开头，两个注入互不影响。
 */
@Mixin(ChunkStatusTasks.class)
abstract class ChunkStatusTasksFullStatusMixin {
    @Inject(method = "full", at = @At("HEAD"))
    private static void mcdonate$fullStatus(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (context.level().dimension() == Level.OVERWORLD) {
            FullStatus.Kind kind = chunk instanceof ImposterProtoChunk ? FullStatus.Kind.LOADED
                    : chunk instanceof FromDisk d && d.mcdonate$isFromDisk() ? FullStatus.Kind.RESUMED
                    : FullStatus.Kind.GENERATED;
            FullStatus.onFull(kind, chunk.getPos().x, chunk.getPos().z);
        }
    }
}
