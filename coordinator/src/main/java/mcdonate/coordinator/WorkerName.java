package mcdonate.coordinator;

import java.util.UUID;

/**
 * 一个 worker 的稳定名字和显示名（11-09 kexu overlay：按 worker 给 chunk 上色，重连后颜色不变）。
 * 稳定名字：玩家 UUID 优先（"player:<uuid>"），没有就用 donor 世界编号（"world:<id>"），再没有用主机名（"host:<名字>"）。
 * 显示名：玩家名，没有就用主机名，都没有是 "?"。纯逻辑。
 */
public record WorkerName(String stable, String display) {
    static WorkerName of(UUID player, String playerName, String world, String host) {
        String h = host == null ? "" : host;
        String stable = player != null ? "player:" + player
                : world != null && !world.isEmpty() ? "world:" + world : "host:" + h;
        String display = playerName != null && !playerName.isEmpty() ? playerName : h.isEmpty() ? "?" : h;
        return new WorkerName(stable, display);
    }
}
