package mcdonate.client;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * 第 9 步（docs/step9-plan.md 第 4 节）：按帧时间决定 worker 暂停还是恢复。纯逻辑，客户端每帧调 {@link #frame}。
 *
 * <ul>
 *   <li>看最近 10 秒帧时间的中位数（按帧数）。
 *   <li>中位数高于目标持续 5 秒 → {@link Action#PAUSE}；低于目标的 0.8 持续 5 秒 → {@link Action#RESUME}。两者之间保持现状。
 *   <li>每 100 ms 算一次（排序几百个数，不必每帧算）。
 * </ul>
 * 11-09 改（原来是 5 秒窗口的 p99 超过目标 2 秒就暂停）：用户真机上鞘翅进新区域时客户端加载 chunk 必然掉帧，
 * p99 一下就超，donor 正好在最需要的时候停下。中位数只有在一半以上的帧比目标慢（游戏整体慢）时才超。
 * p99 还算，写进 frametimes.csv 给人看。
 */
public final class FrameThrottle {
    public enum Action { NONE, PAUSE, RESUME }

    static final long WINDOW_MS = 10_000;
    static final long PAUSE_AFTER_MS = 5_000;
    static final long RESUME_AFTER_MS = 5_000;
    static final long EVAL_EVERY_MS = 100;
    static final double RESUME_FRACTION = 0.8;

    private volatile double targetMs;
    private final ArrayDeque<long[]> frames = new ArrayDeque<>(); // [时间, 帧时间 × 1000（微秒）]
    private boolean paused;
    private long overSince = -1;
    private long underSince = -1;
    private long lastEval = Long.MIN_VALUE;

    public FrameThrottle(double targetMs) {
        this.targetMs = targetMs;
    }

    /** 设置里改了帧时间目标：马上生效（不重启 worker；暂停着的按新的恢复线判）。 */
    public void setTargetMs(double ms) {
        targetMs = ms;
    }

    public double targetMs() {
        return targetMs;
    }

    /** 记一帧（nowMs：现在，frameMs：这一帧用了多久），返回要不要发 PAUSE / RESUME。 */
    public Action frame(long nowMs, double frameMs) {
        frames.addLast(new long[] {nowMs, Math.round(frameMs * 1000)});
        while (!frames.isEmpty() && frames.peekFirst()[0] <= nowMs - WINDOW_MS) {
            frames.pollFirst();
        }
        if (lastEval != Long.MIN_VALUE && nowMs - lastEval < EVAL_EVERY_MS) {
            return Action.NONE;
        }
        lastEval = nowMs;
        double p = median(nowMs);
        if (p > targetMs) {
            underSince = -1;
            if (overSince < 0) {
                overSince = nowMs;
            }
            if (!paused && nowMs - overSince >= PAUSE_AFTER_MS) {
                paused = true;
                return Action.PAUSE;
            }
        } else if (p < targetMs * RESUME_FRACTION) {
            overSince = -1;
            if (underSince < 0) {
                underSince = nowMs;
            }
            if (paused && nowMs - underSince >= RESUME_AFTER_MS) {
                paused = false;
                return Action.RESUME;
            }
        } else {
            overSince = -1;
            underSince = -1;
        }
        return Action.NONE;
    }

    /** 最近 10 秒帧时间的中位数（毫秒，按帧数，偶数个取靠上的）；没有帧时是 0。暂停、恢复按它判。 */
    public double median(long nowMs) {
        long[] v = frames.stream().filter(f -> f[0] > nowMs - WINDOW_MS).mapToLong(f -> f[1]).toArray();
        if (v.length == 0) {
            return 0;
        }
        Arrays.sort(v);
        return v[v.length / 2] / 1000.0;
    }

    /** 最近 10 秒的 p99（毫秒，最近秩）；没有帧时是 0。只写进 frametimes.csv。 */
    public double p99(long nowMs) {
        long[] v = frames.stream().filter(f -> f[0] > nowMs - WINDOW_MS).mapToLong(f -> f[1]).toArray();
        if (v.length == 0) {
            return 0;
        }
        Arrays.sort(v);
        return v[(int) Math.ceil(0.99 * v.length) - 1] / 1000.0;
    }

    public boolean paused() {
        return paused;
    }
}
