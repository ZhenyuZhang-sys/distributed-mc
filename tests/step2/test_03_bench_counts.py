"""第 2 步（2.4）：bench mod 数清"新生成"和"从磁盘读"的 chunk。

区域在 (5000, 5000)，远离出生点。半径 256 格 ≈ 33×33 chunk，
比假玩家的视野（视距 10，21×21 chunk）大一圈。
"""

import json
import re

import pytest

from analysis.mca import read_chunk
from mcd import wait_until

CENTER = 5000
RADIUS = 256


def stats(server) -> dict:
    return json.loads(server.rcon("bench stats"))


def chunky_done(server) -> list[int]:
    # 同一轮里第 0 步也跑过 Chunky，所以返回所有完成记录，调用方看新增的那条
    return [int(n) for n in re.findall(
        r"Task finished for minecraft:overworld\. Processed: (\d+) chunks", server.logs())]


def wait_stable(server, key: str, timeout: float) -> int:
    """等计数不再变化（连续两次读数相同且大于 0）。"""
    last = {"v": -1}

    def check():
        v = stats(server)[key]
        same = v == last["v"] and v > 0
        last["v"] = v
        return same and v

    return wait_until(check, timeout=timeout, interval=3, what=f"{key} 稳定")


@pytest.fixture(scope="module")
def generated(server):
    """在新世界的 (5000, 5000) 用 Chunky 生成一块地，返回 (Chunky 报告的数, 生成后的计数)。"""
    server.rcon("bench reset")
    before = len(chunky_done(server))
    for cmd in ["chunky world minecraft:overworld", "chunky shape square",
                f"chunky center {CENTER} {CENTER}", f"chunky radius {RADIUS}", "chunky start"]:
        server.rcon(cmd)
    count = wait_until(lambda: len(d := chunky_done(server)) > before and d[-1],
                       timeout=900, interval=3, what="Chunky 完成")
    return count, stats(server)


def test_stats_is_json(server):
    s = stats(server)
    assert set(s) >= {"full_generated", "full_loaded", "noise"}, s


def test_reset(server):
    server.rcon("bench reset")
    s = stats(server)
    assert set(s) >= {"full_generated", "full_loaded", "full_resumed", "noise", "features"}, s
    assert all(v == 0 for v in s.values()), s


def test_generated_matches_chunky(generated):
    count, s = generated
    assert s["full_generated"] == count, (count, s)
    assert s["noise"] >= count, (count, s)


def test_reload_counts_as_loaded(server, generated):
    # 重启后把假玩家放到区域中心：视野内都是存档里现成的 chunk
    server.restart()
    server.rcon("bench reset")
    server.rcon(f"player bot2 spawn at {CENTER} 200 {CENTER} facing 0 0 "
                "in minecraft:overworld in spectator")
    loaded = wait_stable(server, "full_loaded", timeout=120)
    assert loaded >= 21 * 21, stats(server)
    assert stats(server)["full_generated"] == 0, stats(server)


def test_walking_out_generates(server, generated):
    # 接着上一个测试：假玩家走出区域，服务器开始自己生成
    server.rcon(f"tp bot2 {CENTER + RADIUS + 400} 200 {CENTER}")
    wait_until(lambda: stats(server)["full_generated"] > 0, timeout=120, interval=2,
               what="走出区域后开始生成")
    server.rcon("player bot2 kill")


def test_generated_matches_save(server, generated, tmp_path_factory):
    # Chunky 区域里的 chunk 在存档里全是成品，个数等于 full_generated
    count, s = generated
    snap = server.snapshot(tmp_path_factory.mktemp("counts") / "snapshot")
    lo, hi = (CENTER - RADIUS) // 16, (CENTER + RADIUS) // 16
    full = sum(1 for x in range(lo, hi + 1) for z in range(lo, hi + 1)
               if (c := read_chunk(snap, x, z)) is not None and c.status == "minecraft:full")
    assert full == count == s["full_generated"], (full, count, s)
