"""第 5 步（5.4）：coordinator 和 worker 联网的端到端测试。

tile 之间隔开（间距 2 个 tile），外圈互不重叠：相邻 tile 的外圈冲突留到第 6 步处理。
按顺序跑：放 10 个 tile → 和原版比 → kill worker → netem → 重启 coordinator → worker 内存。
"""

import json

import pytest

from analysis.mca import read_chunk
from analysis.mca.decor import VANILLA_DECOR_FLOOR, VANILLA_DECOR_MAX, Diff, compare_chunks
from mcd import SCRIPTS, compose, run, wait_until, worker_id

# 跑一遍要几分钟，--quick 时不跑
pytestmark = pytest.mark.long

# 粗查：装饰类差异率超过原版最乱的区域（jungle）的 1.5 倍就算明显坏了
SANITY = VANILLA_DECOR_MAX * 1.5


def status(server) -> dict:
    return json.loads(server.rcon("donor status"))


def done(server) -> int:
    return status(server)["tasks"]["done"]


def enqueue(server, tiles):
    # 明确派给 worker 这个容器：第 6 步起还有 worker2，对照世界是 worker 的
    wid = worker_id(server, "worker")
    for tx, tz in tiles:
        out = server.rcon(f"donor assign {tx} {tz} {wid}")
        assert out.startswith("queued"), out


def wait_done(server, target, timeout=300):
    wait_until(lambda: done(server) >= target, timeout=timeout, interval=2, what=f"完成 {target} 个任务")


def written(server, tile) -> bool:
    tx, tz = tile
    return all(server.rcon(f"donor peek {tx * 4 + dx} {tz * 4 + dz}") != "none"
               for dx in (0, 3) for dz in (0, 3))


def wait_worker(server):
    return worker_id(server, "worker")


@pytest.fixture(scope="module")
def baseline(server, worker):
    wait_worker(server)
    return {"loaded": int(worker.rcon("donor loaded"))}


# ---- 放 10 个 tile，和原版比 ----

# 远离同一轮里其他测试生成过的区域（第 2 步计数测试在 chunk 296～363 一带生成过，
# 第一版放在 tile 80 一带和它重叠，导入时被判"已存在"跳过，污染了对照）
G0 = -120
GRID = [(G0 + 2 * i, G0 + 2 * j) for j in range(3) for i in range(4)][:10]


@pytest.fixture(scope="module")
def ten(server, worker, baseline):
    # 前提：这些 tile 在服务器存档里一个 chunk 都没有，导入的才全是 worker 生成的
    touched = [t for t in GRID if any(server.rcon(f"donor peek {t[0] * 4 + dx} {t[1] * 4 + dz}") != "none"
                                      for dx in range(-1, 5) for dz in range(-1, 5))]
    assert not touched, f"这些 tile 已经被别的测试生成过，换个位置：{touched}"
    before = done(server)
    enqueue(server, GRID)
    wait_done(server, before + 10)
    return before


def test_ten_tiles_written(server, ten):
    missing = [t for t in GRID if not written(server, t)]
    assert not missing, missing


