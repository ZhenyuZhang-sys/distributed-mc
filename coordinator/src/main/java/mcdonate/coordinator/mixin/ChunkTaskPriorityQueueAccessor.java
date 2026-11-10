package mcdonate.coordinator.mixin;

import net.minecraft.server.level.ChunkTaskPriorityQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 读回玩家票节流的实际名额（/donor status）。 */
@Mixin(ChunkTaskPriorityQueue.class)
public interface ChunkTaskPriorityQueueAccessor {
    @Accessor("maxTasks")
    int mcdonate$maxTasks();
}
