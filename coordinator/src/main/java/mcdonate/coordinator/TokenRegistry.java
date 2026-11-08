package mcdonate.coordinator;

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
    static final long TTL_MS = 60_000;

    private static final class Entry {
        final UUID player;
        final byte[] token;
        final long issuedAt;
        boolean used;

        Entry(UUID player, byte[] token, long issuedAt) {
            this.player = player;
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
    private final Map<UUID, String> digestOfPlayer = new HashMap<>();

    TokenRegistry(LongSupplier clock) {
        this(clock, new SecureRandom());
    }

    TokenRegistry(LongSupplier clock, Random random) {
        this.clock = clock;
        this.random = random;
    }

    /** 发一个新 token；这个玩家之前的作废。 */
    synchronized String issue(UUID player) {
        revoke(player);
        byte[] raw = new byte[16];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        String d = digest(bytes);
        byDigest.put(d, new Entry(player, bytes, clock.getAsLong()));
        digestOfPlayer.put(player, d);
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
            digestOfPlayer.remove(e.player, d);
            return Optional.empty();
        }
        e.used = true;
        return Optional.of(e.player);
    }

    /** 玩家退出或重新进服：作废他的 token。 */
    synchronized void revoke(UUID player) {
        String d = digestOfPlayer.remove(player);
        if (d != null) {
            byDigest.remove(d);
        }
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
