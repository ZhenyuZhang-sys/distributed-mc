package mcdonate.common;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * chunk 来源事件的静态广播：coordinator 报 DONOR / REJECTED，bench 监听后写进 provenance.jsonl。
 *
 * <p>变成完整状态的三类（bench 的 Generation 报）：VANILLA = 服务器从头生成；RESUMED = 从存档读出来的半成品被服务器接着做完
 * （donor 导入的 tile 就是这种，所以它不算"服务器自己生成了多少"）；LOADED = 读盘的成品。
 *
 * <p>common 以嵌套 Fabric mod（jar-in-jar）打进 bench 和 coordinator，Loader 按 mod id 只加载一份，
 * 所以这里的监听器列表全服只有一份。不许 shadow / relocate common，见 docs/step4-plan.md 3.3。
 * 没有监听器时 report 什么都不做。
 */
public final class Provenance {
    public enum Kind { VANILLA, DONOR, REJECTED, LOADED, RESUMED }

    @FunctionalInterface
    public interface Listener { void on(int cx, int cz, Kind kind, int donorId); }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private Provenance() {}

    public static void addListener(Listener l) {
        LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    /** donorId：原版和读盘填 -1。监听器抛异常不影响其他监听器和调用方。 */
    public static void report(int cx, int cz, Kind kind, int donorId) {
        if (kind == null) {
            throw new IllegalArgumentException("kind 不能为空");
        }
        for (Listener l : LISTENERS) {
            try {
                l.on(cx, cz, kind, donorId);
            } catch (RuntimeException e) {
                // 测量代码出错不能影响写入路径。
            }
        }
    }
}
