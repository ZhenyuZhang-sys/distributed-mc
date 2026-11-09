"""第 6 步（6.2）：相邻 tile 的接缝规则，端到端。见 docs/step6-plan.md 第 0 节。

用到的 tile 都在 x、z ≤ -200 的一带，远离其他测试碰过的地方；每条测试放任务前先确认那里是空的。
/donor assign <tx> <tz> <worker> 把任务直接派给指定的 worker（绕过"包一片"），
/donor imports 列出每个导入（tile、donor 世界、是否被整个丢弃），/donor owner 查 chunk 归属。
"""

import json

import pytest

from analysis.mca.decor import Diff, compare_chunks
from analysis.mca import read_chunk
from analysis.mca.chunk import decode_chunk
from mcd import donor_imports, EXCHANGE, compose, load_tile_file, run, wait_until, worker_id


def status(server) -> dict:
    return json.loads(server.rcon("donor status"))


def imports(server) -> list:
    return donor_imports(server)


def peek(server, x, z) -> str:
    return server.rcon(f"donor peek {x} {z}")


def assert_fresh(server, tiles):
    for tx, tz in tiles:
        for x in range(tx * 4 - 1, tx * 4 + 5):
            for z in range(tz * 4 - 1, tz * 4 + 5):
                assert peek(server, x, z) == "none", f"tile {tx},{tz} 附近的 chunk ({x},{z}) 已经被碰过，换个位置"


def assign_and_wait(server, tile, wid):
    before = len(imports(server))
    out = server.rcon(f"donor assign {tile[0]} {tile[1]} {wid}")
    assert out == "queued", out
    wait_until(lambda: len(imports(server)) > before, timeout=180, interval=2, what=f"tile {tile} 结果回来")
    return imports(server)[-1]


def tile_chunks(tile):
    return [(tile[0] * 4 + dx, tile[1] * 4 + dz) for dx in range(4) for dz in range(4)]


# ---- 世界编号 ----

@pytest.mark.long
def test_world_ids_differ_and_survive_restart(worker, worker2):
    a, b = worker.rcon("donor worldid"), worker2.rcon("donor worldid")
    assert a and b and a != b
    compose("restart", "worker", timeout=300)
    wait_until(lambda: worker.health() == "healthy", timeout=300, interval=5, what="worker 重启好")
    assert worker.rcon("donor worldid") == a, "存档还在，编号不该变"


# ---- 同一个 donor 先后做相邻的 A、B：B 是超集，A∪B 内部和 donor 世界逐块一致 ----

SAME_A, SAME_B = (-200, -200), (-199, -200)


@pytest.mark.long
def test_same_donor_superset(server, worker, tmp_path_factory):
    assert_fresh(server, [SAME_A, SAME_B])
    wid = worker_id(server, "worker")
    ra = assign_and_wait(server, SAME_A, wid)
    rb = assign_and_wait(server, SAME_B, wid)
    assert ra["accepted"] and rb["accepted"], (ra, rb)
    # 区域：A∪B（8×4 个 tile chunk）外面再加 2 圈
    x0, z0 = SAME_A[0] * 4 - 2, SAME_A[1] * 4 - 2
    size = 8 + 4
    server.chunky_square(x0, z0, size)
    worker.chunky_square(x0, z0, size)
    base = tmp_path_factory.mktemp("superset")
    a, b = server.snapshot(base / "server"), worker.snapshot(base / "worker")
    # A∪B 的 tile chunk 里，除了挨着外面那一圈，必须逐块一致
    inner = [(x, z) for x in range(SAME_A[0] * 4 + 1, SAME_B[0] * 4 + 3)
             for z in range(SAME_A[1] * 4 + 1, SAME_A[1] * 4 + 3)]
    bad = {}
    for x, z in inner:
        d = sum(p != q for p, q in zip(read_chunk(a, x, z).column_hashes(), read_chunk(b, x, z).column_hashes()))
        if d:
            bad[(x, z)] = d
    assert not bad, f"同一个 donor 的相邻 tile 内部和 donor 世界不一致：{bad}"


def test_export_is_superset(worker):
    # 只在 worker 上：先后导出相邻的 C、D（同一个世界）。D 导出的外圈里 C 的 tile chunk，
    # 和 C 导出时比只多不少：C 有非空气方块的地方，D 不会变成空气（那说明 C 写进去的方块丢了）
    c, d = (-210, -200), (-209, -200)
    for t in (c, d):
        json.loads(worker.rcon(f"donor export {t[0]} {t[1]}"))
    fc = load_tile_file(EXCHANGE / f"tile_{c[0]}_{c[1]}.nbt")
    fd = load_tile_file(EXCHANGE / f"tile_{d[0]}_{d[1]}.nbt")
    shared = [p for p in tile_chunks(c) if p in fd]
    assert len(shared) == 4, shared  # C 最东边一列
    lost = changed = 0
    for x, z in shared:
        cc = decode_chunk(fc[(x, z)], x, z, incomplete=True)
        dd = decode_chunk(fd[(x, z)], x, z, incomplete=True)
        for bx in range(16):
            for bz in range(16):
                for y in range(-64, 320):
                    p, q = cc.block_state(bx, y, bz).name, dd.block_state(bx, y, bz).name
                    if p != q:
                        changed += 1
                        if q == "minecraft:air" and p != "minecraft:air":
                            lost += 1
    print(f"D 的版本改了 {changed} 个方块（D 的地物写进来的），丢了 {lost} 个")
    assert lost == 0


# ---- 服务器重启后归属丢失：按服务器的处理 ----

