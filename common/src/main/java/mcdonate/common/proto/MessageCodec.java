package mcdonate.common.proto;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** 消息和 NBT 互转。NBT 里用 type 字段（小写）区分；多出来的字段忽略。 */
public final class MessageCodec {
    private MessageCodec() {}

    public static CompoundTag toTag(Message m) {
        CompoundTag t = new CompoundTag();
        switch (m) {
            case Hello h -> {
                t.putString("type", "hello");
                t.putInt("protocol", h.protocol());
                t.putString("name", h.name());
                t.putString("token", h.token());
                t.putString("worldId", h.worldId());
            }
            case Welcome w -> {
                t.putString("type", "welcome");
                t.putInt("protocol", w.protocol());
                t.putInt("workerId", w.workerId());
                t.putLong("seed", w.seed());
                t.putInt("dataVersion", w.dataVersion());
            }
            case Task k -> {
                t.putString("type", "task");
                t.putLong("taskId", k.taskId());
                t.putInt("tx", k.tx());
                t.putInt("tz", k.tz());
                t.putLong("leaseMs", k.leaseMs());
                t.putString("kind", k.kind().wire());
            }
            case Result r -> {
                t.putString("type", "result");
                t.putLong("taskId", r.taskId());
                t.putBoolean("ok", r.ok());
                t.putString("error", r.error());
                t.put("tile", r.tile());
            }
            case Cancel c -> {
                t.putString("type", "cancel");
                t.putLong("taskId", c.taskId());
            }
            case Heartbeat b -> {
                t.putString("type", "heartbeat");
                t.putLong("sentAtMs", b.sentAtMs());
                t.putLong("taskId", b.taskId());
                t.putBoolean("paused", b.paused());
                t.putBoolean("boost", b.boost());
                t.putInt("threads", b.threads());
                t.putInt("cpus", b.cpus());
                t.putInt("genPool", b.genPool());
            }
        }
        return t;
    }

    /** 字段缺失、类型不对、type 未知、内容自相矛盾都抛 ProtocolException。 */
    public static Message fromTag(CompoundTag t) {
        if (t == null) {
            throw new ProtocolException("消息为空");
        }
        String type = str(t, "type");
        try {
            return switch (type) {
                case "hello" -> new Hello(i(t, "protocol"), str(t, "name"), optionalStr(t, "token"), optionalStr(t, "worldId"));
                case "welcome" -> new Welcome(i(t, "protocol"), i(t, "workerId"), l(t, "seed"), i(t, "dataVersion"));
                case "task" -> new Task(l(t, "taskId"), i(t, "tx"), i(t, "tz"), l(t, "leaseMs"),
                        t.contains("kind") ? Task.Kind.fromWire(str(t, "kind")) : Task.Kind.GENERATE);
                case "result" -> new Result(l(t, "taskId"), bool(t, "ok"), str(t, "error"), compound(t, "tile"));
                case "cancel" -> new Cancel(l(t, "taskId"));
                case "heartbeat" -> new Heartbeat(l(t, "sentAtMs"), l(t, "taskId"), t.contains("paused") && bool(t, "paused"),
                        t.contains("boost") && bool(t, "boost"), t.contains("threads") ? i(t, "threads") : 0,
                        t.contains("cpus") ? i(t, "cpus") : 0, t.contains("genPool") ? i(t, "genPool") : 0);
                default -> throw new ProtocolException("未知消息类型：" + type);
            };
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("消息 " + type + " 不合规：" + e.getMessage(), e);
        }
    }

    private static void need(CompoundTag t, String key, int tagType) {
        if (!t.contains(key, tagType)) {
            throw new ProtocolException(t.contains(key) ? "字段类型不对：" + key : "缺字段：" + key);
        }
    }

    private static int i(CompoundTag t, String key) {
        need(t, key, Tag.TAG_INT);
        return t.getInt(key);
    }

    private static long l(CompoundTag t, String key) {
        need(t, key, Tag.TAG_LONG);
        return t.getLong(key);
    }

    private static String str(CompoundTag t, String key) {
        need(t, key, Tag.TAG_STRING);
        return t.getString(key);
    }

    /** 可选字段：缺了按空串（向后兼容旧消息），有但类型不对照样拒绝。 */
    private static String optionalStr(CompoundTag t, String key) {
        return t.contains(key) ? str(t, key) : "";
    }

    private static boolean bool(CompoundTag t, String key) {
        need(t, key, Tag.TAG_BYTE);
        byte b = t.getByte(key);
        if (b != 0 && b != 1) {
            throw new ProtocolException("布尔字段不是 0/1：" + key);
        }
        return b == 1;
    }

    private static CompoundTag compound(CompoundTag t, String key) {
        need(t, key, Tag.TAG_COMPOUND);
        return t.getCompound(key);
    }
}
