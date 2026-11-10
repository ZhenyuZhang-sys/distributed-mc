package mcdonate.worker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 每个任务是哪一次连接（代）收到的（11-09，codex-audit A1）。断线时这一代的任务全部作废：
 * 排着的不做，做完的结果不发（不能经新连接发回去；coordinator 重启后编号会重复）。线程安全。
 */
final class ConnectionGenerations {
    private long generation;
    private final Map<Long, Long> taskGeneration = new HashMap<>();

    /** 连上了：开始新的一代。 */
    synchronized void connected() {
        generation++;
    }

    /** 现在是第几代。 */
    synchronized long current() {
        return generation;
    }

    synchronized void received(long taskId) {
        taskGeneration.put(taskId, generation);
    }

    /** 这个任务是哪一代收到的；不认识是 -1。 */
    synchronized long generationOf(long taskId) {
        Long g = taskGeneration.get(taskId);
        return g == null ? -1 : g;
    }

    /** 这个任务是这一代收到的（结果可以发）。 */
    synchronized boolean isCurrent(long taskId) {
        Long g = taskGeneration.get(taskId);
        return g != null && g == generation;
    }

    synchronized void finished(long taskId) {
        taskGeneration.remove(taskId);
    }

    /** 断线：返回这一代还没做完的任务（全部作废），并忘掉它们。 */
    synchronized List<Long> disconnected() {
        List<Long> out = new ArrayList<>();
        taskGeneration.entrySet().removeIf(e -> {
            if (e.getValue() == generation) {
                out.add(e.getKey());
                return true;
            }
            return false;
        });
        out.sort(null);
        generation++;
        return out;
    }
}
