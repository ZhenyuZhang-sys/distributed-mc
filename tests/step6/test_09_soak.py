"""第 6 步（6.5）：30 分钟浸泡测试（slow）。

- 打开找需求，2 个 donor；1 个旁观假玩家从 (0, 30000) 沿 +x 每 0.5 秒前进 7 格（14 格/秒，骑马的速度），走 30 分钟。
- 跑完：存档里每个 chunk 都读得出来；抽 1% 的 chunk 在全新世界（同 seed）里单独生成作对照，
  按"导入的 tile / 服务器生成的"分开比，导入的不高于服务器生成的（同 5.4 的相对判断）；记录指标。
- 会用 new_world.sh 把测试服务器换成全新世界做对照，所以放在第 6 步最后。
- 2 核再跑一次：MC_CPUS=2 MC_CPUSET=32-33 scripts/test.sh step6 --slow -k soak
"""

import json
import random
import time

import pytest

from analysis.mca import iter_chunk_positions, read_chunk
from mcd import SCRIPTS, donor_imports, run, worker_id

START = (0, 30000)
SPEED = 14          # 格/秒
DURATION = 30 * 60  # 秒
STEP_S = 0.5


@pytest.mark.slow
def test_soak_30_minutes(server, worker, worker2, tmp_path_factory):
    worker_id(server, "worker")
    worker_id(server, "worker2")
    before = len(donor_imports(server))
    server.rcon("bench reset")
    server.rcon("donor demand on")
    server.rcon(f"player soakbot spawn at {START[0]} 200 {START[1]} facing 0 0 in minecraft:overworld in spectator")
    x = START[0]
    try:
        t0 = time.monotonic()
        while time.monotonic() - t0 < DURATION:
            x += SPEED * STEP_S
            server.rcon(f"tp soakbot {x:.1f} 200 {START[1]}")
            time.sleep(STEP_S)  # 移动节奏，不是等条件
        status = json.loads(server.rcon("donor status"))
        stats = json.loads(server.rcon("bench stats"))
    finally:
        server.rcon("donor demand off")
        server.rcon("player soakbot kill")
    assert status["scan"]["count"] > 0, "找需求没开起来，测试在空跑"

    base = tmp_path_factory.mktemp("soak")
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
    acc = {"imported": [0, 0], "server": [0, 0]}
    for p in sample:
        a, b = read_chunk(snap, *p), read_chunk(control, *p)
        c = acc["imported" if p in imported else "server"]
        c[0] += sum(u != v for u, v in zip(a.column_hashes(), b.column_hashes()))
        c[1] += 256
    rate = {k: (v[0] / v[1] if v[1] else None) for k, v in acc.items()}

    # 3. 记录
    resumed, generated = stats["full_resumed"], stats["full_generated"]
    report = {
        "chunks_on_disk": len(positions), "full_chunks": len(full),
        "donor_share": resumed / (resumed + generated) if resumed + generated else 0,
        "discard_ratio": status["discard_ratio"], "scan": status["scan"],
        "imports_accepted": len(imported_tiles), "sample": len(sample), "diff_rate": rate,
    }
    print(json.dumps(report, ensure_ascii=False))
    if server.artifact_dir:
        server.artifact_dir.mkdir(parents=True, exist_ok=True)
        (server.artifact_dir / "soak.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    if rate["imported"] is not None and rate["server"] is not None:
        assert rate["imported"] <= max(rate["server"] * 1.5, 0.005), rate
