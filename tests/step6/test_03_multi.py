"""第 6 步（6.4）：两个 donor 同时干活；运行中 kill、重启 donor，不丢 chunk。

tile 之间隔开（间距 2 个 tile），外圈互不重叠，不触发接缝规则，只看多 donor 的派活和容错。
/donor status 里每个 worker 有 done（被接受导入的 tile 数）。
"""

import json

from mcd import compose, donor_imports, wait_until, worker_id

# 远离其他测试：tile (300, -300) 一带
TILES = [(300 + 2 * i, -300 + 2 * j) for i in range(4) for j in range(3)]


def status(server) -> dict:
    return json.loads(server.rcon("donor status"))


def assert_fresh(server, tiles):
    for tx, tz in tiles:
        assert server.rcon(f"donor peek {tx * 4 + 1} {tz * 4 + 1}") == "none", f"tile {tx},{tz} 已被碰过，换个位置"


def written(server, tile) -> bool:
    tx, tz = tile
    return all(server.rcon(f"donor peek {tx * 4 + dx} {tz * 4 + dz}") != "none" for dx in (0, 3) for dz in (0, 3))


def test_two_donors_share_and_survive_kill(server, worker, worker2):
    assert_fresh(server, TILES)
    w1, w2 = worker_id(server, "worker"), worker_id(server, "worker2")
    before = len(donor_imports(server))
    for tx, tz in TILES:
        assert server.rcon(f"donor enqueue {tx} {tz}").startswith("queued")

    # 两个都领到活以后，kill 掉 worker2
    def both_busy():
        ws = {w["id"]: w for w in status(server)["workers"]}
        return w1 in ws and w2 in ws and (ws[w2]["task"] >= 0 or ws[w2]["done"] > 0)

    wait_until(both_busy, timeout=120, interval=0.5, what="worker2 领到任务")
    compose("kill", "worker2")
    try:
        wait_until(lambda: all(w["id"] != w2 for w in status(server)["workers"]), timeout=30, interval=1,
                   what="coordinator 发现 worker2 断了")
    finally:
        compose("up", "-d", "--wait", "--wait-timeout", "600", "worker2", timeout=660)
    worker_id(server, "worker2")

    def finished():
        t = status(server)["tasks"]
        return t["queued"] == 0 and t["leased"] == 0

    wait_until(finished, timeout=600, interval=3, what="任务都处理完")
    got = [r for r in donor_imports(server)[before:] if tuple(r["tile"]) in set(TILES) and r["accepted"]]
    worlds = {r["world"] for r in got}
    print(f"{len(TILES)} 个 tile，接受 {len(got)} 个，来自 {len(worlds)} 个 donor 世界")
    assert len(worlds) == 2, "两个 donor 都应该干过活"
    missing = [t for t in TILES if not written(server, t)]
    assert not missing, f"有 tile 没写进存档：{missing}"
