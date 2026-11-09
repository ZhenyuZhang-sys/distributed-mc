"""第 6 步（6.6，降级为冒烟）：鞘翅速度飞 120 秒，0 个 donor 和 2 个 donor 交替各两轮，只看方向对不对。

飞行用第 4 步的场景（bench.layer2.fly：旁观假玩家、等初始视野就绪、逐秒虚空率和 MSPT、逐 chunk 就绪延迟、
宿主机 cgroup 的服务器 CPU）。正式验收（5 次、置信区间）留到部署机器上，见 docs/step6-report.md 第 7 节。

- 0 个 donor：worker、worker2 容器**停掉**（不只是不连 coordinator：空跑的 JVM 也有 GC、存盘、磁盘读写，
  会干扰测量，第 1 步吃过这个亏），飞之前确认它们不在跑。
- 2 个 donor：两个 worker 起来、连上，打开找需求。
- 每轮在不同的、没生成过的地方飞；每轮记下当时在跑的容器。
- 判定只要求方向：2 个 donor 时服务器 CPU 比 0 个 donor 低（两轮均值），而且 donor 真的导入了 tile。
  虚空率、就绪延迟、卡住时长占比、实际/理想距离只记录（1～2 次的波动太大），后两项是主要指标（调度 11-09）。
- 测量有效的条件：新 layer2 的守护计数 sync_loads.count（假玩家 tick 里的同步加载）为 0。
  11-09 第一次跑时假玩家越过没生成的区域，2 核原版被看门狗杀掉，那次数据作废；sync_loads.world 是原版自己的，只记录。
跑法（2 核服务器，拿独占锁）：MC_CPUS=2 MC_CPUSET=32-33 scripts/test.sh step6 --slow -k smoke
"""

import json
import statistics

import pytest

from bench import layer2
from mcd import compose, donor_imports, wait_until, worker_id

SPEED = 30        # 格/秒，鞘翅
SECONDS = 120
# 交替跑，减少时间上的漂移；每轮一条没生成过的航线
ROUNDS = [("donors0", (0, -40000)), ("donors2", (0, 40000)), ("donors0", (40000, -40000)), ("donors2", (-40000, 40000))]


def running_services() -> list[str]:
    return sorted(s for s in compose("ps", "--format", "{{.Service}}").split() if s)


def donors_down():
    compose("stop", "worker", "worker2", timeout=300)
    wait_until(lambda: not ({"worker", "worker2"} & set(running_services())), timeout=60, interval=1,
               what="worker、worker2 都停了")


def donors_up(server):
    compose("up", "-d", "--wait", "--wait-timeout", "600", "worker", "worker2", timeout=660)
    worker_id(server, "worker")
    worker_id(server, "worker2")


@pytest.mark.slow
def test_elytra_smoke_0_vs_2_donors(server, worker, worker2, tmp_path_factory):
    base = server.artifact_dir or tmp_path_factory.mktemp("smoke")
    results = []
    try:
        for i, (kind, start) in enumerate(ROUNDS):
            before = len(donor_imports(server))
            if kind == "donors0":
                server.rcon("donor demand off")
                donors_down()
            else:
                donors_up(server)
                server.rcon("donor demand on")
            services = running_services()
            out = layer2.fly(server, run=f"smoke{i}", player=f"smoke{i}", start=start, speed=SPEED, seconds=SECONDS,
                             out_dir=base / f"smoke-{i}-{kind}")
            summary = json.loads((out / "summary.json").read_text())
            sync_loads = json.loads((out / "raw" / "done.json").read_text()).get("sync_loads") or {}
            status = json.loads(server.rcon("donor status"))
            server.rcon("donor demand off")
            row = {"round": i, "kind": kind, "services": services,
                   "imports_accepted": sum(1 for r in donor_imports(server)[before:] if r["accepted"]),
                   "discard_ratio": status["discard_ratio"], "discarded": status["discarded"], "sync_loads": sync_loads,
                   **{k: v for k, v in summary.items() if "stall" in k or "stuck" in k or "distance" in k},
                   **{k: summary[k] for k in ("cpu_cores_mean", "void_mean", "void_p95", "latency_p95_ms",
                                               "mspt_mean", "mspt_max", "ticks_per_s")}}
            results.append(row)
            print(json.dumps(row, ensure_ascii=False))
            assert "count" in sync_loads, "layer2 还是旧的（没有 sync_loads 守护计数）"
            assert sync_loads["count"] == 0, f"第 {i} 轮假玩家 tick 里同步加载过 chunk，测量无效：{sync_loads}"
            if kind == "donors0":
                assert not ({"worker", "worker2"} & set(services)), f"0 个 donor 那轮 worker 还在跑：{services}"
    finally:
        donors_up(server)  # 后面的测试和 fixture 收尾要 worker
    report = {"speed": SPEED, "seconds": SECONDS, "rounds": results, "config": json.loads(server.rcon("donor status"))["config"]}
    for kind in ("donors0", "donors2"):
        rows = [r for r in results if r["kind"] == kind]
        report[kind] = {k: statistics.fmean(r[k] for r in rows if r[k] is not None)
                        for k in ("cpu_cores_mean", "void_mean", "latency_p95_ms", "mspt_mean")}
    print(json.dumps(report, ensure_ascii=False))
    (base / "smoke.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    assert all(r["imports_accepted"] > 0 for r in results if r["kind"] == "donors2"), "2 个 donor 时没有导入 tile"
    assert report["donors2"]["cpu_cores_mean"] < report["donors0"]["cpu_cores_mean"], report
