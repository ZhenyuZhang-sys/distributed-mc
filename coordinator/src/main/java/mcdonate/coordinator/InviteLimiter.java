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
    private final Map<UUID, Long> last = new ConcurrentHashMap<>();

    InviteLimiter(long minMs) {
        this.minMs = minMs;
    }

    boolean allow(UUID player, long now) {
        boolean[] ok = {false};
        last.compute(player, (k, prev) -> {
            if (prev == null || now - prev >= minMs) {
                ok[0] = true;
                return now;
            }
            return prev;
        });
        return ok[0];
    }

    void forget(UUID player) {
        last.remove(player);
    }
}
