"""worker 关掉看门狗（max-tick-time=-1），服务器的不动（kexu、调度 11-09）。

worker 上没有玩家，一个 tile 在一个 tick 里生成是设计如此；并行跑抢磁盘时一个 tile 偶尔要几十秒，
看门狗（默认 60 秒）会把 worker 当成卡死杀掉——第 7 步对比里几轮的 donor 就是这样起飞前全没了。
coordinator 所在的服务器有真实玩家，看门狗照常开着。客户端起的 worker 也要关（第 9 步，启动 worker 时写 server.properties）。
"""

import json

from mcd import compose, data_root


def env(svc: dict) -> dict:
    return svc.get("environment", {})


def test_compose_sets_it_for_workers_only():
    import os
    old = os.environ.get("COMPOSE_PROFILES")
    os.environ["COMPOSE_PROFILES"] = "worker,worker2,worker3"
    try:
        svcs = json.loads(compose("config", "--format", "json"))["services"]
    finally:
        if old is None:
            os.environ.pop("COMPOSE_PROFILES", None)
        else:
            os.environ["COMPOSE_PROFILES"] = old
    for w in ("worker", "worker2", "worker3"):
        assert str(env(svcs[w]).get("MAX_TICK_TIME")) == "-1", (w, env(svcs[w]).get("MAX_TICK_TIME"))
    assert str(env(svcs["minecraft"]).get("MAX_TICK_TIME", "")) != "-1", "服务器的看门狗不能关"


def props(svc: str) -> dict:
    out = {}
    for line in (data_root(svc) / "server.properties").read_text().splitlines():
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            out[k] = v
    return out


def test_running_worker_has_no_watchdog(server, worker):
    assert props("worker")["max-tick-time"] == "-1"
    assert props("minecraft")["max-tick-time"] != "-1"
