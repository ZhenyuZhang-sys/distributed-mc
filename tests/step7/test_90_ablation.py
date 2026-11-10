"""第 7 步快速对比（docs/step7-plan.md 0.5，降级版）。slow，拿独占锁。

一次 test.sh 跑一组 JVM 配置（tile 大小、提前量要重启才生效，由 MCD_JVM_EXTRA 传）；同一组里按轮切换开关（运行时生效）。
每轮在没生成过的航线上用第 4 步的飞行场景（bench.layer2.fly）鞘翅飞 120 秒，2 个 donor，结束时从 /donor status
取 coordinator 的计数（按轮相减），连同场景汇总写一行，字段见计划 0.3。只判方向，不判显著：
测试本身只检查"测量有效"——每轮都导入了 tile、开关确实是这一轮要的、守护计数（主线程同步加载）为 0。

环境变量：
  MCD_ABLATION        这次跑哪些轮，逗号分隔，名字见 ROUNDS；默认见 DEFAULT。
                      slow- 开头的轮多起一个慢 donor（worker3，0.5 核），给 7.3 用：slow-v0 和 slow-throughput 比
  MCD_ABLATION_LABEL  这组 JVM 配置的名字（default、tile2、tile8、look16、look24），决定航线起点和结果文件名
结果：artifacts/<本轮>/ablation-<label>.json，scripts/ablation.sh 跑完几组后汇总。

跑法：scripts/ablation.sh（会依次用不同的 MCD_JVM_EXTRA 调 test.sh step7 --slow -k ablation）
"""

import bisect
import json
import os
import shutil
import time

import pytest

from bench import layer2
from mcd import SCRIPTS, compose, data_root, donor_imports, limits, run, wait_until, worker_id

ALL = ["territory", "deadline", "throughput", "hedge", "waitdonor"]
ROUNDS = {"v0": [], "all": ALL, **{f: [f] for f in ALL}, "slow-v0": [], "slow-throughput": ["throughput"],
          "slow-deadline": ["deadline"], "slow-territory-deadline": ["territory", "deadline"],
          "deploy": ["territory", "deadline", "throughput"], "vanilla": []}
# slow-deadline：第三个 donor 加 deadline。worker3 默认 0.5 核（慢 donor）；当成第三个正常 donor 用时
# 外面设 WORKER3_CPUS=4 WORKER3_CPUSET=<4 个核>（11-09 调度：2 个和 3 个 donor 各跑 2 轮）
# vanilla：原版对照（0 个 donor，worker 容器停掉，不找需求），同一组核、同样航线长度和时长（调度 11-09：
# 有效轮的卡住比例比 kexu 的原版基线高，要在同一批里看 donor 加进来是不是更差）
DEFAULT = "vanilla,v0,v0,territory,deadline,throughput,slow-v0,slow-throughput,hedge,waitdonor,all,vanilla,all"
LABELS = ["default", "tile2", "tile8", "look16", "look24"]
SPEED = 30
SECONDS = 120



def flight_guard(out) -> dict:
    """新 layer2 的守护计数（kexu 11-09）：done.json 的 sync_loads。
    count 是玩家实体 tick 里的同步加载，必须为 0，否则这一轮测量无效（假玩家跑到了生成前面）；
    world 是原版自己的（流体后处理等，真实服务器也有），只记录不判。旧 layer2 没有这一项时返回空。"""
    done = json.loads((out / "raw" / "done.json").read_text())
    return done.get("sync_loads") or {}


