package mcdonate.bench.flight;

import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLongArray;
import mcdonate.bench.Generation;
import mcdonate.common.Provenance;
import mcdonate.bench.mixin.ChunkMapAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * 一次飞行（docs/step4-plan.md 2、3.1 节）。只在主线程上用。
 *
 * <ul>
 *   <li>tick 开始：按 {@link Flight} 算位置，把玩家放过去，再让 ChunkMap 更新视野中心（不靠移动包）。
 *   <li>tick 结束：{@link ViewTracker} 扫一遍视野，{@link SecondRows} 汇总成每秒一行。
 *   <li>就绪 = {@code ChunkHolder.getChunkToSend() != null}，和原版决定"能发给玩家"的条件一样。
 *   <li>正常飞完把玩家放到终点（tick 模式下最后一个 tick 的位置还差一步）。
 * </ul>
 */
public final class FlightRecorder {
    static final int SCAN_EVERY_TICKS = 1;
    final String run;
    private final UUID playerId;
    private final String playerName;
    private final ServerLevel level;
    private final Flight flight;
    private final int viewDistance;
    private final int requestedViewDistance;
    private final FlightOutput out;
    private final ViewTracker tracker;
    private final SecondRows rows;
    private final ViewTracker.Snapshot initial;
    /** 起飞命令执行时的 System.nanoTime；逐秒、chunk 事件、来源事件的时间都从这里算。 */
    private final long t0 = System.nanoTime();
    private long tickStart, ticks, scanNanos, endMs;
    private boolean started;
    /** 来源事件计数，按 Provenance.Kind 的顺序；监听器在生成线程上调用。 */
    private final AtomicLongArray provenance = new AtomicLongArray(Provenance.Kind.values().length);
    private final Provenance.Listener listener = this::provenance;
    private final Generation.Snapshot countersAtStart;
    private int enters, readies, leaves;
    private boolean finished;

    FlightRecorder(String run, ServerPlayer player, Flight flight, int viewDistance, FlightOutput out) {
        this.run = run;
        this.playerId = player.getUUID();
        this.playerName = player.getGameProfile().getName();
        this.level = player.serverLevel();
        this.flight = flight;
        this.viewDistance = viewDistance;
        this.requestedViewDistance = player.requestedViewDistance();
        this.out = out;
        this.tracker = new ViewTracker(viewDistance, this::event);
        this.rows = new SecondRows(flight.mode() == Flight.Mode.WALL ? (int) Math.ceil(flight.seconds()) : 0, r -> out.second(r.json()));
        player.getAbilities().mayfly = true;
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        place(player, flight.x(0), flight.z(0));
        ChunkPos c = player.chunkPosition();
        this.initial = tracker.start(c.x, c.z, this::ready);
        out.runJson(config().toString());
        countersAtStart = Generation.listen(listener);
    }

    UUID playerId() {
        return playerId;
    }

    boolean finished() {
        return finished;
    }

    /** 视野里还没就绪的 chunk 数（/bench fly ready 用，起飞前等它变 0）。 */
    static ViewTracker.Snapshot readiness(ServerPlayer player, int viewDistance) {
        ServerLevel level = player.serverLevel();
        int[] n = {0, 0};
        net.minecraft.server.level.ChunkTrackingView.of(player.chunkPosition(), viewDistance).forEach(p -> {
            n[0]++;
            if (!ready(level, p.x, p.z)) n[1]++;
        });
        return new ViewTracker.Snapshot(n[0], n[1]);
    }

    void startTick(MinecraftServer server) {
        if (finished) return;
        ServerPlayer p = player(server);
        if (p == null) {
            finish(false, "player_left");
            return;
        }
        long now = System.nanoTime();
        started = true;
        tickStart = now;
        double e = flight.elapsed(now - t0, ticks);
        place(p, flight.x(e), flight.z(e));
    }

    void endTick(MinecraftServer server) {
        if (finished || !started) return;
        ServerPlayer p = player(server);
        if (p == null) {
            finish(false, "player_left");
            return;
        }
        long now = System.nanoTime();
        ticks++;
        long elapsed = now - t0;
        endMs = elapsed / 1_000_000;
        long scan = System.nanoTime();
        ViewTracker.Snapshot s = tracker.tick(endMs, SectionPos.blockToSectionCoord(p.getX()), SectionPos.blockToSectionCoord(p.getZ()), this::ready);
        scanNanos += System.nanoTime() - scan;
        rows.tick(elapsed, now - tickStart, p.getX(), p.getZ(), s);
        if (flight.finished(flight.elapsed(elapsed, ticks))) {
            place(p, flight.x(flight.seconds()), flight.z(flight.seconds()));
            finish(false, null);
        }
    }

