"""第 5 步（5.4）：coordinator 和 worker 联网的端到端测试。

tile 之间隔开（间距 2 个 tile），外圈互不重叠：相邻 tile 的外圈冲突留到第 6 步处理。
按顺序跑：放 10 个 tile → 和原版比 → kill worker → netem → 重启 coordinator → worker 内存。
"""

import json

import pytest

from analysis.mca import read_chunk
from mcd import SCRIPTS, compose, run, wait_until

# 粗查：任何一类差异超过 10% 就算明显坏了
SANITY = 0.10


def status(server) -> dict:
    return json.loads(server.rcon("donor status"))


def done(server) -> int:
    return status(server)["tasks"]["done"]


def enqueue(server, tiles):
    for tx, tz in tiles:
        out = server.rcon(f"donor enqueue {tx} {tz}")
        assert out.startswith("queued"), out


def wait_done(server, target, timeout=300):
    wait_until(lambda: done(server) >= target, timeout=timeout, interval=2, what=f"完成 {target} 个任务")


def written(server, tile) -> bool:
    tx, tz = tile
    return all(server.rcon(f"donor peek {tx * 4 + dx} {tz * 4 + dz}") != "none"
               for dx in (0, 3) for dz in (0, 3))


def wait_worker(server):
    return wait_until(lambda: status(server)["workers"], timeout=180, interval=2, what="worker 连上")


@pytest.fixture(scope="module")
def baseline(server, worker):
    wait_worker(server)
    return {"loaded": int(worker.rcon("donor loaded"))}


# ---- 放 10 个 tile，和原版比 ----

GRID = [(80 + 2 * i, 80 + 2 * j) for j in range(3) for i in range(4)][:10]


@pytest.fixture(scope="module")
def ten(server, worker, baseline):
    before = done(server)
    enqueue(server, GRID)
    wait_done(server, before + 10)
    return before


def test_ten_tiles_written(server, ten):
    missing = [t for t in GRID if not written(server, t)]
    assert not missing, missing


def test_ten_tiles_match_vanilla(server, worker, ten, tmp_path_factory):
    # 和第 3 步关卡一样：服务器把这块区域做成成品，worker（同 seed 的另一个全新世界）
    # 也生成同一块作为原版答案，逐列比。
    #
    # 不用固定阈值：第一次实测这片区域原版自己的噪声就有 3～4%（地下晶洞、地表树多，
    # 相邻 chunk 放地物的先后不同），个别 tile 到 10%。所以拿同一轮、同一片区域里
    # 导入的部分（tile 和边界一圈）和完全没导入的部分（两边各自从头生成）比。
    x0, z0 = 80 * 4 - 2, 80 * 4 - 2
    size = (86 - 80) * 4 + 4 + 4
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

    acc = {"tile": [0, 0], "ring1": [0, 0], "not_imported": [0, 0]}
    for x in range(x0, x0 + size):
        for z in range(z0, z0 + size):
            ca, cb = read_chunk(a, x, z), read_chunk(b, x, z)
            assert ca.status == cb.status == "minecraft:full", (x, z)
            c = acc[category(x, z)]
            c[0] += sum(p != q for p, q in zip(ca.column_hashes(), cb.column_hashes()))
            c[1] += 256
    rate = {k: v[0] / v[1] for k, v in acc.items()}
    imported = (acc["tile"][0] + acc["ring1"][0]) / (acc["tile"][1] + acc["ring1"][1])
    print(f"差异列比例：{rate}，导入部分合计 {imported:.2%}")
    if server.artifact_dir:
        server.artifact_dir.mkdir(parents=True, exist_ok=True)
        (server.artifact_dir / "e2e-diff.json").write_text(json.dumps({"rate": rate, "imported": imported}, indent=2) + "\n")
    assert all(v <= SANITY for v in rate.values()), rate
    assert imported <= max(rate["not_imported"] * 1.5, 0.005), (imported, rate)


# ---- kill worker ----

def test_kill_worker_mid_task(server, worker, ten):
    tiles = [(120 + 2 * i, 120) for i in range(3)]
    before = done(server)
    enqueue(server, tiles)
    # 等 worker 手上有任务再 kill
    wait_until(lambda: any(w["task"] >= 0 for w in status(server)["workers"]), timeout=60, interval=0.5,
               what="worker 领到任务")
    compose("kill", "worker")
    # 15 秒没心跳就断开，任务回到队首
    s = wait_until(lambda: (st := status(server))["workers"] == [] and st, timeout=30, interval=1,
                   what="coordinator 发现 worker 断了")
    assert s["tasks"]["leased"] == 0, s
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
    s = wait_worker(server) and status(server)
    assert s["tasks"] == {"queued": 0, "leased": 0, "done": 0, "failed": 0}, s

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
