"""第 2 步（2.5）：阶段计时本身不能明显拖慢生成。

同一个 seed 开两个全新世界，生成同一块区域：一次计时关，一次计时开。
每个世界先生成一块别处的地预热 JVM，再测目标区域。
这个测试会换世界，所以放在第 2 步最后。
"""

import time

import pytest

from mcd import SCRIPTS, chunky, run

WARMUP = (8000, 0, 64)
TARGET = (-8000, 0, 256)


def measure(server, timing: bool) -> float:
    """换一个全新世界，返回目标区域每秒生成的 chunk 数。"""
    level = f"t{int(time.time())}{'on' if timing else 'off'}"
    run(str(SCRIPTS / "new_world.sh"), level, str(server.seed), timeout=900)
    server.level = level
    server.rcon(f"bench timing {'on' if timing else 'off'}")
    chunky(server, *WARMUP)
    t0 = time.monotonic()
    count = chunky(server, *TARGET)
    return count / (time.monotonic() - t0)


@pytest.mark.slow
def test_timing_overhead_under_5_percent(server):
    off = measure(server, timing=False)
    on = measure(server, timing=True)
    print(f"计时关 {off:.1f} chunk/s，计时开 {on:.1f} chunk/s")
    assert on >= off * 0.95, f"计时开 {on:.1f}，关 {off:.1f} chunk/s"
