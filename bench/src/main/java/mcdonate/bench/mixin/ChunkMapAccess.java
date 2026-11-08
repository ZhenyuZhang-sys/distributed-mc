package mcdonate.bench.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 第 4 步"就绪"判定要查 ChunkHolder（docs/step4-plan.md 2.1）；原版这个方法是 protected。只在主线程调用。 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccess {
    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder mcdonate$visibleHolder(long pos);
}
