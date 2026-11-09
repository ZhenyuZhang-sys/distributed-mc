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

import json
import os

import pytest

from bench import layer2
from mcd import compose, donor_imports, limits, wait_until, worker_id

ALL = ["territory", "deadline", "throughput", "hedge", "waitdonor"]
ROUNDS = {"v0": [], "all": ALL, **{f: [f] for f in ALL}, "slow-v0": [], "slow-throughput": ["throughput"]}
DEFAULT = "v0,v0,territory,deadline,throughput,slow-v0,slow-throughput,hedge,waitdonor,all,all"
LABELS = ["default", "tile2", "tile8", "look16", "look24"]
SPEED = 30
SECONDS = 120



def flight_guard(out) -> dict:
    """新 layer2 的守护计数（kexu 11-09）：done.json 的 sync_loads。
    count 是玩家实体 tick 里的同步加载，必须为 0，否则这一轮测量无效（假玩家跑到了生成前面）；
    world 是原版自己的（流体后处理等，真实服务器也有），只记录不判。旧 layer2 没有这一项时返回空。"""
    done = json.loads((out / "raw" / "done.json").read_text())
    return done.get("sync_loads") or {}


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
        "donor_tiles": {w["name"]: w["done"] for w in s["workers"]},
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


def set_features(server, on: list[str]):
    for f in ALL:
        server.rcon(f"donor feature {f} {'on' if f in on else 'off'}")
    assert sorted(status(server)["features"]) == sorted(on)


@pytest.mark.slow
def test_ablation_rounds(server, worker, worker2, tmp_path_factory):
    label = os.environ.get("MCD_ABLATION_LABEL", "default")
    assert label in LABELS, f"MCD_ABLATION_LABEL 要是 {LABELS} 之一"
    names = os.environ.get("MCD_ABLATION", DEFAULT).split(",")
    unknown = [n for n in names if n not in ROUNDS]
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
            slow_donor(server, slow)
            set_features(server, ROUNDS[name])
            before_imports = len(donor_imports(server))
            before = counters(status(server))
            server.rcon("donor demand on")
            out = layer2.fly(server, run=f"abl-{label}-{i}", player=f"abl{i}", start=start, speed=SPEED,
                             seconds=SECONDS, out_dir=base / f"ablation-{label}-{i}-{name}")
            server.rcon("donor demand off")
            summary = json.loads((out / "summary.json").read_text())
            d = delta(before, counters(status(server)))
            imports = donor_imports(server)[before_imports:]
            n = cfg["tileSize"]
            row = {
                "label": label, "round": i, "name": name, "start": start,
                "coordinator_features": ROUNDS[name], "demand_lookahead": cfg["demandLookahead"], "tile_size": n,
                "donors": [{"name": s, "cpuset": lim.get(f"{s.upper()}_CPUSET"),
                            "cpus": float(lim.get("WORKER3_CPUS", 0.5)) if s == "worker3" else None}
                           for s in ("worker", "worker2", *(["worker3"] if slow else []))],
                "server_cpus": lim.get("MC_CPUS"), "server_cpuset": lim.get("MC_CPUSET"),
                "bots": 1, "speed": "elytra",
                "imports_accepted": sum(1 for r in imports if r["accepted"]),
                "imports_discarded": sum(1 for r in imports if not r["accepted"]),
                "discard_ratio": (d["late"] + d["other_world"] + d["server"]) / max(1, len(imports)),
                "discarded": {k: d[k] for k in ("late", "other_world", "server")},
                "ring_overhead": (n + 2) ** 2 / n ** 2 - 1,
                **{k: d[k] for k in ("hedge_cancelled", "hedge_wasted_tiles", "waitdonor_hits", "waitdonor_timeouts",
                                     "waitdonor_abandoned", "donor_tiles")},
                "main": main_metrics(summary), "sync_loads": flight_guard(out),
                "summary": summary,
            }
            rows.append(row)
            print(json.dumps({k: v for k, v in row.items() if k != "summary"}, ensure_ascii=False))
            assert row["sync_loads"].get("count", 0) == 0, f"{name} 测量无效（假玩家 tick 里同步加载），停下来查"
    finally:
        server.rcon("donor demand off")
        set_features(server, [])
        slow_donor(server, False)
        (base / f"ablation-{label}.json").write_text(json.dumps({"config": cfg, "rounds": rows}, ensure_ascii=False,
                                                                indent=2) + "\n")
    assert len(rows) == len(names)
    for r in rows:
        assert r["imports_accepted"] > 0, f"{r['name']} 这一轮 donor 没导入，测量无效"
        assert "count" in r["sync_loads"], "layer2 还是旧的（没有 sync_loads 守护计数），不能做第 7 步对比"
        assert r["sync_loads"]["count"] == 0, f"{r['name']} 假玩家 tick 里同步加载过 chunk，测量无效，要重跑：{r['sync_loads']}"
