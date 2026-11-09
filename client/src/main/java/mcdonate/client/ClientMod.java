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

    private static DonorController controller;
    private static volatile WorkerProcess worker;
    private static volatile boolean preparing;
    private static volatile String lastError = "";
    private static FrameThrottle throttle;
    private static long lastFrameNanos;
    private static long lastCsvMs;

    static Path consentFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("mc-donate.json");
    }

    static Path settingsFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("mc-donate-settings.properties");
    }

    static Path donateDir() {
        return FabricLoader.getInstance().getGameDir().resolve("mc-donate");
    }

    private static WorkerFiles files() {
        return new WorkerFiles(donateDir().resolve("worker"));
    }

    @Override
    public void onInitializeClient() {
        // 两个游戏内频道：服务器发邀请、客户端再要一次（服务器那边登记了同样的）
        PayloadTypeRegistry.playS2C().register(DonorInvite.TYPE, DonorInvite.CODEC);
        PayloadTypeRegistry.playC2S().register(InviteRequest.TYPE, InviteRequest.CODEC);
        controller = new DonorController(new RealEnv());
        ClientPlayNetworking.registerGlobalReceiver(DonorInvite.TYPE, (invite, context) -> onInvite(invite));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            for (ConsentDriver d : FabricLoader.getInstance().getEntrypoints("mcdonate:consent_driver", ConsentDriver.class)) {
                d.onJoin(client);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(controller::onDisconnect));
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

    private static void onInvite(DonorInvite invite) {
        if (!invite.valid()) {
            LOG.warn("收到读不懂的 donor 邀请（格式 {}），忽略", invite.version());
            return;
        }
        LOG.info("收到 donor 邀请：coordinator {}:{}", invite.host(), invite.port()); // 不打 token
        if (!Consent.load(consentFile()).given()) {
            LOG.info("没同意，不用这个邀请");
        }
        controller.onInvite(invite);
    }

    /** 设置变了（设置界面、同意界面调）。 */
    static void settingsChanged() {
        Minecraft.getInstance().execute(controller::onSettingsChanged);
    }

    static String statusLine() {
        if (!Consent.load(consentFile()).given()) {
            return "还没同意";
        }
        if (!ClientSettings.load(settingsFile()).enabled()) {
            return "关着";
        }
        if (preparing) {
            return "第一次准备中（下载服务器）…";
        }
        WorkerProcess w = worker;
        if (w != null && w.alive()) {
            return "worker 在跑" + (throttle != null && throttle.paused() ? "（帧率低，暂停中）" : "");
        }
        return lastError.isEmpty() ? "等服务器的邀请（进一个有 mc-donate 的服务器）" : "出错：" + lastError;
    }

    // ---- 帧时间限速（第 9 步 d）----

    private static void onFrame() {
        long now = System.nanoTime();
        long prev = lastFrameNanos;
        lastFrameNanos = now;
        WorkerProcess w = worker;
        if (prev == 0 || w == null || !w.alive()) {
            return;
        }
        if (throttle == null) {
            throttle = new FrameThrottle(ClientSettings.load(settingsFile()).frameTargetMs());
        }
        long nowMs = System.currentTimeMillis();
        controller.onThrottle(throttle.frame(nowMs, (now - prev) / 1e6));
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
        WorkerProcess w = worker;
        worker = null;
        throttle = null;
        if (w != null) {
            try {
                w.stop(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** DonorController 要的真动作。都在客户端主线程上被调；慢的（下载、停进程）放到后台线程。 */
    private static final class RealEnv implements DonorController.Env {
        public boolean consent() {
            return Consent.load(consentFile()).given();
        }

        public ClientSettings settings() {
            return ClientSettings.load(settingsFile());
        }

        public boolean prepared() {
            return files().prepared();
        }

        public void prepareAsync() {
            if (preparing) {
                return;
            }
            preparing = true;
            Thread t = new Thread(() -> {
                try {
                    files().prepare(ClientMod::download, name -> ClientMod.class.getResourceAsStream("/mcdonate/worker-mods/" + name));
                    LOG.info("worker 准备好了：{}", files().root());
                    lastError = "";
                } catch (IOException | RuntimeException e) {
                    lastError = "准备失败：" + e.getMessage();
                    LOG.warn("worker 准备失败：{}", e.toString());
                } finally {
                    preparing = false;
                    Minecraft.getInstance().execute(controller::onPrepared);
                }
            }, "mcdonate-prepare");
            t.setDaemon(true);
            t.start();
        }

        public boolean workerRunning() {
            WorkerProcess w = worker;
            return w != null && w.alive();
        }

        public void startWorker(DonorInvite invite, ClientSettings s) {
            try {
                int port;
                try (ServerSocket probe = new ServerSocket(0)) {
                    port = probe.getLocalPort();
                }
                WorkerFiles f = files();
                f.writeServerProperties(port);
                // 每次都按客户端 jar 里带的放 worker mod（客户端升级后不再用旧的）
                f.syncMods(name -> ClientMod.class.getResourceAsStream("/mcdonate/worker-mods/" + name));
                String exe = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows") ? "java.exe" : "java";
                WorkerLaunch.Options o = new WorkerLaunch.Options(Path.of(System.getProperty("java.home"), "bin", exe),
                        s.memoryMb(), s.threads(), ProcessHandle.current().pid(), invite.host() + ":" + invite.port(), invite.seed());
                worker = WorkerProcess.start(WorkerLaunch.command(o, System.getProperty("os.name")),
                        WorkerLaunch.environment(invite.token()), f.root(),
                        line -> Minecraft.getInstance().execute(() -> controller.onWorkerLine(line)));
                throttle = null;
                lastError = "";
                LOG.info("worker 起来了（pid {}，{} 个线程、{} MB，coordinator {}:{}）", worker.pid(), s.threads(), s.memoryMb(),
                        invite.host(), invite.port());
            } catch (IOException e) {
                lastError = "worker 起不来：" + e.getMessage();
                LOG.warn("worker 起不来：{}", e.toString());
            }
        }

        public void stopWorker() {
            WorkerProcess w = worker;
            worker = null;
            throttle = null;
            if (w != null) {
                Thread t = new Thread(() -> {
                    try {
                        boolean clean = w.stop(10, TimeUnit.SECONDS);
                        LOG.info("worker 停了{}", clean ? "" : "（强杀）");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "mcdonate-stop-worker");
                t.setDaemon(true);
                t.start();
            }
        }

        public void sendToWorker(String line) {
            WorkerProcess w = worker;
            if (w != null) {
                w.send(line);
                LOG.info("给 worker 发 {}", line.startsWith("TOKEN ") ? "TOKEN ****" : line);
            }
        }

        public void requestInvite() {
            if (ClientPlayNetworking.canSend(InviteRequest.TYPE)) {
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
