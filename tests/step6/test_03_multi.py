"""第 6 步（6.4）：两个 donor 同时干活；运行中 kill、重启 donor，不丢 chunk。

tile 之间隔开（间距 2 个 tile），外圈互不重叠，不触发接缝规则，只看多 donor 的派活和容错。
/donor status 里每个 worker 有 done（被接受导入的 tile 数）。
"""

import pytest
import json

from mcd import compose, donor_imports, wait_until, worker_id

# 跑一遍要几分钟，--quick 时不跑
pytestmark = pytest.mark.long

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

    # worker2 做完至少一个、手上又正有任务时 kill 掉它（中途 kill）。
    # 原来只等"领到任务"就 kill：它手上那个还没做完，重启要一分钟，worker 早把 12 个都做完了，
    # 接受的结果只来自一个 donor 世界（11-09 完整回归里失败过一次，以前是碰巧它先做完了一个）。
    def busy_after_one():
        ws = {w["id"]: w for w in status(server)["workers"]}
        return w1 in ws and w2 in ws and ws[w2]["done"] >= 1 and ws[w2]["task"] >= 0

    wait_until(busy_after_one, timeout=120, interval=0.2, what="worker2 做完一个、手上又有任务")
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
