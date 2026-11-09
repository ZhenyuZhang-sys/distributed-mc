"""11-09（用户真机，distributed-mc-75 查日志）：worker 连上 83 秒没派任务。donor 世界跨会话复用，飞过的区域都导入过这个世界的
chunk，新任务按认主规则绑定这个世界（hardWorld）；而 coordinator 只在收到 worker 的第一个结果时才知道它是哪个世界，
新连上的 worker 在交出结果之前领不到绑定的任务，"认主的世界 60 秒不在"还会误报，把任务丢给服务器。
修法：worker 在 hello 里报自己的 donor 世界。这里：先让 worker 导入一个 tile（之后它旁边的 tile 绑定这个世界），
重启 worker（新连接、新编号），马上放旁边的 tile，几秒内就要派给它。
"""

import json

import pytest

from mcd import compose, wait_until, worker_id

pytestmark = pytest.mark.long


def status(server):
    return json.loads(server.rcon("donor status"))


def test_reconnected_worker_gets_tasks_bound_to_its_world(server, worker):
    n = status(server)["config"]["tileSize"]
    tile = (-3200 // n, 3200 // n)     # 离其他测试远
    wid = worker_id(server, "worker")
    done0 = status(server)["tasks"]["done"]
    assert server.rcon(f"donor assign {tile[0]} {tile[1]} {wid}") == "queued"
    wait_until(lambda: status(server)["tasks"]["done"] > done0, timeout=180, interval=2, what="第一个 tile 导入")
    compose("restart", "worker", timeout=600)
    wait_until(lambda: any(w["id"] != wid for w in status(server)["workers"]), timeout=300, interval=2, what="worker 重新连上")
    new = next(w["id"] for w in status(server)["workers"] if w["id"] != wid)
    logs_before = len(server.logs())
    # 旁边的 tile：区域碰到上一个 tile 的外圈（这个世界导入的），认主给这个世界
    assert server.rcon(f"donor enqueue {tile[0] + 1} {tile[1]}").startswith("queued")
    wait_until(lambda: f"派给 worker {new}" in server.logs()[logs_before:], timeout=10, interval=0.5,
               what="新连上的 worker 马上领到认主给它世界的任务")
    assert "60 秒不在" not in server.logs()[logs_before:], "worker 连着，不该报'认主的世界不在'"
