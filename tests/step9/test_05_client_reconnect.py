"""11-09（distributed-mc-75 在用户 Mac 上查到）：真机上 worker 断线后连不回去，编号一直涨、服务器反复发新邀请。
原因是 worker 发给游戏的 NEED_TOKEN 被原版包成日志格式（"[STDOUT]: MCDONATE NEED_TOKEN"），客户端认不出，拿不到新 token；
游戏发给 worker 的 TOKEN 又可能被原版控制台读走——这时 token 会以 "TOKEN xxx<--[HERE]" 明文写进 worker.log。

这里：worker 连上以后用 /donor kick 把它踢掉（用过的 token 不能再用），客户端应该收到 NEED_TOKEN、再要一次邀请、
把新 token 交给 worker，worker 用新 token 连回来。worker.log 里不能有原版控制台读到的控制行（"<--[HERE]"），也不能有 token 原文。
"""

import re
import threading
import time

import pytest

from mcd import wait_until
from step9.headless_client import TESTHOOKS_JAR, prepare_mods, run_client

pytestmark = pytest.mark.long

NAME = "reconnect1"
STAY = 300


def connected_ids(server) -> list[int]:
    return [int(m) for m in re.findall(r"worker (\d+)（[^）]*）连上", server.logs())]


def test_worker_reconnects_with_new_token(server, tmp_path_factory):
    base = server.artifact_dir or tmp_path_factory.mktemp("client")
    mods = prepare_mods(base / "client-mods-reconnect", extra=[TESTHOOKS_JAR])
    before = set(connected_ids(server))
    result = {}
    t = threading.Thread(target=lambda: result.update(run_client(NAME, STAY, mods, base / "client-reconnect")))
    t.start()
    try:
        wait_until(lambda: set(connected_ids(server)) - before, timeout=STAY - 30, interval=2, what="客户端的 worker 连上")
        first = max(set(connected_ids(server)) - before)
        assert server.rcon(f"donor kick {first}") == "kicked", "踢不掉"
        wait_until(lambda: [i for i in connected_ids(server) if i > first], timeout=120, interval=2,
                   what="worker 用新 token 连回来")
    finally:
        t.join()
    log, worker_log = result["log"], result.get("worker", "")
    assert "worker：NEED_TOKEN" in log, "客户端没收到 worker 要新 token"
    assert log.count("worker：CONNECTED") >= 2, "客户端没看到 worker 连回来"
    assert "<--[HERE]" not in worker_log, [l for l in worker_log.splitlines() if "<--[HERE]" in l][:3]
    assert not re.search(r"TOKEN (?!\*)\S{6,}", worker_log), "worker.log 里有 token 原文"
    assert not re.search(r"TOKEN (?!\*)\S{6,}", log), "客户端日志里有 token 原文"
