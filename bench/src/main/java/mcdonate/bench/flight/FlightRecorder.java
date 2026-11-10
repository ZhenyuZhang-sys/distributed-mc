package mcdonate.bench.flight;

import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLongArray;
import mcdonate.bench.Generation;
import mcdonate.bench.SyncLoads;
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
    private int startTick = -1;
    /** 起飞命令执行时的 System.nanoTime；逐秒、chunk 事件、来源事件的时间都从这里算。 */
    private final long t0 = System.nanoTime();
    private long tickStart, ticks, scanNanos, endMs;
    private boolean started;
    /** 来源事件计数，按 Provenance.Kind 的顺序；监听器在生成线程上调用。 */
    private final AtomicLongArray provenance = new AtomicLongArray(Provenance.Kind.values().length);
    private final Provenance.Listener listener = this::provenance;
    private final Generation.Snapshot countersAtStart;
    /** 现在的位置（按速度积分，不越过没就绪的 chunk，见 Flight.advance）和上次前进到的起飞后纳秒。 */
    private double posX, posZ, distance;
    private long movedUntil;
    private long stalledTicks, unreadyTicks;
    private double stallNanos;
    private boolean stalledThisTick;
    /** 守护计数：这次飞行期间主线程同步取 chunk ≥ 1 ms 的次数、总耗时、最长一次和它的栈（主线程上更新）。 */
    /** 玩家实体 tick 里的（[0]，必须是 0）和其余的（[1]，原版流体 tick 等，只记录）。 */
    private final long[] syncCount = new long[2], syncTotalNanos = new long[2], syncMaxNanos = new long[2];
    private final String[] syncMaxStack = {"", ""};
    private final SyncLoads.Listener syncListener = (cx, cz, nanos, stack, playerTick) -> {
        int i = playerTick ? 0 : 1;
        syncCount[i]++;
        syncTotalNanos[i] += nanos;
        if (nanos > syncMaxNanos[i]) {
            syncMaxNanos[i] = nanos;
            syncMaxStack[i] = "chunk " + cx + "," + cz + "\n" + stack;
        }
    };
    private int enters, readies, leaves;
    private boolean finished;
    // 跟踪真人玩家（flight == null，docs/step10-plan.md 7.2）：只记录不移动
    private final double startX, startZ, startY;
    private double prevX, prevZ;
    private long lastEndNanos;
    private String stoppedReason;
    // 路线飞行（调度 11-09 真实场景）：若干段，flight 只是总时长和平均速度的摘要
    private Route route;
    private Flight[] legs;

    /** 跟踪真人玩家：不移动、不改能力，记录视野、卡住（{@link RealStall} 的近似）、来源事件，文件和飞行一样。 */
    static FlightRecorder record(String run, ServerPlayer player, int viewDistance, FlightOutput out) {
        return new FlightRecorder(run, player, null, viewDistance, out);
    }

    /** 路线飞行：flight 是摘要（起点、高度、总时长、平均速度，墙钟模式），每段各自按客户端的方式前进。 */
    static FlightRecorder route(String run, ServerPlayer player, Route route, int viewDistance, FlightOutput out) {
        double secs = route.seconds();
        Flight summary = new Flight(player.getX(), player.getZ(), player.getY(), 1, 0, Math.max(1e-6, route.idealDistance() / secs), secs,
                Flight.Mode.WALL);
        FlightRecorder r = new FlightRecorder(run, player, summary, viewDistance, out, route);
        return r;
    }

    FlightRecorder(String run, ServerPlayer player, Flight flight, int viewDistance, FlightOutput out, Route route) {
        this(run, player, flight, viewDistance, out);
        this.route = route;
        this.legs = new Flight[route.segments().size()];
        for (int i = 0; i < legs.length; i++) {
            Route.Segment s = route.segments().get(i);
            legs[i] = s.speed() > 0 ? new Flight(0, 0, flight.y(), s.dirX(), s.dirZ(), s.speed(), s.seconds(), Flight.Mode.WALL) : null;
        }
        out.runJson(config().toString());   // 带上路线再写一遍 run.json
        face(player, route.segments().get(0).yaw());
        place(player, posX, posZ);
    }

    /** 假玩家朝当前这一段的方向看（站着也是）：coordinator 的后台扫描站着时按视线 yaw 往前铺（调度 11-10 加速演示）。 */
    private static void face(ServerPlayer p, float yaw) {
        p.setYRot(yaw);
        p.setYHeadRot(yaw);
        p.setYBodyRot(yaw);
    }

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
        this.rows = new SecondRows(flight != null && flight.mode() == Flight.Mode.WALL ? (int) Math.ceil(flight.seconds()) : 0,
                r -> out.second(r.json()));
        this.startX = flight != null ? flight.startX() : player.getX();
        this.startZ = flight != null ? flight.startZ() : player.getZ();
        this.startY = flight != null ? flight.y() : player.getY();
        posX = prevX = startX;
        posZ = prevZ = startZ;
        if (flight != null) {
            player.getAbilities().mayfly = true;
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            place(player, posX, posZ);
        }
        ChunkPos c = player.chunkPosition();
        this.initial = tracker.start(c.x, c.z, this::ready);
        // 起飞时服务器的 tick 数：对 autosave（每 6000 tick）用（codex-audit K8：驱动在等初始视野之前取的不准）
        this.startTick = player.getServer() == null ? -1 : player.getServer().getTickCount();
        out.initial(tracker.initialJsonl());
        out.runJson(config().toString());
        countersAtStart = Generation.listen(listener);
        SyncLoads.addListener(syncListener);
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
            leave();
            return;
        }
        long now = System.nanoTime();
        if (!started) lastEndNanos = now;
        started = true;
        tickStart = now;
        if (flight == null) {
            // 真人玩家：位置由客户端的移动包在这个 tick 里改，endTick 时和这里比
            prevX = p.getX();
            prevZ = p.getZ();
            return;
        }
        // 像客户端一样前进：这一 tick 该走的时间（墙钟模式按真实时间，tick 模式每 tick 1/20 秒），不越过没就绪的 chunk
        long target = flight.mode() == Flight.Mode.WALL ? Math.min(now - t0, (long) (flight.seconds() * 1e9))
                : Math.min(ticks + 1, (long) Math.ceil(flight.seconds() * 20)) * 50_000_000L;
        double dt = Math.max(0, target - movedUntil) / 1e9;
        double from = movedUntil / 1e9;
        movedUntil = Math.max(movedUntil, target);
        if (route != null) {
            routeTick(p, from, from + dt);
            return;
        }
        Flight.Step step = flight.advance(posX, posZ, dt, this::ready);
        stalledThisTick = step.stalled();
        if (step.stalled()) {
            stalledTicks++;
            double want = flight.blocksPerSecond() * dt;
            stallNanos += want > 0 ? dt * 1e9 * (1 - step.moved() / want) : 0;
        }
        posX = step.x();
        posZ = step.z();
        distance += step.moved();
        place(p, posX, posZ);
    }

    /** 路线飞行的一 tick：按段切开，每截像客户端一样前进；停下的段不算卡住，被挡住的截里没走的那部分时间算卡住。 */
    private void routeTick(ServerPlayer p, double from, double to) {
        stalledThisTick = false;
        for (Route.Piece piece : route.pieces(from, to)) {
            Flight leg = legs[piece.index()];
            if (leg == null) {
                continue;   // 停在原地的一段
            }
            Flight.Step step = leg.advance(posX, posZ, piece.seconds(), this::ready);
            if (step.stalled()) {
                stalledThisTick = true;
                double want = leg.blocksPerSecond() * piece.seconds();
                stallNanos += want > 0 ? piece.seconds() * 1e9 * (1 - step.moved() / want) : 0;
            }
            posX = step.x();
            posZ = step.z();
            distance += step.moved();
        }
        if (stalledThisTick) {
            stalledTicks++;
        }
        face(p, route.segments().get(route.segmentAt(to)).yaw());
        place(p, posX, posZ);
    }

    void endTick(MinecraftServer server) {
        if (finished || !started) return;
        ServerPlayer p = player(server);
        if (p == null) {
            leave();
            return;
        }
        long now = System.nanoTime();
        ticks++;
        if (flight == null) {
            double moved = Math.hypot(p.getX() - prevX, p.getZ() - prevZ);
            distance += moved;
            stalledThisTick = RealStall.stalled(moved, p.getX(), p.getZ(), p.getYRot(), this::ready);
            if (stalledThisTick) {
                stalledTicks++;
                stallNanos += now - lastEndNanos;   // 卡住的这个 tick 从上一个 tick 结束算起，整段都算卡住
            }
        }
        lastEndNanos = now;
        long elapsed = now - t0;
        endMs = elapsed / 1_000_000;
        long scan = System.nanoTime();
        ViewTracker.Snapshot s = tracker.tick(endMs, SectionPos.blockToSectionCoord(p.getX()), SectionPos.blockToSectionCoord(p.getZ()), this::ready);
        scanNanos += System.nanoTime() - scan;
        if (!ready(SectionPos.blockToSectionCoord(p.getX()), SectionPos.blockToSectionCoord(p.getZ()))) {
            unreadyTicks++;   // 不该发生：前进时已经挡住了。不为 0 说明模拟客户端的逻辑有漏洞
        }
        rows.tick(elapsed, now - tickStart, p.getX(), p.getZ(), s, stalledThisTick);
        if (flight != null && flight.finished(flight.elapsed(elapsed, ticks))) {
            finish(false, null);
        }
    }

    /** 玩家不在了：飞行算出错（aborted），跟踪真人玩家是正常结束（stopped_reason = left）。 */
    private void leave() {
        if (flight == null) {
            finishRecord("left");
        } else {
            finish(false, "player_left");
        }
    }

    /** 跟踪真人玩家的正常结束：command = /bench record stop，left = 玩家下线，server_stopping = 停服。 */
    void finishRecord(String reason) {
        stoppedReason = reason;
        finish(false, null);
    }

    boolean recording() {
        return flight == null;
    }

    /** 结束：写最后一秒和 done.json。stopped = 被 /bench fly stop 提前停下；aborted = 出错原因（没有就是 null）。 */
    void finish(boolean stopped, String aborted) {
        if (finished) return;
        finished = true;
        rows.finish();
        Generation.Snapshot end = Generation.unlisten(listener);
        SyncLoads.removeListener(syncListener);
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(playerId);
        JsonObject done = config();
        done.addProperty("end_x", p != null ? (Number) p.getX() : null);
        done.addProperty("end_z", p != null ? (Number) p.getZ() : null);
        done.addProperty("ticks", ticks);
        done.addProperty("rows", rows.written());
        done.addProperty("end_ms", endMs);
        // 第一个 tick 开始时的 System.nanoTime。Linux 上它就是 CLOCK_MONOTONIC，容器和宿主机共用，宿主机的 cgroup 采样靠它对齐
        done.addProperty("t0_nanotime", t0);
        // 像客户端一样前进（11-09）：实际距离、理想距离、卡在没就绪 chunk 前面的 tick 数和时长
        done.addProperty("distance", distance);
        done.addProperty("ideal_distance", route != null ? (Number) route.idealDistance()
                : flight != null ? (Number) (flight.blocksPerSecond() * flight.seconds()) : null);
        if (flight == null) {
            done.addProperty("seconds", endMs / 1000.0);   // 跟踪了多久（compose 当飞行时长用）
            done.addProperty("stopped_reason", stoppedReason);
        }
        done.addProperty("stalled_ticks", stalledTicks);
        done.addProperty("stall_ms", stallNanos / 1e6);
        done.addProperty("unready_ticks", unreadyTicks);
        JsonObject sync = syncJson(0), world = syncJson(1);
        sync.add("world", world);
        done.add("sync_loads", sync);
        // 监听期间收到的来源事件数，和同一段时间的计数增量（Generation 保证两者对得上：vanilla = generated，resumed = resumed，loaded = loaded）
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
        o.addProperty("distance", distance);
        o.addProperty("stalled_ticks", stalledTicks);
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
        o.addProperty("mode", route != null ? "route" : flight != null ? flight.mode().name().toLowerCase(java.util.Locale.ROOT) : "record");
        o.addProperty("route", route != null ? route.spec() : null);
        o.addProperty("speed", flight != null ? (Number) flight.blocksPerSecond() : null);
        o.addProperty("seconds", flight != null ? (Number) flight.seconds() : null);
        o.addProperty("y", startY);
        o.addProperty("start_x", startX);
        o.addProperty("start_z", startZ);
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
        o.addProperty("start_tick", startTick);
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

    private JsonObject syncJson(int i) {
        JsonObject o = new JsonObject();
        o.addProperty("count", syncCount[i]);
        o.addProperty("total_ms", syncTotalNanos[i] / 1e6);
        o.addProperty("max_ms", syncMaxNanos[i] / 1e6);
        o.addProperty("stack", syncMaxStack[i]);
        return o;
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
        p.teleportTo(level, x, startY, z, p.getYRot(), p.getXRot());
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance = 0;
        level.getChunkSource().move(p);
    }
}
