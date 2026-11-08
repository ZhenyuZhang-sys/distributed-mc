package mcdonate.coordinator;

/**
 * 第 7 步 7.4：对冲。纯逻辑。
 *
 * <p>任务快到 deadline 还没回来，就撤回，交给服务器自己生成（不交给另一个 donor：
 * 两个不同 donor 世界的结果不能拼，第 6 步 0.3）。
 *
 * <p>判断按"预计什么时候做完"：
 * <ul>
 *   <li>还没超过它的平均耗时：预计 = 派出时间 + 平均耗时；
 *   <li>已经超过了（这次比平时慢）：预计 = 现在 + 平均耗时的一半。
 * </ul>
 * 预计完成时间晚于 deadline 才撤。不用"剩余时间 < 平均耗时"：那样正常做到一半的任务也会被撤。
 */
final class Hedge {
    private Hedge() {}

    static long expectedFinish(long leasedAtMs, long averageMs, long nowMs) {
        long onTrack = leasedAtMs + averageMs;
        return nowMs <= onTrack ? onTrack : nowMs + averageMs / 2;
    }

    static boolean shouldHedge(long leasedAtMs, long averageMs, long nowMs, long deadlineMs) {
        if (deadlineMs == Long.MAX_VALUE) {
            return false;
        }
        return expectedFinish(leasedAtMs, averageMs, nowMs) > deadlineMs;
    }
}
