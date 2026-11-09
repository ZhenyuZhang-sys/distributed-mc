package mcdonate.coordinator;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第 9 步：玩家再要邀请（mcdonate:invite_request）的频率限制，每个玩家每 minMs 最多一次（配置 inviteRequestMinMs，默认 10 秒）。
 * 被拒的请求不推迟下一次。玩家退出时忘掉。纯逻辑。
 */
final class InviteLimiter {
    private final long minMs;
    private record Slot(UUID player, int worker) {}
    private final Map<Slot, Long> last = new ConcurrentHashMap<>();

    InviteLimiter(long minMs) {
        this.minMs = minMs;
    }

    boolean allow(UUID player, long now) {
        return allow(player, 0, now);
    }

    boolean allow(UUID player, int worker, long now) {
        if (worker < 0 || worker >= mcdonate.common.net.WorkerInvite.MAX_WORKERS) return false;
        boolean[] ok = {false};
        last.compute(new Slot(player, worker), (k, prev) -> {
            if (prev == null || now - prev >= minMs) {
                ok[0] = true;
                return now;
            }
            return prev;
        });
        return ok[0];
    }

    void forget(UUID player) {
        last.keySet().removeIf(s -> s.player().equals(player));
    }
}
