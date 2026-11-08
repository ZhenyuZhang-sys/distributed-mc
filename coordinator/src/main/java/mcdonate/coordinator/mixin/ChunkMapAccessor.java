package mcdonate.coordinator.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 导入时要看 ChunkMap 的几个私有成员。 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    /** 内存里正在用的 chunk（已加载、正在生成）。 */
    @Invoker("getUpdatingChunkIfPresent")
    ChunkHolder mcdonate$getUpdatingChunkIfPresent(long chunkPos);

    /** 已经不用、但还没存盘卸载的 chunk。这时写进去会被卸载时的存盘覆盖。 */
    @Accessor("pendingUnloads")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> mcdonate$pendingUnloads();

    /** 写完要更新 ChunkMap 对这个位置 chunk 类型的缓存。 */
    @Invoker("markPosition")
    byte mcdonate$markPosition(ChunkPos pos, ChunkType type);
}
