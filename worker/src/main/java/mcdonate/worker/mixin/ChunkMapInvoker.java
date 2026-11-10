package mcdonate.worker.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 拿 chunk 的 holder（看它有没有进行中的生成任务，TileExporter 导出前等它们结束）。 */
@Mixin(ChunkMap.class)
public interface ChunkMapInvoker {
    @Invoker("getUpdatingChunkIfPresent")
    ChunkHolder mcdonate$holder(long pos);
}
