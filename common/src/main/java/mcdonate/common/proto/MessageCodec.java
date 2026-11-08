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
                case "hello" -> new Hello(i(t, "protocol"), str(t, "name"));
                case "welcome" -> new Welcome(i(t, "protocol"), i(t, "workerId"), l(t, "seed"), i(t, "dataVersion"));
                case "task" -> new Task(l(t, "taskId"), i(t, "tx"), i(t, "tz"), l(t, "leaseMs"));
                case "result" -> new Result(l(t, "taskId"), bool(t, "ok"), str(t, "error"), compound(t, "tile"));
                case "cancel" -> new Cancel(l(t, "taskId"));
                case "heartbeat" -> new Heartbeat(l(t, "sentAtMs"), l(t, "taskId"));
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
