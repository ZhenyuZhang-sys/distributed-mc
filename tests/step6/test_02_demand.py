"""第 6 步（6.3）：自动找需求。

测试服务器默认关着找需求（-Dmcdonate.demand=off），这里用 /donor demand on 临时打开。
/donor status 里的 scan：扫描次数、平均和最长耗时（微秒）、inner（离玩家这么多圈以内不交给 donor）、outer。
"""

import json
import time

import pytest

from mcd import donor_imports, compose, wait_until, worker_id

# 远离其他测试的地方：方块 (-8000, 8000) = chunk (-500, 500)
SPOT = (-8000, 8000)


def status(server) -> dict:
    return json.loads(server.rcon("donor status"))


def imports(server) -> list:
    return donor_imports(server)


def test_demand_off_by_default(server, worker):
    worker_id(server, "worker")
    s = status(server)
    assert s["demand"] is False, s
    assert s["scan"]["count"] == 0, "关着的时候不该扫描"


@pytest.fixture(scope="module")
def filled(server, worker, worker2):
    worker_id(server, "worker")
    worker_id(server, "worker2")
    before = len(imports(server))
    server.rcon("donor demand on")
    server.rcon(f"player demandbot spawn at {SPOT[0]} 200 {SPOT[1]} facing 0 0 in minecraft:overworld in spectator")
    try:
        got = wait_until(lambda: (n := [r for r in imports(server)[before:] if r["accepted"]]) and len(n) >= 10 and n,
                         timeout=300, interval=3, what="donor 在假玩家周围导入至少 10 个 tile")
        yield got, status(server)
    finally:
        server.rcon("donor demand off")
        server.rcon("player demandbot kill")


def test_tiles_are_around_player_outside_loaded_area(server, filled):
    got, s = filled
    cx, cz = SPOT[0] // 16, SPOT[1] // 16
    inner, outer = s["scan"]["inner"], s["scan"]["outer"]
    for r in got:
        tx, tz = r["tile"]
        dist = max(max(tx * 4 - cx, cx - (tx * 4 + 3)), max(tz * 4 - cz, cz - (tz * 4 + 3)))
        # 在 inner 以外（服务器自己会占在内存里的范围以外）、outer 以内
        assert inner < dist <= outer, (r, dist, inner, outer)


def test_imported_tiles_in_save(server, filled):
    got, _ = filled
    for r in got:
        tx, tz = r["tile"]
        assert server.rcon(f"donor peek {tx * 4 + 1} {tz * 4 + 1}") != "none", r


def test_scan_is_cheap(filled):
    _, s = filled
    scan = s["scan"]
    print(f"扫描 {scan['count']} 次，平均 {scan['avgMicros']} µs，最长 {scan['maxMicros']} µs")
    assert scan["count"] > 0
    assert scan["avgMicros"] < 1000, scan


def test_no_workers_no_scan(server, worker, worker2, filled):
    # 没有 donor 连着时不扫描：服务器和原版一样
    server.rcon("donor demand on")
    compose("stop", "worker", "worker2", timeout=300)
    try:
        wait_until(lambda: status(server)["workers"] == [], timeout=60, interval=2, what="worker 都断开")
        a = status(server)["scan"]["count"]
        # 观察窗口：扫描每秒一次，5 秒里一次都不该有（这里是故意等，不是等某个条件）
        time.sleep(5)
        b = status(server)["scan"]["count"]
        assert a == b, (a, b)
    finally:
        server.rcon("donor demand off")
        compose("up", "-d", "--wait", "--wait-timeout", "600", "worker", "worker2", timeout=660)
        worker_id(server, "worker")
