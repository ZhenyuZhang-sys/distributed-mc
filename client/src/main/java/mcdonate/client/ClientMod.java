package mcdonate.client;

import java.io.IOException;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import mcdonate.common.ModIds;
import mcdonate.common.net.DonorInvite;
import mcdonate.common.net.InviteRequest;
import mcdonate.common.net.WorkerInvite;
import mcdonate.common.net.WorkerInviteRequest;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端 mod（第 9 步）：玩家同意以后，按服务器的邀请在后台起 worker 子进程帮服务器生成地形，按帧率暂停恢复。
 *
 * <p>默认关闭：没同意之前什么都不做——不下载、不起进程、不连 coordinator（docs/step9-plan.md 1.1）。启动时打一行固定的日志说明状态
 * （9.2 的测试认这一行）。打开：暂停菜单里的"mc-donate"按钮 → 设置界面 → 开关 → 第一次弹同意界面（含 Minecraft EULA）。
 */
public final class ClientMod implements ClientModInitializer {
    public static final String MOD_ID = ModIds.of("client");
    static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static final DonorController[] controllers = new DonorController[WorkerInvite.MAX_WORKERS];
    private static final java.util.concurrent.atomic.AtomicReferenceArray<WorkerProcess> workers =
            new java.util.concurrent.atomic.AtomicReferenceArray<>(WorkerInvite.MAX_WORKERS);
    private static final java.util.Set<Integer> preparing = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Map<Integer, String> errors = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<Integer> stopping = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile ClientSettings activePlan;
    private static FrameThrottle throttle;
    private static long lastFrameNanos;
    private static long lastCsvMs;
    private static long lastControllerMs;
    private static final java.util.Map<Integer, Integer> ports = new java.util.concurrent.ConcurrentHashMap<>();

