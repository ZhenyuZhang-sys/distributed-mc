package mcdonate.client;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * 第 9 步（docs/step9-plan.md 第 4 节）：按帧时间决定 worker 暂停还是恢复。纯逻辑，客户端每帧调 {@link #frame}。
 *
 * <ul>
 *   <li>看最近 5 秒帧时间的 p99（最近秩）。
 *   <li>p99 高于目标持续 2 秒 → {@link Action#PAUSE}；低于目标的 0.8 持续 5 秒 → {@link Action#RESUME}。两者之间保持现状。
 *   <li>p99 每 100 ms 算一次（排序几百个数，不必每帧算）。
 * </ul>
 * 注意：5 秒窗口里的 p99 对"卡满 1 秒"这种卡顿很敏感（慢帧超过 1%，p99 高 5 秒），照规则会暂停；
 * 一两帧的卡顿不会。目标、窗口都可配置，真实电脑上测 Z 时再看要不要调（9.7）。
 */
public final class FrameThrottle {
    public enum Action { NONE, PAUSE, RESUME }

    static final long WINDOW_MS = 5_000;
    static final long PAUSE_AFTER_MS = 2_000;
    static final long RESUME_AFTER_MS = 5_000;
    static final long EVAL_EVERY_MS = 100;
    static final double RESUME_FRACTION = 0.8;

    private final double targetMs;
    private final ArrayDeque<long[]> frames = new ArrayDeque<>(); // [时间, 帧时间 × 1000（微秒）]
    private boolean paused;
    private long overSince = -1;
    private long underSince = -1;
    private long lastEval = Long.MIN_VALUE;

    public FrameThrottle(double targetMs) {
        this.targetMs = targetMs;
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
        double p = p99(nowMs);
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

    /** 最近 5 秒的 p99（毫秒，最近秩）；没有帧时是 0。 */
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
