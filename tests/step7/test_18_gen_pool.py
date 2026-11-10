"""11-10 kexu：测试的 docker worker 不走客户端，原来没有 -XX:ActiveProcessorCount，生成线程池按容器核数 − 1
（2 核 worker 只有 1 个生成线程）；真客户端是池 = 设的线程数（WorkerLaunch：ActiveProcessorCount = threads + 1）。
codex-pipe 2×2（2736827，总 4 核）：池 1+1 时 0.83 tile/s，加到 2+2 后 1.43～1.50 tile/s。

改成和真客户端一样：worker、worker2 的生成线程默认 = WORKER_CPUS（取整、至少 1），WORKER_GEN_THREADS 可以单独设；
worker3 按 WORKER3_CPUS、WORKER3_GEN_THREADS。scripts/lib.sh 的 tc 算好 ActiveProcessorCount 交给 compose。
worker 在心跳里报实际看到的核数（cpus）和生成池大小（genPool），/donor status 显示，测量驱动每轮核对。
"""
import json
import math
import os
import re

from mcd import compose, limits, wait_until, worker_id


def config(profiles: str, **env) -> dict:
    saved = os.environ.copy()
    try:
        os.environ["COMPOSE_PROFILES"] = profiles
        os.environ.update(env)
        return json.loads(compose("config", "--format", "json"))["services"]
    finally:
        os.environ.clear()
        os.environ.update(saved)


def apc(svc: dict) -> int:
    m = re.search(r"-XX:ActiveProcessorCount=(\d+)", svc["environment"]["JVM_OPTS"])
    assert m, svc["environment"]["JVM_OPTS"]
    return int(m.group(1))


def threads_for(cpus) -> int:
    return max(1, math.ceil(float(cpus)))


def expected_worker_threads() -> int:
    """这个槽位 worker 应有的生成线程数：WORKER_GEN_THREADS，没设就是 WORKER_CPUS 取整（默认 4）。"""
    lim = limits()
    g = os.environ.get("WORKER_GEN_THREADS") or lim.get("WORKER_GEN_THREADS")
    return int(g) if g else threads_for(os.environ.get("WORKER_CPUS") or lim.get("WORKER_CPUS") or 4)


def test_compose_gives_workers_client_like_thread_pool():
    s = config("worker,worker2,worker3")
    w = expected_worker_threads()
    assert apc(s["worker"]) == w + 1, s["worker"]["environment"]["JVM_OPTS"]
    assert apc(s["worker2"]) == w + 1, "worker2 和 worker 一样"
    assert apc(s["worker3"]) == threads_for(s["worker3"]["cpus"]) + 1, "worker3 按自己的核数（0.5 核 → 1 个）"


def test_overrides_follow_cpus_and_explicit_threads():
    assert apc(config("worker", WORKER_CPUS="2")["worker"]) == 3, "2 核：2 个生成线程"
    assert apc(config("worker", WORKER_CPUS="8")["worker"]) == 9, "kexu 1×8"
    assert apc(config("worker", WORKER_CPUS="4", WORKER_GEN_THREADS="6")["worker"]) == 7, "单独设线程数"
    assert apc(config("worker3", WORKER3_CPUS="4")["worker3"]) == 5, "kexu 3×4 的 worker3"


def me(server, wid):
    return next(w for w in json.loads(server.rcon("donor status"))["workers"] if w["id"] == wid)


def test_worker_reports_cpus_and_generation_pool(server, worker):
    wid = worker_id(server, "worker")
    wait_until(lambda: me(server, wid).get("genPool") is not None, timeout=30, interval=1, what="worker 报了生成池大小")
    w = me(server, wid)
    print({k: w.get(k) for k in ("cpus", "genPool", "threads")})
    want = expected_worker_threads()
    assert w["genPool"] == want, f"生成池 = 设的线程数 {want}：{w}"
    assert w["cpus"] == want + 1, w
