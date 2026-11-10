"""11-10 f3 端到端（819df0f）：worker2 停服时原版报 "Exception stopping the server: NoSuchElementException
at LongLinkedOpenHashSet.removeFirstLong ← DistanceManager 的票更新"。当时 worker2 刚起来、在做 tile（落后 52 tick）。
猜测：TileExporter.prefetch 用 server.execute 排的加票，在主线程等 chunk 时插进了原版正在做的票更新（重入），停服那一轮更新撞上。
复现：让 worker2 手上有好几个 tile（prefetch 排着、生成在跑），马上让它正常停服，看它的日志；来回几轮。
"""
import json
import time

import pytest

from mcd import compose, wait_until, worker_id

pytestmark = pytest.mark.long

ROUNDS = 4


def status(server):
    return json.loads(server.rcon("donor status"))


def me(server, wid):
    return next((w for w in status(server)["workers"] if w["id"] == wid), None)


def test_worker_stops_cleanly_while_tiles_are_generating(server, worker, worker2):
    assert server.rcon("donor feature throughput on").startswith("features")   # 一次给几个，worker 上才有排着的 prefetch
    bad = []
    try:
        for r in range(ROUNDS):
            wid = worker_id(server, "worker2")
            for i in range(6):
                tx, tz = 6000 + r * 40 + i * 4, -6000 - r * 40
                assert server.rcon(f"donor assign {tx} {tz} {wid}") == "queued"
            wait_until(lambda: (me(server, wid) or {}).get("inFlight", 0) >= 2, timeout=60, interval=0.2,
                       what="worker2 手上有好几个 tile")
            time.sleep(1)   # 让 prefetch、生成都排上（这里是故意等，不是等某个条件）
            compose("stop", "worker2", timeout=300)
            logs = worker2.logs()
            if "Exception stopping the server" in logs:
                i = logs.index("Exception stopping the server")
                bad.append(f"第 {r + 1} 轮：" + logs[i:i + 400])
            compose("up", "-d", "--wait", "--wait-timeout", "600", "worker2", timeout=660)
            assert server.rcon("donor reset").startswith("reset")
    finally:
        server.rcon("donor feature throughput off")
        server.rcon("donor reset")
    assert not bad, "worker 停服时原版票更新出错：\n" + "\n".join(bad)
