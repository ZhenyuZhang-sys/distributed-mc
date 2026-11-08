"""第 0 步：Chunky 能生成一小块地形，存档读取器能读出来。"""

import re

import pytest

from mcd import wait_until

# 以 (0, 0) 为中心、半径 48 格的方形：chunk -3..2，共 6×6 = 36 个（以 Chunky 报告为准）
RADIUS = 48


@pytest.fixture(scope="module")
def generated(server):
    for cmd in ["chunky world minecraft:overworld", "chunky shape square",
                "chunky center 0 0", f"chunky radius {RADIUS}", "chunky start"]:
        server.rcon(cmd)

    def finished():
        m = re.search(r"Task finished for minecraft:overworld\. Processed: (\d+) chunks", server.logs())
        return m and int(m.group(1))

    count = wait_until(finished, timeout=300, interval=2, what="Chunky 完成")
    return count


def test_chunky_finishes(generated):
    assert generated > 0


def test_region_file_written(server, generated):
    # 存档要落盘才看得到，先 save-all
    server.rcon("save-all flush")
    assert (server.world_dir / "region" / "r.0.0.mca").exists()


def test_reader_sees_full_chunks(server, generated):
    pytest.skip("等 analysis/mca 存档读取器（kexu）：读出区域内每个 chunk，状态都是 minecraft:full")

