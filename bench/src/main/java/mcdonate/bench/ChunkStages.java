package mcdonate.bench;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 按 chunk 记各阶段的时刻（调度 11-10：导入过的 chunk 玩家到了还要 2～5 秒才做完，要分清是票准入、读盘还是续做）。
 * mixin 在任何线程上报，飞行记录器监听后写进 stages.jsonl。没有监听器时什么都不做。
 *
 * <ul>
 *   <li>ticket：这个 chunk 的票等级第一次变成"要加载"（ChunkMap.updateChunkScheduling；玩家票节流放行以后才到这里）。
 *   <li>ticket_full：票等级第一次到能做成 FULL 的等级（ChunkLevel 33 及以下）。外圈的 chunk 先进加载等级、只做到低状态，
 *       要等玩家靠近、等级到 33 才会续做到 FULL。
 *   <li>read：读盘完成（ChunkMap.scheduleChunkLoad 的结果），disk 是存档里的状态（没有存档是 minecraft:empty）。
 *   <li>status：某个状态做完（GenerationChunkHolder.completeFuture），读盘出来的状态也会在这里报一次。
 * </ul>
 */
public final class ChunkStages {
    public enum Kind { TICKET, TICKET_FULL, READ, STATUS }

    @FunctionalInterface
    public interface Listener { void on(int cx, int cz, Kind kind, String status); }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private ChunkStages() {}

    public static void addListener(Listener l) {
        LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    public static boolean active() {
        return !LISTENERS.isEmpty();
    }

    public static void report(int cx, int cz, Kind kind, String status) {
        for (Listener l : LISTENERS) {
            l.on(cx, cz, kind, status);
        }
    }
}
