"""第 6 步（6.3）：自动找需求。

测试服务器默认关着找需求（-Dmcdonate.demand=off），这里用 /donor demand on 临时打开。
/donor status 里的 scan：扫描次数、平均和最长耗时（微秒）、inner（离玩家这么多圈以内不交给 donor）、outer，
recent 是最近 64 次扫描的 [墙钟 µs, 主线程 CPU µs, 发起的读存档次数]。
"""

import json
import time

import pytest

from mcd import donor_imports, compose, wait_until, worker_id

# 假玩家出现后多少秒算启动段
STARTUP_S = 10

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
    t0 = time.monotonic()
    try:
        # 启动段：假玩家出现后的 STARTUP_S 秒（JIT 预热、周围 chunk 状态都要读、生成线程抢核）。
        # 这是测量的分段点，不是等条件。
        wait_until(lambda: time.monotonic() - t0 >= STARTUP_S, timeout=STARTUP_S + 5, interval=0.5, what="启动段结束")
        startup_count = status(server)["scan"]["count"]
        got = wait_until(lambda: (n := [r for r in imports(server)[before:] if r["accepted"]]) and len(n) >= 10 and n,
                         timeout=300, interval=3, what="donor 在假玩家周围导入至少 10 个 tile")
        # 稳定段至少 10 次扫描，p95 才有意义
        wait_until(lambda: status(server)["scan"]["count"] >= startup_count + 10, timeout=60, interval=1,
                   what="稳定段扫描 10 次")
        yield got, status(server), startup_count
    finally:
        server.rcon("donor demand off")
        server.rcon("player demandbot kill")


@pytest.mark.long
def test_tiles_are_around_player_outside_loaded_area(server, filled):
    got, s, _ = filled
    cx, cz = SPOT[0] // 16, SPOT[1] // 16
    inner, outer = s["scan"]["inner"], s["scan"]["outer"]
    for r in got:
        tx, tz = r["tile"]
        dist = max(max(tx * 4 - cx, cx - (tx * 4 + 3)), max(tz * 4 - cz, cz - (tz * 4 + 3)))
        # 在 inner 以外（服务器自己会占在内存里的范围以外）、outer 以内
        assert inner < dist <= outer, (r, dist, inner, outer)


@pytest.mark.long
def test_imported_tiles_in_save(server, filled):
    got, _, _ = filled
    for r in got:
        tx, tz = r["tile"]
        assert server.rcon(f"donor peek {tx * 4 + 1} {tz * 4 + 1}") != "none", r


@pytest.mark.long
def test_scan_runs_within_its_limits(server, filled):
    # 日常回归里的功能断言（调度 11-09）：扫描跑起来了，每次发起的读盘不超过上限，时间预算的配置生效。
    # 扫描有多快是性能测试（下面的 test_scan_is_cheap，标 slow，只在单独跑 benchmark 时判）。
    _, s, _ = filled
    scan, cfg = s["scan"], s["config"]
    assert scan["count"] > 0 and scan["recent"], scan
    assert all(len(r) == 3 for r in scan["recent"]), scan["recent"][:3]
    assert all(r[2] <= cfg["demandReadsPerScan"] for r in scan["recent"]), (cfg["demandReadsPerScan"], scan["recent"])
    assert cfg["demandScanBudgetMicros"] > 0, cfg


@pytest.mark.slow
def test_scan_is_cheap(server, filled):
    # 11-09 改成 slow（调度决定，看过数据后改）：7 个槽位并行跑时启动段第 2 次扫描 CPU 5838 µs > 5000，计时断言在这种负载下不可靠；
    # 按项目规则性能测试标 slow、不进日常回归，只在单独跑 benchmark 时判，阈值不动。
    # 调度 11-08 定：扫描每秒一次。启动段的几毫秒是 JIT 预热和生成线程抢核，只出现一次；稳定段代表常态。
    # - 稳定段（出现 STARTUP_S 秒以后）：单次扫描主线程 CPU 的 p95 < 1 ms
    # - 启动段：单次 CPU < 5 ms、墙钟 < 10 ms（半个 tick）
    _, s, startup_count = filled
    scan = s["scan"]
    recent = scan["recent"]
    first = scan["count"] - len(recent) + 1  # recent[0] 是第几次扫描（从 1 数）
    assert first == 1, f"recent 只留最近 {len(recent)} 次，启动段被挤掉了：{scan}"
    startup = [r for i, r in enumerate(recent, first) if i <= startup_count]
    steady = sorted(r[1] for i, r in enumerate(recent, first) if i > startup_count)
    p95 = steady[min(len(steady) - 1, int(len(steady) * 0.95))]
    report = {"startup": startup, "steady_cpu_us": steady, "steady_cpu_p95_us": p95,
              "startup_max_cpu_us": max((r[1] for r in startup), default=0),
              "startup_max_wall_us": max((r[0] for r in startup), default=0), "scan": scan}
    print(json.dumps(report))
    if server.artifact_dir:
        server.artifact_dir.mkdir(parents=True, exist_ok=True)
        (server.artifact_dir / "scan-cost.json").write_text(json.dumps(report, indent=2) + "\n")
    assert scan["count"] > 0
    assert len(steady) >= 10, report
    assert p95 < 1000, report
    assert report["startup_max_cpu_us"] < 5000, report
    assert report["startup_max_wall_us"] < 10000, report


@pytest.mark.long
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
