/*
 * 临时实现：kexu 的 mcdonate.common.proto 合进 main 之前，fish 先用它把网络接起来。
 * 接口和 docs/step5-plan.md 第 1 节一致；换成正式版时删掉整个包，改 import 即可。
 */
package mcdonate.common.tempproto;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** 消息和 NBT 互转。NBT 里用 type 区分。 */
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

    public static Message fromTag(CompoundTag t) {
        String type = t.getString("type");
        return switch (type) {
            case "hello" -> new Hello(i(t, "protocol"), s(t, "name"));
            case "welcome" -> new Welcome(i(t, "protocol"), i(t, "workerId"), l(t, "seed"), i(t, "dataVersion"));
            case "task" -> new Task(l(t, "taskId"), i(t, "tx"), i(t, "tz"), l(t, "leaseMs"));
            case "result" -> {
                need(t, "ok", Tag.TAG_BYTE);
                need(t, "tile", Tag.TAG_COMPOUND);
                yield new Result(l(t, "taskId"), t.getBoolean("ok"), s(t, "error"), t.getCompound("tile"));
            }
            case "cancel" -> new Cancel(l(t, "taskId"));
            case "heartbeat" -> new Heartbeat(l(t, "sentAtMs"), l(t, "taskId"));
            default -> throw new ProtocolException("未知消息类型：" + type);
        };
    }

    private static void need(CompoundTag t, String key, int type) {
        if (!t.contains(key, type)) {
            throw new ProtocolException("缺字段 " + key);
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

    private static String s(CompoundTag t, String key) {
        need(t, key, Tag.TAG_STRING);
        return t.getString(key);
    }
}
