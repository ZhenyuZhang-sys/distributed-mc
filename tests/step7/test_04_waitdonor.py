"""第 7 步 7.6a：等 donor 的原型（docs/step7-waitdonor-notes.md、docs/step7-plan.md 7.6）。

一个 tile 正租给 donor 时，服务器第一次要这个 tile 区域的 chunk：先等 donor 的结果，读到的是 donor 的数据，
服务器自己不跑 noise、features。

场景按飞行来：假玩家先在离 tile 32 个 chunk 的地方站稳（那里加载好），派出 tile，再往前挪 9 个 chunk
（新位置原来就在视距里，已经加载好，不会同步卡）。这时 tile 进入服务器的依赖范围（视距 + 约 11 圈，
低阶段还要更远一点；起点 25 圈时派任务前就有 tile 区域的 chunk 开始加载，导入被整个丢弃），开始异步加载，碰到闸门。不直接把假玩家放在 tile 上：出生点那个 chunk 是主线程同步加载的，
按设计会放弃闸门（主线程同步要 chunk 时不能等 donor，原型第一版这样做时 rcon 卡了 2.9 秒）。

1. donor 正常：闸门等到结果，tile 的 chunk 是 donor 的（服务器上 noise、features 都是 0）。
2. 主线程同步要一个正租着的 chunk：不等，主线程不卡，闸门被放弃（abandoned）。
3. donor 卡住（worker 主线程睡 6 秒）：最多等 2 秒就超时，原版照常生成；整个过程 rcon 一直有回应。
"""

import json
import threading
import time

import pytest

from mcd import donor_imports, wait_until, worker_id

pytestmark = pytest.mark.long

N = 4  # 默认 tile 大小


def status(server):
    return json.loads(server.rcon("donor status"))


def chunk_runs(server, x, z):
    return json.loads(server.rcon(f"bench chunk {x} {z}"))


def tile_chunks(t):
    return [(t[0] * N + dx, t[1] * N + dz) for dx in range(N) for dz in range(N)]


def view(server, name):
    """这个假玩家的视野；还没出现（生成是异步的）返回 None。"""
    return next((p for p in json.loads(server.rcon("bench view"))["players"] if p["name"].lower() == name.lower()), None)


def assign_and_wait_leased(server, tile, wid):
    before = status(server)["tasks"]["leased"]
    assert server.rcon(f"donor assign {tile[0]} {tile[1]} {wid}") == "queued"
    wait_until(lambda: status(server)["tasks"]["leased"] > before, timeout=10, interval=0.05, what=f"tile {tile} 派出去")


def wait_import(server, tile, before):
    return wait_until(lambda: next((r for r in donor_imports(server)[before:] if tuple(r["tile"]) == tile), None),
                      timeout=60, interval=0.5, what=f"tile {tile} 的结果")


def stand(server, name, cx, cz):
    """假玩家在 chunk (cx, cz) 出生并等周围加载好（出生点是同步生成的，可能卡几秒，所以放在派任务之前）。"""
    server.rcon(f"player {name} spawn at {cx * 16 + 8} 200 {cz * 16 + 8} facing 0 0 in minecraft:overworld in spectator")
    wait_until(lambda: (v := view(server, name)) is not None and v["ready"] / v["total"] > 0.95, timeout=180, interval=1,
               what=f"{name} 周围加载好")


def poke(server, seconds):
    """每 0.2 秒发一次 rcon，返回最慢的一次（秒）：rcon 由主线程处理，主线程卡住就回不来。"""
    slow, t0 = 0.0, time.monotonic()
    while time.monotonic() - t0 < seconds:
        s = time.monotonic()
        server.rcon("donor status")
        slow = max(slow, time.monotonic() - s)
        time.sleep(0.2)  # 戳的间隔，不是等条件
    return slow


@pytest.fixture(scope="module")
def ready(server, worker):
    wid = worker_id(server, "worker")
    # 先做几个 tile，让 coordinator 知道这个 donor 平时做一个要多久（7.3 的实测耗时，等不等按它判断）。
    # 第一个是 donor 冷启动，特别慢，只做一个的话平均值大于等待上限，一次都不会等。
    for i in range(5):
        before = len(donor_imports(server))
        server.rcon(f"donor assign {900 + 2 * i} 900 {wid}")
        wait_import(server, (900 + 2 * i, 900), before)
    print("预热后", next(w for w in status(server)["workers"] if w["id"] == wid))
    server.rcon("bench track on")
    server.rcon("donor feature waitdonor on")
    yield wid
    server.rcon("donor feature waitdonor off")
    server.rcon("bench track off")


