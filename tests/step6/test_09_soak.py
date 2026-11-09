"""第 6 步（6.5）：30 分钟浸泡测试（slow）。

- 打开找需求，2 个 donor；1 个旁观假玩家从 (0, 30000) 沿 +x 以 14 格/秒（骑马的速度）走 30 分钟。
  移动用第 4 步的飞行场景（bench.layer2.fly）：像客户端一样每 tick 前进，不越过没就绪的 chunk（11-09 起；
  原来用 rcon tp 每 0.5 秒跳 7 格，假玩家会跑到生成前面，在实体 tick 里同步生成，2 核时被看门狗杀掉）。
  守护计数 sync_loads.count 必须为 0，否则这次测量无效；卡住时长、实际距离记录。
- 跑完：存档里每个 chunk 都读得出来；抽 1% 的 chunk 在全新世界（同 seed）里单独生成作对照，
  按"导入的 tile / 服务器生成的"分开逐块比（analysis/mca/decor.py 两层）：严格层两边都是 0，
  导入的装饰类差异率、地形互换率各自不高于服务器生成的 1.5 倍（同 5.4）；记录指标。
- 会用 new_world.sh 把测试服务器换成全新世界做对照，所以放在第 6 步最后。
- 2 核再跑一次：MC_CPUS=2 MC_CPUSET=32-33 scripts/test.sh step6 --slow -k soak
"""

import json
import random
import time

import pytest

from analysis.mca import iter_chunk_positions, read_chunk
from analysis.mca.decor import Diff, compare_chunks, relative_check
from bench import layer2
from mcd import SCRIPTS, compose, donor_imports, run, wait_until, worker_id

START = (0, 30000)
SPEED = 14          # 格/秒
DURATION = 30 * 60  # 秒


@pytest.mark.slow
def test_soak_30_minutes(server, worker, worker2, tmp_path_factory):
    worker_id(server, "worker")
    worker_id(server, "worker2")
    before = len(donor_imports(server))
    base = tmp_path_factory.mktemp("soak")  # 存档快照（大），不放 artifacts
    flight_dir = server.artifact_dir or base
    server.rcon("bench reset")
    server.rcon("donor demand on")
    try:
        out = layer2.fly(server, run=f"soak-{time.time_ns()}", player="soakbot", start=START, speed=SPEED,
                         seconds=DURATION, out_dir=flight_dir / "soak-flight")
        status = json.loads(server.rcon("donor status"))
        stats = json.loads(server.rcon("bench stats"))
    finally:
        server.rcon("donor demand off")
    assert status["scan"]["count"] > 0, "找需求没开起来，测试在空跑"
    flight = json.loads((out / "summary.json").read_text())
    sync_loads = json.loads((out / "raw" / "done.json").read_text()).get("sync_loads") or {}
    assert "count" in sync_loads, "layer2 还是旧的（没有 sync_loads 守护计数）"
    assert sync_loads["count"] == 0, f"假玩家 tick 里同步加载过 chunk，测量无效：{sync_loads}"
    # 拍快照前先停掉 donor：找需求关了，但队列里排着的、donor 手上的任务还会陆续导入，
    # 拍快照时正好写 region 文件就会拷到一半（11-09 f2 两次：航线末端的 region 头部截断）。停掉后它们手上的任务回队列，不再导入
    compose("stop", "worker", "worker2", timeout=300)
    wait_until(lambda: not json.loads(server.rcon("donor status"))["workers"], timeout=60, interval=1, what="donor 断开")
    snap = server.snapshot(base / "soak")
    # 1. 每个 chunk 都读得出来（读不出会抛异常）
    positions = list(iter_chunk_positions(snap))
    full = [p for p in positions if read_chunk(snap, *p).status == "minecraft:full"]

    # 2. 抽 1%，在全新世界里单独生成作对照
    imported_tiles = {tuple(r["tile"]) for r in donor_imports(server)[before:] if r["accepted"]}
    imported = {(tx * 4 + dx, tz * 4 + dz) for tx, tz in imported_tiles for dx in range(4) for dz in range(4)}
    rng = random.Random(424242)
    sample = rng.sample(full, max(1, len(full) // 100))
    level = f"soak{int(time.time())}"
    run(str(SCRIPTS / "new_world.sh"), level, str(server.seed), timeout=900)
    server.level = level
    server.chunky_list(sample)
    control = server.snapshot(base / "control")
    acc = {"imported": Diff(examples=[]), "server": Diff(examples=[])}
    for p in sample:
        acc["imported" if p in imported else "server"].add(compare_chunks(read_chunk(snap, *p), read_chunk(control, *p)))
    rate = {k: ({"decor": v.decor_rate, "terrain": v.terrain_rate} if v.blocks else None) for k, v in acc.items()}
    strict = {k: v.strict for k, v in acc.items()}

    # 3. 记录
    resumed, generated = stats["full_resumed"], stats["full_generated"]
    report = {
        "chunks_on_disk": len(positions), "full_chunks": len(full),
        "donor_share": resumed / (resumed + generated) if resumed + generated else 0,
        "discard_ratio": status["discard_ratio"], "scan": status["scan"],
        "speed_blocks_per_s": SPEED,
        # 来晚了：tile 回来时区域已经落进服务器自己占着的范围，只能丢弃
        "late_ratio": status["discarded"]["late"] / max(1, status["accepted"] + sum(status["discarded"].values())),
        "discarded": status["discarded"],
        "imports_accepted": len(imported_tiles), "sample": len(sample), "decor_rate": rate, "strict": strict,
        "sync_loads": sync_loads,
        "flight": {k: v for k, v in flight.items() if "stall" in k or "stuck" in k or "distance" in k or k in
                   ("void_mean", "void_p95", "latency_p95_ms", "cpu_cores_mean", "mspt_mean", "mspt_max", "ticks_per_s")},
        "strict_examples": acc["imported"].examples + acc["server"].examples,
    }
    print(json.dumps(report, ensure_ascii=False))
    if server.artifact_dir:
        server.artifact_dir.mkdir(parents=True, exist_ok=True)
        (server.artifact_dir / "soak.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    assert all(v == 0 for v in strict.values()), report
    if acc["imported"].blocks and acc["server"].blocks:
        bad = relative_check(acc["imported"], acc["server"])
        assert not bad, (bad, rate)
