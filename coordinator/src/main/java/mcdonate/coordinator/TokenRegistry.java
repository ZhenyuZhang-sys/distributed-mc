package mcdonate.coordinator;

import mcdonate.common.Config;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 第 9 步：玩家 worker 的一次性 token（docs/step9-plan.md 第 3 节）。纯逻辑。
 *
 * <ul>
 *   <li>玩家进服时发一个（128 位随机）；同一个玩家再进服，旧的作废。
 *   <li>只能用一次；发出后 60 秒过期；玩家退出时作废。
 *   <li>按 token 的 SHA-256 摘要查找，查到后用 MessageDigest.isEqual 做常量时间比较。
 *   <li>不在日志、toString 里出现 token 本身，只用 {@link #redact}。
 * </ul>
 * 已知限制：token 走明文 TCP，抓包的人可以抢先用掉（以后加 TLS）。
 */
final class TokenRegistry {
    static final long TTL_MS = Config.getLong("tokenTtlMs", 60_000);

    private static final class Entry {
        final UUID player;
        final int slot;
        final byte[] token;
        final long issuedAt;
        boolean used;

        Entry(UUID player, int slot, byte[] token, long issuedAt) {
            this.player = player;
            this.slot = slot;
            this.token = token;
            this.issuedAt = issuedAt;
        }

        @Override
        public String toString() {
            return "Entry[player=" + player + ", used=" + used + "]"; // 不打 token
        }
    }

    private final LongSupplier clock;
    private final Random random;
    private final Map<String, Entry> byDigest = new HashMap<>();
    private record Slot(UUID player, int worker) {}
    private final Map<Slot, String> digestOfPlayer = new HashMap<>();

    TokenRegistry(LongSupplier clock) {
        this(clock, new SecureRandom());
    }

    TokenRegistry(LongSupplier clock, Random random) {
        this.clock = clock;
        this.random = random;
    }

    /** 单 worker 兼容入口：更新玩家的第0槽位。 */
    synchronized String issue(UUID player) {
        return issue(player, 0);
    }

    /** 只更新这个槽位。同玩家的其他 worker 邀请照常有效。 */
    synchronized String issue(UUID player, int worker) {
        if (worker < 0 || worker >= mcdonate.common.net.WorkerInvite.MAX_WORKERS) {
            throw new IllegalArgumentException("worker slot 越界");
        }
        Slot slot = new Slot(player, worker);
        String old = digestOfPlayer.remove(slot);
        if (old != null) byDigest.remove(old);
        byte[] raw = new byte[16];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        String d = digest(bytes);
        byDigest.put(d, new Entry(player, worker, bytes, clock.getAsLong()));
        digestOfPlayer.put(slot, d);
        return token;
    }

    /** 用掉一个 token：有效（认识、没用过、没过期）就返回它的玩家，否则空。 */
    synchronized Optional<UUID> consume(String token) {
        if (token == null || token.isEmpty()) {
            return Optional.empty();
        }
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        String d = digest(bytes);
        Entry e = byDigest.get(d);
        if (e == null || !MessageDigest.isEqual(e.token, bytes) || e.used) {
            return Optional.empty();
        }
        if (clock.getAsLong() - e.issuedAt > TTL_MS) {
            byDigest.remove(d);
            digestOfPlayer.remove(new Slot(e.player, e.slot), d);
            return Optional.empty();
        }
        e.used = true;
        return Optional.of(e.player);
    }

    /** 玩家退出或重新进服：作废他的 token。 */
    synchronized void revoke(UUID player) {
        digestOfPlayer.entrySet().removeIf(e -> {
            if (!e.getKey().player().equals(player)) return false;
            byDigest.remove(e.getValue());
            return true;
        });
    }

    /** 写日志用：只露前 4 个字符。 */
    static String redact(String token) {
        if (token == null || token.length() <= 4) {
            return "****";
        }
        return token.substring(0, 4) + "****";
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
