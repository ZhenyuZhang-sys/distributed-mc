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


def busy_after_one(ws: dict, w1: int, w2: int, done0: int) -> bool:
    """worker2 在这条测试里做完了至少一个（done 比开始时多）、手上又正有任务。
    done 是连接累计的，前面测试做过的不算（11-09 kexu 完整回归：只看 done >= 1，等待立刻满足，12 个全由 worker 做完）。"""
    return w1 in ws and w2 in ws and ws[w2]["done"] > done0 and ws[w2]["task"] >= 0


def test_two_donors_share_and_survive_kill(server, worker, worker2):
    assert_fresh(server, TILES)
    # 先清掉前面测试留下的任务（11-09 f3 step9：test_02 关了找需求，队里还压着约 70 个需求 tile。
    # worker2 手上那个旧任务就满足了下面的"做完一个、手上又有"，还没碰到这条测试的 tile 就被 kill，
    # 重启后的 worker2 把 12 个全做了，worker 一直在做旧任务）
    assert server.rcon("donor reset").startswith("reset")
    wait_until(lambda: status(server)["tasks"]["leased"] == 0, timeout=120, interval=1, what="旧任务撤完")
    w1, w2 = worker_id(server, "worker"), worker_id(server, "worker2")
    before = len(donor_imports(server))
    done0 = {w["id"]: w["done"] for w in status(server)["workers"]}.get(w2, 0)
    # 头两个直接派给 worker2（软认主，只给它）：负载下 worker2 慢的时候，worker 也可能把排队的 12 个全领走，
    # "两个 donor 都干过活"就成了碰运气（11-09 kexu 完整回归时 k2 的演示矩阵在并行跑）。其余的排队，谁空谁领
    for tx, tz in TILES[:2]:
        assert server.rcon(f"donor assign {tx} {tz} {w2}") == "queued"
    for tx, tz in TILES[2:]:
        assert server.rcon(f"donor enqueue {tx} {tz}").startswith("queued")

    # worker2 在这条测试里做完一个、手上又正有任务（第二个派给它的）时 kill 掉它（中途 kill）。
    # 负载下一个 tile 可能要几十秒，给足时间
    wait_until(lambda: busy_after_one({w["id"]: w for w in status(server)["workers"]}, w1, w2, done0),
               timeout=300, interval=0.2, what="worker2 做完一个、手上又有任务")
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
