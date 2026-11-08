"""第 0 步：服务器起得来，各组件都在。"""

import re

import pytest

from mcd import wait_until


def test_healthy(server):
    assert server.health() == "healthy"


@pytest.mark.parametrize(
    "mod",
    [
        r"fabricloader 0\.16\.14",
        r"fabric-api 0\.116\.6\+1\.21\.1",
        r"carpet \S+",
        r"chunky \S+",
        r"spark \S+",
    ],
)
def test_mod_loaded(server, mod):
    # Fabric 启动时会列出所有 mod，每行像 "	- carpet 1.4.147"
    assert re.search(rf"^\s*- {mod}", server.logs(), re.M), f"日志里没有加载 {mod}"


def test_seed(server):
    assert server.rcon("seed") == f"Seed: [{server.seed}]"


def test_spark_tps(server):
    # spark 不通过 RCON 返回结果，而是异步写进服务器日志
    server.rcon("spark tps")
    wait_until(lambda: "TPS from last" in server.logs(), timeout=15, what="spark 输出 TPS")


def test_chunky_help(server):
    out = server.rcon("chunky help")
    assert "start" in out.lower(), out


def test_new_world_has_only_spawn_chunks(server):
    # 要在 Chunky 生成之前检查，所以放在 01 里
    pytest.skip("等 analysis/mca 存档读取器（kexu）：新世界里只有出生点附近的 chunk")
