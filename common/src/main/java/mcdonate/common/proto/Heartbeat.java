package mcdonate.common.proto;

/**
 * 双向，每 5 秒一条。worker 发时 taskId 是正在做的任务（没有就是 -1），用来续租；coordinator 发 -1。
 * paused（第 9 步加）：worker 因为限速暂停了，coordinator 别再给它派新任务；手上的任务照样用 taskId 报，租约照算。
 * 旧消息没有 paused，按没暂停处理。
 * boost、threads（11-09 加速模式）：donor 愿意空闲时做后台 tile；它的生成线程数（定在途上限，不知道是 0）。
 * 旧消息没有，按不加速、0 处理。coordinator 发的都是 false、0。
 * cpus、genPool（11-10 加）：worker 进程看到的核数（availableProcessors）和原版生成线程池实际的并行度，
 * 测量时核对用（docker 测试 worker 和真客户端的 -XX:ActiveProcessorCount 要一致）。旧消息没有，0 是不知道。
 */
public record Heartbeat(long sentAtMs, long taskId, boolean paused, boolean boost, int threads, int cpus, int genPool)
        implements Message {
    public static final long NO_TASK = -1;

    /** 不带核数、生成池（不知道）。 */
    public Heartbeat(long sentAtMs, long taskId, boolean paused, boolean boost, int threads) {
        this(sentAtMs, taskId, paused, boost, threads, 0, 0);
    }

    /** 不带加速信息。 */
    public Heartbeat(long sentAtMs, long taskId, boolean paused) {
        this(sentAtMs, taskId, paused, false, 0);
    }

    /** 没暂停。 */
    public Heartbeat(long sentAtMs, long taskId) {
        this(sentAtMs, taskId, false);
    }
}
