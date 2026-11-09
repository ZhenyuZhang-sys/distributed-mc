package mcdonate.coordinator.mixin;

import mcdonate.coordinator.PlayerTicketThrottle;
import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** 11-09 放宽玩家票节流：只改 DistanceManager 构造里 ticketThrottler 的 maxTasks（原版 4），见 PlayerTicketThrottle。 */
@Mixin(DistanceManager.class)
public abstract class DistanceManagerThrottleMixin {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkTaskPriorityQueueSorter;<init>(Ljava/util/List;Ljava/util/concurrent/Executor;I)V"),
            index = 2)
    private int mcdonate$playerTicketThrottle(int maxTasks) {
        return PlayerTicketThrottle.maxTasks(maxTasks);
    }
}
