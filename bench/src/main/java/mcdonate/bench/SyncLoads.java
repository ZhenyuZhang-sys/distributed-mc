package mcdonate.bench;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 守护计数（11-09，调度要求所有飞行都带）：主线程上 ServerChunkCache.getChunk(load=true) 耗时 ≥ 1 ms 的次数，
 * 最长一次和它的调用栈。真实玩家不会让主线程同步生成（客户端不走进没收到的 chunk），测量时这个数必须是 0。
 *
 * <p>分两类：玩家实体 tick（ServerPlayer.tick / doTick）里发生的算"玩家引起的"，测量时必须是 0；
 * 其余（原版流体 tick、chunk 变成可 tick 时的后处理读到还没做完的邻居……）算"world"，真实玩家的服务器也会有，只记录。
 *
 * <p>计时状态只在主线程上读写（mixin 里先判断线程）。getChunk 嵌套调用只算最外层；
 * 万一某次抛异常没走到 RETURN，每个 tick 开始时清一次（{@link #onTickStart}）。
 */
public final class SyncLoads {
    public static final long THRESHOLD_NANOS = 1_000_000L;

    /** 一次慢的同步加载。 */
    public interface Listener { void slow(int cx, int cz, long nanos, String stack, boolean playerTick); }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static int depth, playerDepth;
    private static long start;

    private SyncLoads() {}

    public static void addListener(Listener l) {
        LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    public static void onTickStart() {
        depth = 0;
        playerDepth = 0;
    }

    /** ServerPlayer.tick / doTick 开头和返回（主线程）。 */
    public static void enterPlayerTick() {
        playerDepth++;
    }

    public static void exitPlayerTick() {
        if (playerDepth > 0) {
            playerDepth--;
        }
    }

    /** getChunk 开头（只在主线程、load=true 时调）。 */
    public static void enter() {
        if (depth++ == 0) {
            start = System.nanoTime();
        }
    }

    /** getChunk 返回。 */
    public static void exit(int cx, int cz) {
        if (depth == 0 || --depth > 0) {
            return;
        }
        long nanos = System.nanoTime() - start;
        if (nanos < THRESHOLD_NANOS || LISTENERS.isEmpty()) {
            return;
        }
        String stack = stack();
        for (Listener l : LISTENERS) {
            l.slow(cx, cz, nanos, stack, playerDepth > 0);
        }
    }

    /** 调用栈的前 40 层，去掉 mixin 和这个类自己。 */
    static String stack() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            String c = e.getClassName();
            if (c.startsWith("java.lang.Thread") || c.equals(SyncLoads.class.getName()) || e.getMethodName().contains("mcdonate$")) {
                continue;
            }
            sb.append(c).append('.').append(e.getMethodName()).append(':').append(e.getLineNumber()).append('\n');
            if (++n == 40) {
                break;
            }
        }
        return sb.toString();
    }
}
