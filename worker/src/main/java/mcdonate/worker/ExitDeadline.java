package mcdonate.worker;

import java.util.concurrent.atomic.AtomicBoolean;

/** 客户端启动的可重建worker，停服最多宽限5秒。计时不依赖MC主线程或关闭钩子完成。 */
final class ExitDeadline {
    static final long GRACE_MS = 5_000;
    private final long graceMs;
    private final Runnable hardExit;
    private final AtomicBoolean armed = new AtomicBoolean();
    private final AtomicBoolean requested = new AtomicBoolean();
    private final AtomicBoolean halted = new AtomicBoolean();

    ExitDeadline(long graceMs, Runnable hardExit) {
        this.graceMs = graceMs;
        this.hardExit = hardExit;
    }

    void arm() {
        if (!armed.compareAndSet(false, true)) return;
        Thread deadline = new Thread(() -> {
            try { Thread.sleep(graceMs); } catch (InterruptedException ignored) { }
            halt();
        }, "mcdonate-exit-deadline");
        deadline.setDaemon(true);
        deadline.start();
    }

    void stop(Runnable graceful) {
        arm(); // 日志、socket关闭或停服本身也可能阻塞，先启动独立兜底。
        if (requested.compareAndSet(false, true)) {
            try { graceful.run(); } catch (Throwable failure) { halt(); }
        }
    }

    private void halt() {
        if (halted.compareAndSet(false, true)) hardExit.run();
    }
}
