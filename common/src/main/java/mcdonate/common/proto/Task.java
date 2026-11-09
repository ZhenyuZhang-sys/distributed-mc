package mcdonate.common.proto;

import java.util.Locale;
import java.util.Objects;

/**
 * coordinator → worker：生成 tile (tx, tz)。
 * kind（第 8 步加）：GENERATE 是普通的生成任务，结果走导入；RECOMPUTE 是派给受信任 worker 的重算，
 * 结果交给验证器、不导入。worker 两种都照样生成，分流在 coordinator。旧消息没有 kind，按 GENERATE 处理。
 */
public record Task(long taskId, int tx, int tz, long leaseMs, Kind kind) implements Message {
    public enum Kind {
        GENERATE, RECOMPUTE;

        /** NBT 里的写法：小写。 */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** 只认小写的 generate / recompute。 */
        public static Kind fromWire(String s) {
            for (Kind k : values()) {
                if (k.wire().equals(s)) {
                    return k;
                }
            }
            throw new IllegalArgumentException("未知任务类型：" + s);
        }
    }

    public Task {
        Objects.requireNonNull(kind, "kind");
    }

    /** 普通生成任务。 */
    public Task(long taskId, int tx, int tz, long leaseMs) {
        this(taskId, tx, tz, leaseMs, Kind.GENERATE);
    }
}
