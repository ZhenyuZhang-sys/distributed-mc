"""11-09 用户真机：一个任务的生成被异常打断以后，worker 主线程在原版 getChunk 里永远等下去，后面 4 个任务都不动，
心跳照发，coordinator 一直续租（5 分多钟）。现在：worker 自己等 chunk、有超时（最少 30 秒），超时报失败、接着做下一个。
/donor stallnext 让下一个 tile 多等一个永远不会完成的 chunk。
"""

import json

import pytest

from mcd import wait_until, worker_id

pytestmark = pytest.mark.long


def status(server):
    return json.loads(server.rcon("donor status"))


def test_stalled_tile_times_out_and_next_one_runs(server, worker):
    n = status(server)["config"]["tileSize"]
    a, b = (-3400 // n, 3400 // n), (-3400 // n, 3460 // n)
    wid = worker_id(server, "worker")
    logs_before = len(server.logs())
    done0 = status(server)["tasks"]["done"]
    assert worker.rcon("donor stallnext") == "stalling next"
    assert server.rcon(f"donor assign {a[0]} {a[1]} {wid}") == "queued"
    assert server.rcon(f"donor assign {b[0]} {b[1]} {wid}") == "queued"
    # 卡住的那个 30 秒左右超时、报失败；后面那个照常做完
    wait_until(lambda: "失败" in server.logs()[logs_before:], timeout=90, interval=2, what="卡住的 tile 超时报失败")
    wait_until(lambda: status(server)["tasks"]["done"] > done0, timeout=120, interval=2, what="下一个 tile 做完")
    assert "超过" in worker.logs() or "超过" in server.logs()[logs_before:], "失败原因应该是等待超时"
