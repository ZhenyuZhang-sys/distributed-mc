package mcdonate.coordinator.mixin;

import java.util.Map;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 读回玩家票节流的实际名额（/donor status）。 */
@Mixin(ChunkTaskPriorityQueueSorter.class)
public interface ChunkTaskPriorityQueueSorterAccessor {
    @Accessor("queues")
    Map<?, ?> mcdonate$queues();
}