def test_ten_tiles_match_vanilla(server, worker, ten, tmp_path_factory):
    # 和第 3 步关卡一样：服务器把这块区域做成成品，worker（同 seed 的另一个全新世界）
    # 也生成同一块作为原版答案，逐块比，分两层（analysis/mca/decor.py）：
    # - 严格层（装饰类方块以外）：导入的、没导入的都必须是 0。没导入的部分是两个原版世界互比，
    #   它不是 0 说明装饰类清单不全。
    # - 统计层：导入部分（tile 和边界一圈）的装饰类差异率不高于没导入部分（同一轮原版互比）的 1.5 倍。
    x0, z0 = G0 * 4 - 2, G0 * 4 - 2
    size = 6 * 4 + 4 + 4
    server.chunky_square(x0, z0, size)
    worker.chunky_square(x0, z0, size)
    base = tmp_path_factory.mktemp("e2e")
    a, b = server.snapshot(base / "server"), worker.snapshot(base / "worker")

    def category(x, z):
        if any(tx * 4 <= x <= tx * 4 + 3 and tz * 4 <= z <= tz * 4 + 3 for tx, tz in GRID):
            return "tile"
        if any(tx * 4 - 1 <= x <= tx * 4 + 4 and tz * 4 - 1 <= z <= tz * 4 + 4 for tx, tz in GRID):
            return "ring1"
        return "not_imported"

    acc = {k: Diff(examples=[]) for k in ("tile", "ring1", "not_imported")}
    center_diff = {}
    for x in range(x0, x0 + size):
        for z in range(z0, z0 + size):
            ca, cb = read_chunk(a, x, z), read_chunk(b, x, z)
            assert ca.status == cb.status == "minecraft:full", (x, z)
            d = compare_chunks(ca, cb)
            acc[category(x, z)].add(d)
            # 每个 tile 中间 2×2：邻居全在 tile 里，地物都在 worker 上做完了，
            # 导入后没有谁再往里写，必须和 worker 逐块一致（装饰类也算）
            if any(tx * 4 + 1 <= x <= tx * 4 + 2 and tz * 4 + 1 <= z <= tz * 4 + 2 for tx, tz in GRID) \
                    and (d.strict or d.decor):
                center_diff[f"{x},{z}"] = {"strict": d.strict, "decor": d.decor}
    imported = Diff(examples=[]).add(acc["tile"]).add(acc["ring1"])
    summary = {k: {"strict": v.strict, "decor_rate": v.decor_rate, "examples": v.examples} for k, v in acc.items()}
    print(f"严格差异 / 装饰类差异率：{ {k: (v.strict, f'{v.decor_rate:.4%}') for k, v in acc.items()} }")
    if server.artifact_dir:
        server.artifact_dir.mkdir(parents=True, exist_ok=True)
        (server.artifact_dir / "e2e-diff.json").write_text(
            json.dumps({"by_category": summary, "imported_decor_rate": imported.decor_rate,
                        "center_diff": center_diff}, indent=2) + "\n")
    assert not center_diff, f"tile 中间的 chunk 和 worker 不一致，导入丢了东西：{center_diff}"
    assert all(v.strict == 0 for v in acc.values()), summary
    assert all(v.decor_rate <= SANITY for v in acc.values()), summary
    assert imported.decor_rate <= max(acc["not_imported"].decor_rate * 1.5, VANILLA_DECOR_FLOOR), summary


# ---- kill worker ----

def test_kill_worker_mid_task(server, worker, ten):
    tiles = [(120 + 2 * i, 120) for i in range(3)]
    before = done(server)
    wid = worker_id(server, "worker")
    enqueue(server, tiles)
    # 等 worker 手上有任务再 kill
    wait_until(lambda: any(w["id"] == wid and w["task"] >= 0 for w in status(server)["workers"]),
               timeout=60, interval=0.5, what="worker 领到任务")
    compose("kill", "worker")
    try:
        # 15 秒没心跳就断开，它手上的任务回到队首（第 6 步起可能马上被 worker2 领走）
        wait_until(lambda: all(w["id"] != wid for w in status(server)["workers"]), timeout=30, interval=1,
                   what="coordinator 发现 worker 断了")
    finally:
        # 不管上面过没过，都把 worker 拉起来，不连累后面的测试
        compose("up", "-d", "--wait", "--wait-timeout", "300", "worker", timeout=360)
    wait_worker(server)
    wait_done(server, before + 3)
    missing = [t for t in tiles if not written(server, t)]
    assert not missing, missing


# ---- netem：100 ms 延迟、1% 丢包 ----

def test_ten_tiles_with_netem(server, worker, ten):
    tiles = [(140 + 2 * i, 140) for i in range(10)]
    before = done(server)
    run(str(SCRIPTS / "netem.sh"), "on", "100ms", "1%")
    try:
        enqueue(server, tiles)
        wait_done(server, before + 10, timeout=600)
    finally:
        run(str(SCRIPTS / "netem.sh"), "off")
    missing = [t for t in tiles if not written(server, t)]
    assert not missing, missing


# ---- 重启 coordinator：队列不持久化 ----

def test_coordinator_restart(server, worker, ten):
    first = [(160, 160), (162, 160)]
    second = [(164, 160), (166, 160)]
    before = done(server)
    enqueue(server, first)
    wait_done(server, before + 2)

    compose("stop", "minecraft", timeout=180)
    compose("up", "-d", "--wait", "--wait-timeout", "600", "minecraft", timeout=660)
    # worker 没崩，自动重连；队列是空的（第 5 步不持久化）
    assert worker.health() == "healthy"
    wait_worker(server)
    s = status(server)
    assert s["tasks"] == {"queued": 0, "leased": 0, "done": 0, "failed": 0, "dropped": 0}, s

    enqueue(server, second)
    wait_done(server, 2)
    missing = [t for t in first + second if not written(server, t)]
    assert not missing, f"重启前写进存档的和重启后新放的都要在：{missing}"


# ---- worker 做完任务后把区域放掉 ----

def test_worker_releases_chunks(worker, baseline, ten):
    # 做了 25 个 tile（每个连外圈 36 个 chunk）。内存里的 chunk 不能一直涨
    def settled():
        return int(worker.rcon("donor loaded")) <= baseline["loaded"] + 200

    wait_until(settled, timeout=120, interval=5, what="worker 放掉做完的区域")