    static Path consentFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("mc-donate.json");
    }

    static Path settingsFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("mc-donate-settings.properties");
    }

    static Path donateDir() {
        return FabricLoader.getInstance().getGameDir().resolve("mc-donate");
    }

    private static WorkerFiles files(int slot) {
        // 第0槽沿用原目录。其他槽只准备自己的运行文件，不复制世界或 worldId。
        return new WorkerFiles(WorkerLayout.directory(donateDir(), slot));
    }

    private static int runningCount() {
        int count = 0;
        for (int i = 0; i < workers.length(); i++) {
            WorkerProcess w = workers.get(i);
            if (w != null && w.alive()) count++;
        }
        return count;
    }

    private static ClientSettings plan() {
        ClientSettings latest = ClientSettings.load(settingsFile());
        ClientSettings budget = activePlan == null ? latest : activePlan;
        ClientSettings s = new ClientSettings(latest.enabled(), budget.threads(), budget.memoryMb(),
                latest.frameTargetMs(), budget.workerCount());
        return ClientPlayNetworking.canSend(WorkerInviteRequest.TYPE) ? s : s.withWorkerCount(1);
    }

    @Override
    public void onInitializeClient() {
        // 两个游戏内频道：服务器发邀请、客户端再要一次（服务器那边登记了同样的）
        PayloadTypeRegistry.playS2C().register(DonorInvite.TYPE, DonorInvite.CODEC);
        PayloadTypeRegistry.playC2S().register(InviteRequest.TYPE, InviteRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(WorkerInvite.TYPE, WorkerInvite.CODEC);
        PayloadTypeRegistry.playC2S().register(WorkerInviteRequest.TYPE, WorkerInviteRequest.CODEC);
        for (int slot = 0; slot < controllers.length; slot++) controllers[slot] = new DonorController(new RealEnv(slot));
        // 游戏内来源上色和算力面板（docs/overlay-plan.md，kexu）：每秒经控制通道要一次 worker 的 STATUS
        mcdonate.client.overlay.OverlayClient.install(new mcdonate.client.overlay.OverlayClient.WorkerLink() {
            @Override
            public boolean running() {
                WorkerProcess w = workers.get(0);
                return w != null && w.alive();
            }

            @Override
            public void send(String line) {
                WorkerProcess w = workers.get(0);
                if (w != null) {
                    w.send(line); // 每秒一次 STATUS，不记日志（RealEnv.sendToWorker 会记）
                }
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(DonorInvite.TYPE,
                (invite, context) -> context.client().execute(() -> onInvite(0, invite)));
        ClientPlayNetworking.registerGlobalReceiver(WorkerInvite.TYPE, (invite, context) -> {
            if (invite.valid()) context.client().execute(() -> onInvite(invite.slot(), invite.invite()));
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            for (DonorController c : controllers) c.onJoin();
            for (ConsentDriver d : FabricLoader.getInstance().getEntrypoints("mcdonate:consent_driver", ConsentDriver.class)) {
                d.onJoin(client);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> { for (DonorController c : controllers) c.onDisconnect(); }));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stopWorkerNow());
        HudRenderCallback.EVENT.register((graphics, tick) -> onFrame());
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof PauseScreen) {
                Screens.getButtons(screen).add(Button.builder(Component.literal("mc-donate"),
                        b -> client.setScreen(new SettingsScreen(screen))).bounds(w - 84, h - 24, 80, 20).build());
            }
        });

        Consent consent = Consent.load(consentFile());
        if (consent.given()) {
            LOG.info("已同意当 donor（{} 同意 EULA），开关：{}", consent.eulaAcceptedAt(),
                    ClientSettings.load(settingsFile()).enabled() ? "开" : "关");
        } else {
            LOG.info("等待同意：mc-donate 默认关闭，没同意之前不下载、不起进程、不连 coordinator");
        }
        LOG.info("{} loaded", MOD_ID);
    }

    private static void onInvite(int slot, DonorInvite invite) {
        if (!invite.valid()) {
            LOG.warn("收到读不懂的 donor 邀请（格式 {}），忽略", invite.version());
            return;
        }
        LOG.info("收到 donor 邀请：coordinator {}:{}", invite.host(), invite.port()); // 不打 token
        if (!Consent.load(consentFile()).given()) {
            LOG.info("没同意，不用这个邀请");
        }
        controllers[slot].onInvite(invite);
    }

    /** 设置变了（设置界面、同意界面调）。 */
    static void settingsChanged() {
        FrameThrottle t = throttle;
        if (t != null) {
            t.setTargetMs(ClientSettings.load(settingsFile()).frameTargetMs()); // 帧时间目标改了马上生效
        }
        Minecraft.getInstance().execute(() -> { for (DonorController c : controllers) c.onSettingsChanged(); });
    }

    static String statusLine() {
        if (!Consent.load(consentFile()).given()) {
            return "还没同意";
        }
        if (!ClientSettings.load(settingsFile()).enabled()) {
            return "关着";
        }
        if (!preparing.isEmpty()) {
            return "第一次准备中（下载服务器）…";
        }
        if (runningCount() > 0) {
            return runningCount() + "/" + plan().effectiveWorkerCount() + " 个 worker 在跑" + (throttle != null && throttle.paused() ? "（帧率低，暂停中）" : "");
        }
        return errors.isEmpty() ? "等服务器的邀请（进一个有 mc-donate 的服务器）" : "出错：" + errors.values().iterator().next();
    }

    // ---- 帧时间限速（第 9 步 d）----

    private static void onFrame() {
        long now = System.nanoTime();
        long prev = lastFrameNanos;
        lastFrameNanos = now;
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastControllerMs >= 1000) {
            lastControllerMs = nowMs;
            for (DonorController c : controllers) c.tick();
        }
        if (prev == 0 || runningCount() == 0) {
            return;
        }
        if (throttle == null) {
            throttle = new FrameThrottle(ClientSettings.load(settingsFile()).frameTargetMs());
        }
        FrameThrottle.Action action = throttle.frame(nowMs, (now - prev) / 1e6);
        for (DonorController c : controllers) c.onThrottle(action);
        if (nowMs - lastCsvMs >= 1000) {
            lastCsvMs = nowMs;
            writeFrameCsv(nowMs, throttle.p99(nowMs), throttle.paused());
        }
    }

    /** 每秒一行：时间、最近 5 秒 p99 帧时间、worker 是不是暂停（docs/step9-plan.md 第 4 节）。只在 worker 跑着时写。 */
    private static void writeFrameCsv(long nowMs, double p99, boolean paused) {
        Path f = donateDir().resolve("frametimes.csv");
        try {
            boolean header = !Files.exists(f);
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (header) {
                    w.write("time_ms,p99_ms,worker\n");
                }
                w.write(nowMs + "," + String.format(Locale.ROOT, "%.1f", p99) + "," + (paused ? "paused" : "running") + "\n");
            }
        } catch (IOException e) {
            // 写不了就算了
        }
    }

    private static void stopWorkerNow() {
        for (int slot = 0; slot < workers.length(); slot++) {
            WorkerProcess w = workers.getAndSet(slot, null);
            if (w != null) try { w.stop(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throttle = null;
    }

    /** DonorController 要的真动作。都在客户端主线程上被调；慢的（下载、停进程）放到后台线程。 */
    private static final class RealEnv implements DonorController.Env {
        private final int slot;
        RealEnv(int slot) { this.slot = slot; }
        public boolean consent() {
            return Consent.load(consentFile()).given();
        }

        public ClientSettings settings() {
            return plan().forWorker(slot);
        }

        public boolean prepared() {
            return files(slot).prepared();
        }

        public void prepareAsync() {
            if (!preparing.add(slot)) return;
            Thread t = new Thread(() -> {
                try {
                    files(slot).prepare(ClientMod::download, name -> ClientMod.class.getResourceAsStream("/mcdonate/worker-mods/" + name));
                    LOG.info("worker 准备好了：{}", files(slot).root());
                    errors.remove(slot);
                } catch (IOException | RuntimeException e) {
                    errors.put(slot, "准备失败：" + e.getMessage());
                    LOG.warn("worker 准备失败：{}", e.toString());
                } finally {
                    preparing.remove(slot);
                    Minecraft.getInstance().execute(controllers[slot]::onPrepared);
                }
            }, "mcdonate-prepare");
            t.setDaemon(true);
            t.start();
        }

        public boolean busy() { return !stopping.isEmpty(); }

        public boolean workerRunning() {
            WorkerProcess w = workers.get(slot);
            return w != null && w.alive();
        }

        public void startWorker(DonorInvite invite, ClientSettings s) {
            if (activePlan == null) activePlan = ClientSettings.load(settingsFile());
            try {
                int port;
                do {
                    try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
                } while (ports.containsValue(port));
                ports.put(slot, port);
                WorkerFiles f = files(slot);
                f.writeServerProperties(port);
                // 每次都按客户端 jar 里带的放 worker mod（客户端升级后不再用旧的）
                f.syncMods(name -> ClientMod.class.getResourceAsStream("/mcdonate/worker-mods/" + name));
                String exe = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows") ? "java.exe" : "java";
                WorkerLaunch.Options o = new WorkerLaunch.Options(Path.of(System.getProperty("java.home"), "bin", exe),
                        s.memoryMb(), s.threads(), ProcessHandle.current().pid(), invite.host() + ":" + invite.port(), invite.seed());
                WorkerProcess started = WorkerProcess.start(WorkerLaunch.command(o, System.getProperty("os.name")),
                        WorkerLaunch.environment(invite.token()), f.root(),
                        line -> {
                            if (!line.startsWith("STATUS ")) {
                                LOG.info("worker：{}", line); // CONNECTED、NEED_TOKEN 这类少见的记下来（STATUS 每秒一次，不记）
                            }
                            if (slot == 0) mcdonate.client.overlay.OverlayClient.onWorkerLine(line); // 只存最新的 STATUS，哪个线程都行
                            Minecraft.getInstance().execute(() -> controllers[slot].onWorkerLine(line));
                        });
                workers.set(slot, started);
                if (throttle != null && throttle.paused()) started.send("PAUSE");
                errors.remove(slot);
                LOG.info("worker 起来了（pid {}，{} 个线程、{} MB，coordinator {}:{}）", started.pid(), s.threads(), s.memoryMb(),
                        invite.host(), invite.port());
            } catch (IOException e) {
                ports.remove(slot);
                errors.put(slot, "worker 起不来：" + e.getMessage());
                LOG.warn("worker 起不来：{}", e.toString());
            }
        }

        public void stopWorker() {
            WorkerProcess w = workers.get(slot);
            workers.set(slot, null);
            if (w != null) {
                stopping.add(slot);
                Thread t = new Thread(() -> {
                    try {
                        boolean clean = w.stop(10, TimeUnit.SECONDS);
                        LOG.info("worker 停了{}", clean ? "" : "（强杀）");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        ports.remove(slot);
                        stopping.remove(slot);
                        if (stopping.isEmpty() && runningCount() == 0) activePlan = null;
                    }
                }, "mcdonate-stop-worker");
                t.setDaemon(true);
                t.start();
            }
        }

        public void sendToWorker(String line) {
            WorkerProcess w = workers.get(slot);
            if (w != null) {
                w.send(line);
                LOG.info("给 worker 发 {}", line.startsWith("TOKEN ") ? "TOKEN ****" : line);
            }
        }

        public void requestInvite() {
            if (ClientPlayNetworking.canSend(WorkerInviteRequest.TYPE)) {
                ClientPlayNetworking.send(new WorkerInviteRequest(slot));
            } else if (slot == 0 && ClientPlayNetworking.canSend(InviteRequest.TYPE)) {
                ClientPlayNetworking.send(new InviteRequest());
                LOG.info("再要一次 donor 邀请");
            }
        }

        public long now() {
            return System.currentTimeMillis();
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20)).build();

    static byte[] download(String url) throws IOException {
        try {
            HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "mc-donate").build(), HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) {
                throw new IOException(url + " 返回 " + r.statusCode());
            }
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被打断", e);
        }
    }
}
