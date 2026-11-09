package mcdonate.client;

import mcdonate.common.net.DonorInvite;

/**
 * 第 9 步 c/d：客户端什么时候起 worker、停 worker、要新 token、暂停恢复（纯逻辑；真正的动作由 {@link Env} 做）。
 *
 * <ul>
 *   <li>没同意、设置里没打开：什么都不做（不下载、不起进程）。
 *   <li>同意了、打开了、收到有效邀请：没准备好就先准备（后台下载），准备好了用邀请里的 token、seed 起 worker。
 *   <li>邀请里的 token 60 秒过期：拿到邀请时还没能起（比如后来才打开），用之前先看它新不新，旧了就再要一次（mcdonate:invite_request）。
 *   <li>worker 打 NEED_TOKEN（token 用过了、被拒、coordinator 重启）：再要一次邀请，拿到后把新 token 转给 worker（不重起）。
 *       客户端这边 10 秒最多要一次（服务器那边也限）。
 *   <li>退出世界、关掉设置：停 worker。用过的邀请作废。
 *   <li>帧率限速：PAUSE、RESUME 转给 worker。
 * </ul>
 * 都在客户端主线程（渲染线程）上调。
 */
public final class DonorController {
    /** 外面的世界：同意、设置、准备、进程、发包。 */
    public interface Env {
        boolean consent();

        ClientSettings settings();

        boolean prepared();

        void prepareAsync();

        boolean workerRunning();

        default boolean busy() { return false; }

        void startWorker(DonorInvite invite, ClientSettings settings);

        void stopWorker();

        void sendToWorker(String line);

        void requestInvite();

        long now();
    }

    static final long INVITE_FRESH_MS = 50_000; // token 60 秒过期，留 10 秒余量
    static final long REQUEST_MIN_MS = 10_000;

    private final Env env;
    private DonorInvite invite;
    private long inviteAt;
    private boolean inviteUsed;
    private boolean connected;
    private long lastRequest = Long.MIN_VALUE / 2;

    public DonorController(Env env) {
        this.env = env;
    }

    private boolean wanted() {
        return connected && env.consent() && env.settings().enabled();
    }

    public void onInvite(DonorInvite i) {
        if (!i.valid()) {
            return;
        }
        connected = true;
        invite = i;
        inviteAt = env.now();
        inviteUsed = false;
        if (!wanted()) return;
        if (env.workerRunning()) {
            env.sendToWorker("TOKEN " + i.token()); // worker 在等新 token（NEED_TOKEN）
            inviteUsed = true;
            return;
        }
        tryStart();
    }

    /** 等服务器初次邀请；随后 tick 可以重试启动/过期邀请，不会在主菜单下载。 */
    public void onJoin() {
        connected = true;
        lastRequest = env.now();
    }

    public void tick() { tryStart(); }

    /** 首次准备做完了（后台线程做完后回到主线程调）。 */
    public void onPrepared() {
        tryStart();
    }

    public void onSettingsChanged() {
        if (!wanted()) {
            if (env.workerRunning()) {
                env.stopWorker();
            }
            return;
        }
        tryStart();
    }

    private void tryStart() {
        if (!wanted() || env.workerRunning() || env.busy()) {
            return;
        }
        if (!env.prepared()) {
            env.prepareAsync();
            return;
        }
        if (invite == null) {
            request();
            return;
        }
        if (inviteUsed || env.now() - inviteAt > INVITE_FRESH_MS) {
            request();
            return;
        }
        inviteUsed = true;
        env.startWorker(invite, env.settings());
    }

    /** worker 标准输出里给游戏看的一行（已去掉 MCDONATE 前缀）。 */
    public void onWorkerLine(String body) {
        if (wanted() && body.equals("NEED_TOKEN")) {
            request();
        }
    }

    private void request() {
        long now = env.now();
        if (now - lastRequest < REQUEST_MIN_MS) {
            return;
        }
        lastRequest = now;
        env.requestInvite();
    }

    public void onThrottle(FrameThrottle.Action a) {
        if (a == FrameThrottle.Action.NONE || !env.workerRunning()) {
            return;
        }
        env.sendToWorker(a.name());
    }

    /** 退出世界：停 worker，邀请作废（下次进服会收到新的）。 */
    public void onDisconnect() {
        connected = false;
        if (env.workerRunning()) {
            env.stopWorker();
        }
        invite = null;
        inviteUsed = true;
    }
}