def donor_lead(out) -> dict:
    """donor 写入的 chunk，写入那一刻在玩家前方多远（沿飞行方向 +x，单位 chunk）：在前方 ≥ 10（一个视距）的比例、
    在身后（玩家已经飞过）的比例、中位数。来源是飞行记录的 provenance.jsonl（donor 事件）和逐秒玩家位置。"""
    raw = out / "raw"
    secs = [json.loads(l) for l in (raw / "seconds.jsonl").read_text().splitlines() if l.strip()]
    ts = [s["t_s"] * 1000 for s in secs]
    xs = [s["x"] / 16 for s in secs]
    leads = []
    path = raw / "provenance.jsonl"
    for line in (path.read_text().splitlines() if path.exists() else []):
        e = json.loads(line)
        if e["kind"] != "donor":
            continue
        i = min(bisect.bisect_left(ts, e["t_ms"]), len(xs) - 1)
        leads.append(e["cx"] - xs[i])
    if not leads:
        return {"donor_chunks": 0}
    leads.sort()
    n = len(leads)
    return {"donor_chunks": n, "ahead_10": sum(l >= 10 for l in leads) / n, "behind": sum(l < 0 for l in leads) / n,
            "median_lead": leads[n // 2]}


def main_metrics(summary: dict) -> dict:
    """主要指标：卡住时长占比、实际/理想距离（新 layer2，字段名含 stuck、distance），和虚空率、就绪延迟并列。"""
    keys = ("void_mean", "void_p95", "latency_p50_ms", "latency_p95_ms", "cpu_cores_mean", "mspt_mean", "mspt_max")
    return {k: v for k, v in summary.items() if k in keys or "stall" in k or "distance" in k}


def status(server) -> dict:
    return json.loads(server.rcon("donor status"))


def counters(s: dict) -> dict:
    return {
        "accepted": s["accepted"],
        "late": s["discarded"]["late"], "other_world": s["discarded"]["other_world"], "server": s["discarded"]["server"],
        "hedge_cancelled": s["hedge"]["cancelled"], "hedge_wasted_tiles": s["hedge"]["wasted"],
        "waitdonor_hits": s["waitdonor"]["hits"], "waitdonor_timeouts": s["waitdonor"]["timeouts"],
        "waitdonor_abandoned": s["waitdonor"]["abandoned"],
        "stale_dropped": s.get("stale", {}).get("dropped", 0), "stale_cancelled": s.get("stale", {}).get("cancelled", 0),
        # 11-09：区域已进服务器前沿、还没派出去就丢掉的（交回也会被整个丢弃）
        "import_late": s.get("stale", {}).get("import_late", 0),
        # 11-09：按实测吞吐算排队来不及、提前丢掉的
        "hopeless": s.get("stale", {}).get("hopeless", 0),
        "donor_tiles": {w["name"]: w["done"] for w in s["workers"]},
        # 主线程上导入用了多久（coordinator 11-09 加的计时；旧版没有就是 0）
        "import_ms": s.get("import", {}).get("totalMs", 0), "imports_timed": s.get("import", {}).get("count", 0),
    }


def delta(a: dict, b: dict) -> dict:
    out = {k: b[k] - a[k] for k in a if k != "donor_tiles"}
    out["donor_tiles"] = {n: d - a["donor_tiles"].get(n, 0) for n, d in b["donor_tiles"].items()}
    return out


def slow_donor(server, up: bool):
    """7.3：第三个 donor（worker3，0.5 核）起来并连上，或者停掉并确认不在跑。"""
    if up:
        compose("up", "-d", "--wait", "--wait-timeout", "600", "worker3", timeout=660)
        worker_id(server, "worker3")
    else:
        compose("stop", "worker3", timeout=300)
        wait_until(lambda: "worker3" not in compose("ps", "--format", "{{.Service}}").split(), timeout=60,
                   interval=1, what="worker3 停了")


def donors(server, up: bool, solo: bool = False):
    """vanilla 轮停掉全部 donor 并确认不在跑；其他轮把 worker、worker2 起来并连上。
    solo（"one-" 开头的轮，11-09 用户定先 1 个 donor）：只要 worker，worker2 停掉。"""
    if up and solo:
        compose("stop", "worker2", timeout=300)
        wait_until(lambda: "worker2" not in compose("ps", "--format", "{{.Service}}").split(), timeout=60, interval=1,
                   what="worker2 停了")
        compose("up", "-d", "--wait", "--wait-timeout", "600", "worker", timeout=660)
        worker_id(server, "worker")
    elif up:
        compose("up", "-d", "--wait", "--wait-timeout", "600", "worker", "worker2", timeout=660)
        worker_id(server, "worker")
        worker_id(server, "worker2")
    else:
        compose("stop", "worker", "worker2", "worker3", timeout=300)
        wait_until(lambda: not ({"worker", "worker2", "worker3"} & set(compose("ps", "--format", "{{.Service}}").split())),
                   timeout=60, interval=1, what="donor 都停了")


# "有网络"的条件（调度 11-09，按用户说的网络延迟约 70 ms）：往返 70 ms（每个方向 35 ms），donor 上传限 20 Mbps，丢包 1%。
# 轮名后面加 "+net"（比如 deadline+net）就在这一轮加上：每个 donor 出站 35 ms、1%、20mbit，服务器出站 35 ms（不限速、不丢包）。
NET_DONOR = ("35ms", "1%", "20mbit")
NET_SERVER = ("35ms", "0%")


def base_name(name: str) -> str:
    return name.removesuffix("+net").removeprefix("one-")


def netem(svc: str, *args: str):
    env = os.environ.copy()
    try:
        os.environ["NETEM_SERVICE"] = svc
        run(str(SCRIPTS / "netem.sh"), *args, timeout=120)
    finally:
        os.environ.clear()
        os.environ.update(env)


def net_on(slow: bool):
    for svc in ("worker", "worker2", *(["worker3"] if slow else [])):
        netem(svc, "on", *NET_DONOR)
    netem("minecraft", "on", *NET_SERVER)


def net_off():
    for svc in ("worker", "worker2", "worker3", "minecraft"):
        try:
            netem(svc, "off")
        except RuntimeError:
            pass  # 没在跑的服务


def spread(values: list) -> dict:
    v = sorted(x for x in values if x is not None and x >= 0)
    if not v:
        return {"n": 0}
    return {"n": len(v), "p50": v[len(v) // 2], "p90": v[int(len(v) * 0.9)], "max": v[-1]}


SEG_KEYS = ["queue", "to_worker", "wait", "gen", "pack", "back", "main_wait", "import"]


def segments(imports: list) -> dict:
    """分段耗时的分布（coordinator 11-09 起在 /donor imports 的 seg），被接受的和被丢的分开：
    时间花在 coordinator 队列、worker 上排队、生成、回传还是等主线程。"""
    out = {}
    for kind, rows in (("accepted", [r for r in imports if r.get("accepted")]),
                       ("discarded", [r for r in imports if not r.get("accepted")])):
        segs = [r["seg"] for r in rows if r.get("seg")]
        out[kind] = {k: spread([g.get(k) for g in segs]) for k in SEG_KEYS}
        # 11-09：排队时间按"派出时有没有截止时间"分开（slack_ms 是 -1 的没有 deadline：侧面、玩家不朝那边走的）
        out[kind]["queue_with_deadline"] = spread([r["seg"].get("queue") for r in rows if r.get("seg") and r.get("slack_ms", -1) >= 0])
        out[kind]["queue_no_deadline"] = spread([r["seg"].get("queue") for r in rows if r.get("seg") and r.get("slack_ms", -1) < 0])
        out[kind]["slack_ms"] = spread([r.get("slack_ms") for r in rows])
        # 11-09：主线程导入的三段（毫秒）
        for k in ("parse", "check", "write"):
            out[kind][f"imp_{k}"] = spread([r["imp"][k] for r in rows if r.get("imp")])
    return out


def set_features(server, on: list[str]):
    for f in ALL:
        server.rcon(f"donor feature {f} {'on' if f in on else 'off'}")
    assert sorted(status(server)["features"]) == sorted(on)


@pytest.mark.slow
def test_ablation_rounds(server, worker, worker2, tmp_path_factory):
    label = os.environ.get("MCD_ABLATION_LABEL", "default")
    assert label in LABELS, f"MCD_ABLATION_LABEL 要是 {LABELS} 之一"
    names = os.environ.get("MCD_ABLATION", DEFAULT).split(",")
    unknown = [n for n in names if base_name(n) not in ROUNDS]
    assert not unknown, f"不认识的轮：{unknown}"
    base = server.artifact_dir or tmp_path_factory.mktemp("ablation")
    worker_id(server, "worker")
    worker_id(server, "worker2")
    s0 = status(server)
    cfg = s0["config"]
    lim = limits()
    rows = []
    try:
        for i, name in enumerate(names):
            # 每组配置、每轮一条没生成过的航线：z 方向隔 8000 格，组和组之间隔 100000 格
            start = (0, -200_000 + 100_000 * LABELS.index(label) + 8000 * i)
            slow = name.startswith("slow-")
            vanilla = name == "vanilla"
            net = name.endswith("+net")
            solo = name.startswith("one-")
            donors(server, not vanilla, solo)
            slow_donor(server, slow)
            set_features(server, ROUNDS[base_name(name)])
            before_imports = len(donor_imports(server))
            before = counters(status(server))
            running = sorted(compose("ps", "--format", "{{.Service}}").split())
            # 来源事件的记录（11-09：几轮有导入、飞行记录里没有 donor 事件，查是没报还是报在了飞行窗口外）：
            # 飞前飞后各抓一次 coordinator 的累计计数和最近 64 条（带时间戳），连同飞行窗口的墙钟时间存进这一轮的目录
            # 每轮开始前清掉上一轮积压的任务、在租的任务和内存里的归属（kexu 11-09：上一轮的积压带进了下一轮）
            reset = server.rcon("donor reset")
            assert reset.startswith("reset"), reset
            ids_before = {w["name"]: w["id"] for w in status(server)["workers"]}
            prov_before = json.loads(server.rcon("donor provenance stats"))
            fly_start_ms = int(time.time() * 1000)
            if not vanilla:
                server.rcon("donor demand on")
            try:
                if net:
                    net_on(slow)
                out = layer2.fly(server, run=f"abl-{label}-{i}", player=f"abl{i}", start=start, speed=SPEED,
                                 seconds=SECONDS, out_dir=base / f"ablation-{label}-{i}-{name}")
            finally:
                if net:
                    net_off()
            fly_end_ms = int(time.time() * 1000)
            server.rcon("donor demand off")
            ids_after = {w["name"]: w["id"] for w in status(server)["workers"]}
            # worker 的崩溃报告（看门狗杀掉时有）存进这一轮的目录
            for svc in ("worker", "worker2", "worker3"):
                crash = data_root(svc) / "crash-reports"
                if crash.is_dir():
                    for f in crash.glob("*.txt"):
                        if f.stat().st_mtime * 1000 >= fly_start_ms - 600_000:
                            (out / f"crash-{svc}").mkdir(exist_ok=True)
                            shutil.copy2(f, out / f"crash-{svc}" / f.name)
            prov_after = json.loads(server.rcon("donor provenance stats"))
            (out / "provenance-coordinator.json").write_text(json.dumps({
                "fly_start_ms": fly_start_ms, "fly_end_ms": fly_end_ms, "before": prov_before, "after": prov_after,
                "recent": json.loads(server.rcon("donor provenance"))}, indent=1) + "\n")
            summary = json.loads((out / "summary.json").read_text())
            d = delta(before, counters(status(server)))
            imports = donor_imports(server)[before_imports:]
            n = cfg["tileSize"]
            row = {
                "label": label, "round": i, "name": name, "start": start, "services": running,
                "coordinator_features": ROUNDS[base_name(name)], "demand_lookahead": cfg["demandLookahead"], "tile_size": n,
                "network": {"donor": NET_DONOR, "server": NET_SERVER} if net else None,
                # 每个结果的大小（NBT 字节）和派出到收到的毫秒数的分布（coordinator 11-09 起记在 /donor imports）
                "result_bytes": spread([r.get("bytes") for r in imports]), "result_ms": spread([r.get("ms") for r in imports]),
                # 线上字节（gzip 后的帧，限速卡的是它）和按 20 Mbps 估的传输毫秒数，和实测的派出到收到对照
                "wire_bytes": spread([r.get("wire_bytes") for r in imports]),
                "seg": segments(imports),
                "wire_ms_at_20mbps": spread([r["wire_bytes"] * 8 / 20_000 for r in imports if r.get("wire_bytes", -1) >= 0]),
                "donors": [{"name": s, "cpuset": lim.get(f"{s.upper()}_CPUSET"),
                            "cpus": float(lim.get("WORKER3_CPUS", 0.5)) if s == "worker3" else None}
                           for s in ([] if vanilla else ["worker", *([] if solo else ["worker2"]), *(["worker3"] if slow else [])])],
                "server_cpus": lim.get("MC_CPUS"), "server_cpuset": lim.get("MC_CPUSET"),
                "bots": 1, "speed": "elytra",
                "imports_accepted": sum(1 for r in imports if r["accepted"]),
                "imports_discarded": sum(1 for r in imports if not r["accepted"]),
                "discard_ratio": (d["late"] + d["other_world"] + d["server"]) / max(1, len(imports)),
                "discarded": {k: d[k] for k in ("late", "other_world", "server")},
                "ring_overhead": (n + 2) ** 2 / n ** 2 - 1,
                **{k: d[k] for k in ("hedge_cancelled", "hedge_wasted_tiles", "waitdonor_hits", "waitdonor_timeouts",
                                     "waitdonor_abandoned", "donor_tiles", "import_ms", "imports_timed",
                                     "stale_dropped", "stale_cancelled", "import_late", "hopeless")},
                "main": main_metrics(summary), "sync_loads": flight_guard(out), "lead": donor_lead(out),
                # coordinator 这一轮报出的 donor / rejected 事件数（和飞行记录里的对照）
                "coordinator_reports": {k: prov_after.get(k, {}).get("count", 0) - prov_before.get(k, {}).get("count", 0)
                                        for k in ("donor", "rejected")},
                "summary": summary,
            }
            # 无效的轮记下来接着跑别的轮（最后判失败），不浪费整批：
            # 假玩家 tick 里同步加载过；donor 中途重启过（worker 编号变了，kexu 11-09：被看门狗杀掉又连回来）；
            # 有 donor 的轮飞行窗口里没有 donor 事件（done.json 的 provenance 只算飞行期间，imports_accepted 从起飞前就开始算，不能用）
            done = json.loads((out / "raw" / "done.json").read_text())
            row["donor_events_in_flight"] = done.get("provenance", {}).get("donor", 0)
            row["worker_ids"] = {"before": ids_before, "after": ids_after}
            row["valid"] = (row["sync_loads"].get("count", 0) == 0
                            and (vanilla or (row["donor_events_in_flight"] > 0 and ids_after == ids_before)))
            rows.append(row)
            print(json.dumps({k: v for k, v in row.items() if k != "summary"}, ensure_ascii=False))
    finally:
        server.rcon("donor demand off")
        set_features(server, [])
        slow_donor(server, False)
        donors(server, True)  # 后面的 fixture 收尾要 worker
        (base / f"ablation-{label}.json").write_text(json.dumps({"config": cfg, "rounds": rows}, ensure_ascii=False,
                                                                indent=2) + "\n")
    assert len(rows) == len(names)
    for r in rows:
        if r["name"] == "vanilla":
            assert not ({"worker", "worker2", "worker3"} & set(r["services"])), f"原版对照那轮 donor 还在跑：{r['services']}"
            continue
        assert r["worker_ids"]["before"] == r["worker_ids"]["after"], f"{r['name']} 这一轮 donor 中途重启过：{r['worker_ids']}"
        assert r["donor_events_in_flight"] > 0, f"{r['name']} 飞行期间没有 donor 事件，测量无效"
        assert "count" in r["sync_loads"], "layer2 还是旧的（没有 sync_loads 守护计数），不能做第 7 步对比"
        assert r["sync_loads"]["count"] == 0, f"{r['name']} 假玩家 tick 里同步加载过 chunk，测量无效，要重跑：{r['sync_loads']}"
