"""11-09 找需求先看正前方（demandForwardFirst）：直飞时 /donor status 报前方组的候选数和看完的比例，扫描墙钟不涨。

默认关。开着的对照：MCD_JVM_EXTRA=-Dmcdonate.demandForwardFirst=1 再跑一遍这条，比较打印出来的 scan 数字（粗测）。
"""
import json
import time

import pytest

from bench import layer2

pytestmark = pytest.mark.long

SPEED = 33.5      # 烟花平飞
SECONDS = 40


def test_forward_counts_during_straight_flight(server, worker, tmp_path):
    base = server.artifact_dir or tmp_path
    on = json.loads(server.rcon("donor status"))["config"]["demandForwardFirst"]
    assert server.rcon("donor reset").startswith("reset")
    assert server.rcon("donor feature deadline on").startswith("features")
    server.rcon("donor demand on")
    before = json.loads(server.rcon("donor status"))["scan"]
    try:
        layer2.fly(server, run=f"fwd-{int(time.time())}", player="fwdbot", start=(-32000.5, 48008.5), speed=SPEED,
                   seconds=SECONDS, out_dir=base / f"forward-scan-{'on' if on else 'off'}")
    finally:
        server.rcon("donor demand off")
        server.rcon("donor feature deadline off")
    s = json.loads(server.rcon("donor status"))
    scan, fw = s["scan"], s["scan"]["forward"]
    print(json.dumps({"forwardFirst": on, "scans": scan["count"] - before["count"], "avgMicros": scan["avgMicros"],
                      "maxMicros": scan["maxMicros"], "avgCpuMicros": scan["avgCpuMicros"], "forward": fw,
                      "budgetMicros": s["config"].get("demandScanBudgetMicros")}))
    assert fw["on"] is on
    assert fw["avgCandidates"] > 0, f"直飞时应该有前方候选：{fw}"
    assert 0 < fw["visitedRatio"] <= 1, fw
    if on:
        # 开着时前方组先扫：每次扫描的前方组应该都看得完（候选只占一小部分）
        assert fw["visitedRatio"] >= 0.9, fw