def test_waits_for_donor_while_approaching(server, ready):
    tile = (950, -950)
    cx, cz = tile[0] * N + 2, tile[1] * N + 2
    stand(server, "waitbot", cx - 32, cz)
    before = len(donor_imports(server))
    w0 = status(server)["waitdonor"]
    try:
        assign_and_wait_leased(server, tile, ready)
        for d in (23, 16):
            server.rcon(f"tp waitbot {(cx - d) * 16 + 8} 200 {cz * 16 + 8}")
            time.sleep(0.3)  # 一步一步走，不是等条件
        slow = poke(server, 3)
        r = wait_import(server, tile, before)
        w1 = status(server)["waitdonor"]
        # 走到 tile 上，tile 的 chunk 都做成成品（从存档读 donor 的数据，接着做完）
        for d in (8, 0):
            server.rcon(f"tp waitbot {(cx - d) * 16 + 8} 200 {cz * 16 + 8}")
            time.sleep(1)  # 一步一步走，不是等条件
        wait_until(lambda: all(server.rcon(f"donor peek {x} {z}") == "minecraft:full" for x, z in tile_chunks(tile)),
                   timeout=60, interval=1, what="tile 的 chunk 都成了成品")
        runs = {f"{x},{z}": chunk_runs(server, x, z) for x, z in tile_chunks(tile)}
        print("导入", r, "等 donor", w0, "→", w1, "rcon 最慢", slow, "服务器上跑过的", runs)
        assert w1["waits"] > w0["waits"], "没有等：tile 的 chunk 在派出去之前就开始加载了，或者预计耗时超过上限"
        assert r["accepted"], r
        assert w1["hits"] > w0["hits"], (w0, w1)
        assert all(v["noise"] == 0 and v["features"] == 0 for v in runs.values()), runs
        assert slow < 1.0, slow
    finally:
        server.rcon("player waitbot kill")


def test_sync_load_does_not_wait(server, ready):
    # 主线程同步要一个正租着的 tile 的 chunk：不等 donor，主线程只卡原版生成这个 chunk 的时间
    tile = (960, -960)
    w0 = status(server)["waitdonor"]
    assign_and_wait_leased(server, tile, ready)
    x, z = tile[0] * N + 1, tile[1] * N + 1
    ms = int(server.rcon(f"donor syncload {x} {z}").split()[1])
    w1 = status(server)["waitdonor"]
    print(f"同步要 chunk 用了 {ms} ms", w0, w1)
    assert w1["timeouts"] == w0["timeouts"], (w0, w1)
    assert chunk_runs(server, x, z)["noise"] > 0, "同步要的 chunk 应该是服务器自己生成的"


def test_donor_stuck_times_out(server, worker, ready):
    # donor 主线程卡住，结果回不来：最多等 2 秒就超时，原版照常生成，rcon 一直有回应
    tile = (980, -980)
    cx, cz = tile[0] * N + 2, tile[1] * N + 2
    stand(server, "stuckbot", cx - 32, cz)
    w0 = status(server)["waitdonor"]
    stuck = threading.Thread(target=lambda: worker.rcon("donor sleep 6"), daemon=True)
    try:
        # 先让 worker 主线程睡下，再派任务：导出排在睡觉后面
        stuck.start()
        time.sleep(0.5)  # 让 sleep 命令先到 worker，不是等条件
        assign_and_wait_leased(server, tile, ready)
        for d in (23, 16):
            server.rcon(f"tp stuckbot {(cx - d) * 16 + 8} 200 {cz * 16 + 8}")
            time.sleep(0.3)  # 一步一步走，不是等条件
        slow = poke(server, 4)
        w1 = status(server)["waitdonor"]
        print("donor 卡住：", w0, "→", w1, "rcon 最慢", slow)
        assert w1["waits"] > w0["waits"], (w0, w1)
        assert w1["timeouts"] > w0["timeouts"], (w0, w1)
        assert slow < 1.0, "主线程卡住了"
    finally:
        server.rcon("player stuckbot kill")
        stuck.join(timeout=30)