    /** 结束：写最后一秒和 done.json。stopped = 被 /bench fly stop 提前停下；aborted = 出错原因（没有就是 null）。 */
    void finish(boolean stopped, String aborted) {
        if (finished) return;
        finished = true;
        rows.finish();
        Generation.Snapshot end = Generation.unlisten(listener);
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(playerId);
        JsonObject done = config();
        done.addProperty("end_x", p != null ? (Number) p.getX() : null);
        done.addProperty("end_z", p != null ? (Number) p.getZ() : null);
        done.addProperty("ticks", ticks);
        done.addProperty("rows", rows.written());
        done.addProperty("end_ms", endMs);
        // 第一个 tick 开始时的 System.nanoTime。Linux 上它就是 CLOCK_MONOTONIC，容器和宿主机共用，宿主机的 cgroup 采样靠它对齐
        done.addProperty("t0_nanotime", t0);
        // 监听期间收到的来源事件数，和同一段时间的计数增量（Generation 保证两者对得上：原版 = generated + resumed）
        JsonObject prov = new JsonObject();
        for (Provenance.Kind k : Provenance.Kind.values()) prov.addProperty(k.name().toLowerCase(java.util.Locale.ROOT), provenance.get(k.ordinal()));
        done.add("provenance", prov);
        JsonObject delta = new JsonObject();
        delta.addProperty("full_generated", end.generated() - countersAtStart.generated());
        delta.addProperty("full_loaded", end.loaded() - countersAtStart.loaded());
        delta.addProperty("full_resumed", end.resumed() - countersAtStart.resumed());
        done.add("counters", delta);
        JsonObject ev = new JsonObject();
        ev.addProperty("enter", enters);
        ev.addProperty("ready", readies);
        ev.addProperty("leave", leaves);
        done.add("events", ev);
        done.addProperty("scan_ms_total", scanNanos / 1e6);
        done.addProperty("stopped", stopped);
        done.addProperty("aborted", aborted);
        out.done(done.toString());
    }

    JsonObject status() {
        JsonObject o = new JsonObject();
        o.addProperty("run", run);
        o.addProperty("player", playerName);
        o.addProperty("elapsed_s", (System.nanoTime() - t0) / 1e9);
        o.addProperty("rows", rows.written());
        ViewTracker.Snapshot s = tracker.snapshot();
        o.addProperty("view", s.view());
        o.addProperty("not_ready", s.notReady());
        return o;
    }

    private JsonObject config() {
        JsonObject o = new JsonObject();
        o.addProperty("version", 1);
        o.addProperty("run", run);
        o.addProperty("player", playerName);
        o.addProperty("uuid", playerId.toString());
        o.addProperty("mode", flight.mode().name().toLowerCase(java.util.Locale.ROOT));
        o.addProperty("speed", flight.blocksPerSecond());
        o.addProperty("seconds", flight.seconds());
        o.addProperty("y", flight.y());
        o.addProperty("start_x", flight.startX());
        o.addProperty("start_z", flight.startZ());
        o.addProperty("view_distance", viewDistance);
        // 假玩家的请求视距（原版发包按 clamp(请求, 2, 服务器视距)；加载和生成不受影响，见计划 2.1）
        o.addProperty("requested_view_distance", requestedViewDistance);
        o.addProperty("available_processors", Runtime.getRuntime().availableProcessors());
        // 每几个 tick 扫一次视野（现在每 tick；计划 5.1：开销超过 MSPT 的 2% 就改成 2），结果里必须带上
        o.addProperty("scan_every_ticks", SCAN_EVERY_TICKS);
        JsonObject init = new JsonObject();
        init.addProperty("view", initial == null ? 0 : initial.view());
        init.addProperty("not_ready", initial == null ? 0 : initial.notReady());
        o.add("initial", init);
        // 给第 7 步预留（计划 6.1）：coordinator 的配置由系统属性给，没有就是 null
        o.addProperty("coordinator_features", System.getProperty("mcdonate.features"));
        o.addProperty("demand_lookahead", System.getProperty("mcdonate.demandLookahead"));
        return o;
    }

    private void event(long tMs, int cx, int cz, ViewTracker.Kind kind) {
        switch (kind) {
            case ENTER -> enters++;
            case READY -> readies++;
            case LEAVE -> leaves++;
        }
        out.chunk("{\"t_ms\":" + tMs + ",\"cx\":" + cx + ",\"cz\":" + cz + ",\"ev\":\"" + kind.name().toLowerCase(java.util.Locale.ROOT) + "\"}");
    }

    /** 来源事件（任何线程）：写进 provenance.jsonl，t_ms 从起飞命令算。地图按它上色和回放。 */
    private void provenance(int cx, int cz, Provenance.Kind kind, int donorId) {
        provenance.incrementAndGet(kind.ordinal());
        out.provenance("{\"t_ms\":" + (System.nanoTime() - t0) / 1_000_000 + ",\"cx\":" + cx + ",\"cz\":" + cz
                + ",\"kind\":\"" + kind.name().toLowerCase(java.util.Locale.ROOT) + "\",\"donor\":" + donorId + "}");
    }

    private boolean ready(int cx, int cz) {
        return ready(level, cx, cz);
    }

    private static boolean ready(ServerLevel level, int cx, int cz) {
        ChunkHolder h = ((ChunkMapAccess) level.getChunkSource().chunkMap).mcdonate$visibleHolder(ChunkPos.asLong(cx, cz));
        return h != null && h.getChunkToSend() != null;
    }

    private ServerPlayer player(MinecraftServer server) {
        ServerPlayer p = server.getPlayerList().getPlayer(playerId);
        return p == null || p.isRemoved() || p.serverLevel() != level ? null : p;
    }

    private void place(ServerPlayer p, double x, double z) {
        p.teleportTo(level, x, flight.y(), z, p.getYRot(), p.getXRot());
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance = 0;
        level.getChunkSource().move(p);
    }
}
