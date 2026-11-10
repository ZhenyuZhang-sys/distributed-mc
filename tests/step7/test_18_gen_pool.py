"""11-10 kexu：测试的 docker worker 不走客户端，没有 -XX:ActiveProcessorCount，生成线程池按容器核数 − 1，
真客户端是池 = 设的线程数。worker 在心跳里报实际看到的核数（cpus）和生成池大小（genPool），/donor status 显示，
kexu 的测量驱动每轮核对，防止哪个槽位漏改。

这里只查报上来了、和 worker 自己一致：genPool 就是原版后台线程池（Util.backgroundExecutor）的并行度。
"""
import json

from mcd import wait_until, worker_id


def me(server, wid):
    return next(w for w in json.loads(server.rcon("donor status"))["workers"] if w["id"] == wid)


def test_worker_reports_cpus_and_generation_pool(server, worker):
    wid = worker_id(server, "worker")
    wait_until(lambda: me(server, wid).get("genPool") is not None, timeout=30, interval=1, what="worker 报了生成池大小")
    w = me(server, wid)
    print({k: w.get(k) for k in ("cpus", "genPool", "threads")})
    assert w["cpus"] >= 1
    assert w["genPool"] >= 1
    # 没有 -XX:ActiveProcessorCount 时原版的池 = 核数 − 1（至少 1）；有的话 cpus 就是设的那个数，关系一样
    assert w["genPool"] == max(1, w["cpus"] - 1), w
