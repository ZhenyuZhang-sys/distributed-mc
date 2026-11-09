package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * worker 编号 → (稳定名字, 显示名)（kexu overlay 计划第 6 节：按 worker 给 chunk 上色，重连后颜色不变）。
 * 稳定名字：玩家 UUID 优先，没有就用 donor 世界编号，再没有用主机名。显示名：玩家名，没有就用主机名。
 */
class WorkerNameTest {
    private static final UUID P = UUID.fromString("4a17be16-bce5-46b2-b538-0c4d4e19f3b2");

    @Test
    void playerWins() {
        assertEquals(new WorkerName("player:" + P, "Steve"), WorkerName.of(P, "Steve", "w-123", "laptop"));
    }

    @Test
    void playerOfflineUsesHostForDisplay() {
        assertEquals(new WorkerName("player:" + P, "laptop"), WorkerName.of(P, null, "w-123", "laptop"));
    }

    @Test
    void dockerWorkerUsesWorld() {
        // 测试里的本地 worker 没有玩家：先用 donor 世界编号（重连后不变），显示主机名
        assertEquals(new WorkerName("world:w-123", "f776deaedcc8"), WorkerName.of(null, null, "w-123", "f776deaedcc8"));
    }

    @Test
    void beforeFirstResultUsesHost() {
        // 还没交过结果时不知道世界编号
        assertEquals(new WorkerName("host:f776deaedcc8", "f776deaedcc8"), WorkerName.of(null, null, null, "f776deaedcc8"));
        assertEquals(new WorkerName("host:", "?"), WorkerName.of(null, null, "", ""));
    }
}
