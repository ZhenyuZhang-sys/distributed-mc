"""第 5 步（5.3）：coordinator 监听，worker 握手拿 seed，网络收发不被主线程卡住。

/donor status（服务器）返回一行 JSON：
  {"workers": [{"id", "name", "task", "idleMs"}], "tasks": {"queued", "leased", "done", "failed"}}
"""

import pytest
import json

from mcd import compose, wait_until, worker_id


def status(server) -> dict:
    return json.loads(server.rcon("donor status"))


def test_worker_registered(server, worker):
    # 第 6 步起还有 worker2，所以只看 worker 这个容器连上了没有
    assert worker_id(server, "worker") > 0


def test_worker_seed_from_coordinator(worker):
    # worker 容器的 SEED 故意设成别的值；它用的是 coordinator 握手给的 seed
    cfg = json.loads(compose("config", "--format", "json"))
    assert cfg["services"]["worker"]["environment"]["SEED"] != "424242"
    assert worker.rcon("seed") == "Seed: [424242]"


@pytest.mark.long
def test_main_thread_stall_keeps_connection(server, worker):
    # worker 主线程卡 20 秒（超过 15 秒的断线判定）：网络线程照常发心跳，连接不断
    before = worker_id(server, "worker")
    worker.rcon("donor sleep 20")
    after = [w["id"] for w in status(server)["workers"]]
    assert before in after, after


def test_enqueue_one_tile(server, worker):
    tile = (-60, 60)
    out = server.rcon(f"donor assign {tile[0]} {tile[1]} {worker_id(server, 'worker')}")
    assert "queued" in out, out
    wait_until(lambda: status(server)["tasks"]["done"] >= 1, timeout=120, interval=2, what="任务完成")
    # tile 已经写进服务器存档（导入的半成品）
    assert server.rcon(f"donor peek {tile[0] * 4} {tile[1] * 4}") == "minecraft:initialize_light"
