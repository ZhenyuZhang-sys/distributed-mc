"""第 6 步（6.5）：30 分钟浸泡测试（slow）。

- 打开找需求，2 个 donor；1 个旁观假玩家从 (0, 30000) 沿 +x 以 14 格/秒（骑马的速度）走 30 分钟。
  移动用第 4 步的飞行场景（bench.layer2.fly）：像客户端一样每 tick 前进，不越过没就绪的 chunk（11-09 起；
  原来用 rcon tp 每 0.5 秒跳 7 格，假玩家会跑到生成前面，在实体 tick 里同步生成，2 核时被看门狗杀掉）。
  守护计数 sync_loads.count 必须为 0，否则这次测量无效；卡住时长、实际距离记录。
- 跑完：存档里每个 chunk 都读得出来；在全新世界（同 seed）里单独生成对照，逐块比（analysis/mca/decor.py 两层）。
  两组分开抽（11-09 调度看过数据后改，见 docs/step6-report.md 第 6 节）：
  - 导入的：被接受的 tile 中间 2×2（邻居都在 tile 里，地物在 donor 上做完）、而且服务器还没把它做成成品（没被玩过、没被 tick 过）的，
    最多 64 个。严格层必须是 0。一个都抽不到算测量无效（原来按全部成品 chunk 抽 1%，导入的一个都没抽到，这一组是空的）。
  - 服务器生成的成品 chunk 抽 1%：只做统计层（导入的装饰类、地形互换率不高于它的 1.5 倍），严格层只记录不判——
    被玩家附近 tick 了 30 分钟的世界和刚生成的对照，在流体、气泡柱上本来就不一样（11-09 f2：920 个方块全是这类），和 donor 无关。
- 会用 new_world.sh 把测试服务器换成全新世界做对照，所以放在第 6 步最后。
- 2 核再跑一次：MC_CPUS=2 MC_CPUSET=32-33 scripts/test.sh step6 --slow -k soak
"""

import json
import os
import random
import shutil
import time
from pathlib import Path

import pytest

from analysis.mca import iter_chunk_positions, read_chunk
from analysis.mca.errors import CorruptRegionError
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
    # 停服再拷（调度 11-09）：save-off 只停自动存盘，玩家（假玩家飞完被移走）周围的 chunk 卸载时照样写 region，
    # 拍快照正好拷到写了一半的文件——两次截断都在航线末端，正是飞完以后卸载的地方。停服以后存档是一致的；
    # 这时还读不出来，就是存档真坏了。后面要换新世界做对照，用不着这台服务器了。
    imported_tiles = {tuple(r["tile"]) for r in donor_imports(server)[before:] if r["accepted"]}  # 停服前取
    if server.artifact_dir:
        # 存下来：快照还在、判法改了时能离线重算（11-09 那次没存，世界目录又被下一轮删了，只能重跑）
        server.artifact_dir.mkdir(parents=True, exist_ok=True)
        (server.artifact_dir / "soak-imported-tiles.json").write_text(json.dumps(sorted(imported_tiles)) + "\n")
    compose("stop", "minecraft", timeout=300)
    snap = server._copy_world(base / "soak")
    # 1. 每个 chunk 都读得出来（读不出会抛异常）
    # 11-09 f2 停了 donor 再拍快照，航线末端的 region 头部照样截断过两次：读不出来时把快照里的和服务器存档里的同一个 region
    # 都存进证据目录（pytest 的临时目录会被清掉），分清是拷到一半还是存档真坏了
    try:
        positions = list(iter_chunk_positions(snap))
        full = [p for p in positions if read_chunk(snap, *p).status == "minecraft:full"]
    except CorruptRegionError as e:
        bad = Path(str(e).split(":")[0])
        keep = (server.artifact_dir or base) / "corrupt-region"
        keep.mkdir(parents=True, exist_ok=True)
        shutil.copy2(bad, keep / f"snapshot-{bad.name}")
        live = server.world_dir / "region" / bad.name
        if live.exists():
            shutil.copy2(live, keep / f"live-{bad.name}")
        raise

    # 2. 抽样，在全新世界里单独生成作对照（两组分开抽，见开头）
    imported = {(tx * 4 + dx, tz * 4 + dz) for tx, tz in imported_tiles for dx in range(4) for dz in range(4)}
    centers = sorted({(tx * 4 + dx, tz * 4 + dz) for tx, tz in imported_tiles for dx in (1, 2) for dz in (1, 2)})
    untouched = [p for p in centers if (c := read_chunk(snap, *p)) is not None and c.status != "minecraft:full"]
    rng = random.Random(424242)
    imported_sample = rng.sample(untouched, min(64, len(untouched)))
    server_pool = [p for p in full if p not in imported]
    sample = imported_sample + rng.sample(server_pool, max(1, len(server_pool) // 100))
    level = f"soak{int(time.time())}"
    # 对照世界：这台服务器关看门狗（只生成对照、不测性能）。11-09 f3 两次：抽样的几千个分散 chunk 存盘时一个 tick 超过 60 秒，
    # 原版看门狗杀了服务器；改成 save-off 再停服，停服时的存盘又超过 docker 的 2 分钟宽限期，对照缺 chunk
    saved = os.environ.get("MC_MAX_TICK_TIME")
    os.environ["MC_MAX_TICK_TIME"] = "-1"
    try:
        run(str(SCRIPTS / "new_world.sh"), level, str(server.seed), timeout=900)
    finally:
        if saved is None:
            os.environ.pop("MC_MAX_TICK_TIME", None)
        else:
            os.environ["MC_MAX_TICK_TIME"] = saved
    server.level = level
    server.chunky_list(sample)
    control = server.snapshot(base / "control")
    acc = {"imported": Diff(examples=[]), "server": Diff(examples=[])}
    missing = [p for p in sample if read_chunk(control, *p) is None]
    assert not missing, f"对照世界里缺 {len(missing)} 个抽样的 chunk（没存盘？），比不了：{missing[:10]}"
    for p in sample:
        # 导入的抽的是没做成成品的（initialize_light），读取器默认只解成品的方块，要 incomplete=True（11-09 f3：不加时读成全是空气）
        acc["imported" if p in imported else "server"].add(
            compare_chunks(read_chunk(snap, *p, incomplete=p in imported), read_chunk(control, *p)))
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
        "imported_centers": len(centers), "imported_untouched": len(untouched), "imported_sample": len(imported_sample),
    }
    print(json.dumps(report, ensure_ascii=False))
    if server.artifact_dir:
        server.artifact_dir.mkdir(parents=True, exist_ok=True)
        (server.artifact_dir / "soak.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    assert imported_sample, f"没抽到可比的导入 chunk（被接受的 tile {len(imported_tiles)} 个），这次测量无效：{report}"
    # 导入的：严格层 0。服务器生成的：严格层只记录（被 tick 过的世界，见开头）
    assert strict["imported"] == 0, report
    # 超过上限的大团只看导入那一侧（服务器那一侧被 tick 过 30 分钟，流水、岩浆冲出的大团和 donor 无关；11-09 f3 实测 4 处全在服务器一侧）
    bad = relative_check(acc["imported"], acc["server"], big_only_inside=True)
    assert not bad, (bad, rate)
