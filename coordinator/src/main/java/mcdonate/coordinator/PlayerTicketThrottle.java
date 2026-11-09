package mcdonate.coordinator;

import mcdonate.common.Config;
import mcdonate.coordinator.mixin.ChunkTaskPriorityQueueAccessor;
import mcdonate.coordinator.mixin.ChunkTaskPriorityQueueSorterAccessor;
import mcdonate.coordinator.mixin.DistanceManagerAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 11-09 放宽玩家票节流：原版 DistanceManager 的 ticketThrottler 同时最多 4 张玩家票（PLAYER，31 级）在途，
 * 每张等这个 chunk 能 entity tick（5×5 都 FULL）才放名额；发给客户端要 BLOCK_TICKING（3×3 都 FULL）。
 * 烟花直飞每秒约 44 个新 chunk 进视野，4 个名额约 20～27 个/秒，排队越积越长：donor 早就导入好的 chunk
 * 也要等几秒才拿到票（codex-server：进视距→票够 p50 3.5 秒、p90 5.5 秒，拿到票到 FULL 只要 0.15 秒）。
 *
 * <p>-Dmcdonate.playerTicketThrottle=N 把名额改成 N（mixin DistanceManagerThrottleMixin）。只让视距内本来就要加的票
 * 加得更快，不改票的级别和占用范围，不碰接缝规则。没配时原版的值不动。原版自己的生成也跟着放宽。
 */
public final class PlayerTicketThrottle {
    private PlayerTicketThrottle() {}

    /** 配的值（没配是 null）。 */
    static final String CONFIGURED = Config.get("playerTicketThrottle", null);

    /** mixin 用：原版要用 vanilla，配了就换成配的。 */
    public static int maxTasks(int vanilla) {
        return maxTasks(vanilla, CONFIGURED);
    }

    /** 纯函数：configured 为 null 时返回 vanilla；不是正整数报错。 */
    static int maxTasks(int vanilla, String configured) {
        if (configured == null) {
            return vanilla;
        }
        int n;
        try {
            n = Integer.parseInt(configured.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("配置 playerTicketThrottle 不是整数：" + configured, e);
        }
        if (n < 1) {
            throw new IllegalArgumentException("配置 playerTicketThrottle 至少是 1：" + configured);
        }
        return n;
    }

    /** /donor status 用：配的值（没配时是原版的 4）和每个维度实际的 maxTasks（从 ticketThrottler 里读回来）。 */
    static String json(MinecraftServer server) {
        StringBuilder sb = new StringBuilder("{\"configured\":").append(maxTasks(4)).append(",\"actual\":{");
        boolean first = true;
        for (ServerLevel level : server.getAllLevels()) {
            Object sorter = ((DistanceManagerAccessor) level.getChunkSource().chunkMap.getDistanceManager()).mcdonate$ticketThrottler();
            int max = -1;
            for (Object q : ((ChunkTaskPriorityQueueSorterAccessor) sorter).mcdonate$queues().values()) {
                max = Math.max(max, ((ChunkTaskPriorityQueueAccessor) q).mcdonate$maxTasks());
            }
            sb.append(first ? "" : ",").append('"').append(level.dimension().location()).append("\":").append(max);
            first = false;
        }
        return sb.append("}}").toString();
    }
}