@pytest.mark.long
def test_restart_keeps_ownership(server, worker):
    # 7.7 起归属存在导入账本里（世界目录 mcdonate/ledger），重启后还在：E 的相邻 F 照样被接受。
    # （第 6 步时归属只在内存里，重启后丢失，F 被整个丢弃；见 docs/step6-report.md 第 4 节）
    e, f = (-220, -200), (-219, -200)
    assert_fresh(server, [e, f])
    wid = worker_id(server, "worker")
    assert assign_and_wait(server, e, wid)["accepted"]
    ex, ez = tile_chunks(e)[0]
    owner = server.rcon(f"donor owner {ex} {ez}")
    assert owner != "none"
    compose("stop", "minecraft", timeout=180)
    compose("up", "-d", "--wait", "--wait-timeout", "600", "minecraft", timeout=660)
    assert server.rcon(f"donor owner {ex} {ez}") == owner, "重启后归属应该还在"
    wid = worker_id(server, "worker")
    r = assign_and_wait(server, f, wid)
    assert r["accepted"] is True, r
    assert all(peek(server, f[0] * 4 + 3, z) != "none" for z in range(f[1] * 4, f[1] * 4 + 4))


# ---- 换了世界（清掉存档重建）的 donor，结果不会覆盖旧世界导入的 chunk ----

@pytest.mark.long
def test_new_world_does_not_overwrite_old(server, worker2):
    g, h = (-230, -200), (-229, -200)
    assert_fresh(server, [g, h])
    old = worker2.rcon("donor worldid")
    assert assign_and_wait(server, g, worker_id(server, "worker2"))["accepted"]
    gx, gz = tile_chunks(g)[0]
    assert server.rcon(f"donor owner {gx} {gz}") == old
    # 清掉 worker2 的存档重建（同一个世界名，重新向 coordinator 要 seed）
    compose("stop", "worker2", timeout=180)
    run("rm", "-rf", "--", str(worker2.world_dir))
    compose("up", "-d", "--wait", "--wait-timeout", "600", "worker2", timeout=660)
    assert worker2.rcon("donor worldid") != old
    r = assign_and_wait(server, h, worker_id(server, "worker2"))
    assert r["accepted"] is False, r
    assert server.rcon(f"donor owner {gx} {gz}") == old


# ---- 两个 donor 跑一片连续区域：写进存档的相邻 tile 都来自同一个世界 ----

@pytest.mark.long
def test_two_donors_no_cross_world_neighbours(server, worker, worker2):
    area = [(-260 + i, -260 + j) for i in range(4) for j in range(4)]
    assert_fresh(server, area)
    worker_id(server, "worker")
    worker_id(server, "worker2")
    before = len(imports(server))
    for tx, tz in area:
        assert server.rcon(f"donor enqueue {tx} {tz}").startswith("queued")

    def finished():
        t = status(server)["tasks"]
        return t["queued"] == 0 and t["leased"] == 0

    wait_until(finished, timeout=600, interval=3, what="这片区域的任务都处理完")
    got = [r for r in imports(server)[before:] if tuple(r["tile"]) in set(area)]
    accepted = {tuple(r["tile"]): r["world"] for r in got if r["accepted"]}
    worlds = set(accepted.values())
    print(f"导入 {len(accepted)} 个、整个丢弃 {len(got) - len(accepted)} 个，用到 {len(worlds)} 个 donor 世界，"
          f"status 里的丢弃比例 {status(server)['discard_ratio']}")
    assert 0 <= status(server)["discard_ratio"] <= 1
    bad = [(a, b) for a in accepted for b in accepted
           if a < b and abs(a[0] - b[0]) <= 1 and abs(a[1] - b[1]) <= 1 and accepted[a] != accepted[b]]
    assert not bad, f"相邻的 tile 来自不同的 donor 世界：{bad}"


# ---- 强制制造跨 donor 接缝：只量不判，记进证据目录 ----

@pytest.mark.long
def test_forced_cross_donor_seam_measured(server, worker, worker2, tmp_path_factory):
    g, h = (-280, -200), (-279, -200)
    assert_fresh(server, [g, h])
    server.rcon("donor rules legacy")
    try:
        assign_and_wait(server, g, worker_id(server, "worker"))
        assign_and_wait(server, h, worker_id(server, "worker2"))
    finally:
        server.rcon("donor rules strict")
    x0, z0 = g[0] * 4 - 2, g[1] * 4 - 2
    size = 8 + 4
    server.chunky_square(x0, z0, size)
    worker.chunky_square(x0, z0, size)  # worker 的世界有 G，自己生成 H：原版答案
    base = tmp_path_factory.mktemp("seam")
    a, b = server.snapshot(base / "server"), worker.snapshot(base / "worker")
    seam_x = [g[0] * 4 + 2, g[0] * 4 + 3, h[0] * 4, h[0] * 4 + 1]  # 接缝两侧各 2 列 chunk
    diff = total = 0
    blocks = Diff(examples=[])
    for x in seam_x:
        for z in range(g[1] * 4, g[1] * 4 + 4):
            ca, cb = read_chunk(a, x, z), read_chunk(b, x, z)
            diff += sum(p != q for p, q in zip(ca.column_hashes(), cb.column_hashes()))
            total += 256
            blocks.add(compare_chunks(ca, cb))
    rate = diff / total
    print(f"强制跨 donor 接缝两侧 2 个 chunk 内的差异列比例：{rate:.2%}；严格差异 {blocks.strict}，"
          f"装饰类差异率 {blocks.decor_rate:.4%}（已知限制，只记录）")
    if server.artifact_dir:
        server.artifact_dir.mkdir(parents=True, exist_ok=True)
        (server.artifact_dir / "forced-seam.json").write_text(json.dumps(
            {"seam_diff_rate": rate, "strict": blocks.strict, "decor_rate": blocks.decor_rate,
             "terrain_rate": blocks.terrain_rate,
             "examples": blocks.examples}) + "\n")
