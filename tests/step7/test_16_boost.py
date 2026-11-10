"""11-09 加速模式（用户：Mac 的 CPU 很闲，希望 donor 空闲时也一直算）：开了加速的 donor 没有有截止的任务时做后台 tile。

假玩家站着不动（没有截止时间：找需求算过的 tile 对不开加速的 donor 照旧不派），worker 开加速以后要领到后台 tile、被接受导入；
关掉加速以后不再领新的后台 tile。测试里的 worker 没有游戏，用 worker 的 /donor boost 代替客户端的 BOOST 控制行。
"""
import json
import time

import pytest

from mcd import wait_until, worker_id

pytestmark = pytest.mark.long

SPOT = (-48000.5, 48008.5)   # 离其他测试远，全新的地形


def status(server):
    return json.loads(server.rcon("donor status"))


def me(server, wid):
    return next(w for w in status(server)["workers"] if w["id"] == wid)


def test_boost_donor_does_background_tiles_when_idle(server, worker):
    wid = worker_id(server, "worker")
    assert server.rcon("donor reset").startswith("reset")
    assert server.rcon("donor feature deadline on").startswith("features")
    server.rcon(f"player boostbot spawn at {SPOT[0]} 200 {SPOT[1]} facing 0 0 in minecraft:overworld in spectator")
    server.rcon("donor demand on")
    try:
        # 没开加速：站着不动，没有截止时间，什么都不派
        time.sleep(8)
        assert me(server, wid)["backgroundLeased"] == 0
        assert worker.rcon("donor boost on") == "boost on"
        wait_until(lambda: me(server, wid)["boost"], timeout=15, interval=1, what="coordinator 知道 worker 开了加速")
        assert me(server, wid)["threads"] >= 1
        wait_until(lambda: me(server, wid)["backgroundAccepted"] >= 3, timeout=300, interval=2, what="后台 tile 做完、被接受")
        s = status(server)
        assert s["scan"]["background"]["active"], s["scan"]["background"]
        print({k: me(server, wid)[k] for k in ("backgroundLeased", "backgroundAccepted", "threads")}, s["scan"]["background"])
        # 关掉：不再领新的后台 tile（已经在做的做完）
        assert worker.rcon("donor boost off") == "boost off"
        wait_until(lambda: not me(server, wid)["boost"], timeout=15, interval=1, what="coordinator 知道 worker 关了加速")
        time.sleep(3)
        leased = me(server, wid)["backgroundLeased"]
        time.sleep(10)
        assert me(server, wid)["backgroundLeased"] == leased, "关掉加速以后不该再领后台 tile"
    finally:
        server.rcon("donor demand off")
        server.rcon("donor feature deadline off")
        server.rcon("player boostbot kill")
        worker.rcon("donor boost off")
        server.rcon("donor reset")
