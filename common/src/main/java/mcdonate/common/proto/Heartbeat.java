package mcdonate.common.proto;

/** 双向，每 5 秒一条。worker 发时 taskId 是正在做的任务（没有就是 -1），用来续租；coordinator 发 -1。 */
public record Heartbeat(long sentAtMs, long taskId) implements Message {
    public static final long NO_TASK = -1;
}
