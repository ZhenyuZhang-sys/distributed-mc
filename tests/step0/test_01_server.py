"""第 0 步：服务器起得来，各组件都在。"""

import re
import json

import pytest

from mcd import REPO_ROOT, wait_until
from analysis.mca import iter_chunk_positions, read_chunk, read_world_metadata


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
    profile = json.loads((REPO_ROOT / "tests/fixtures/mca/spawn_profile.json").read_text())
    snapshot = server.initial_snapshot
    meta = read_world_metadata(snapshot)
    assert meta.seed == server.seed == profile["seed"]
    assert meta.data_version == profile["data_version"]
    assert meta.level_name == server.level
    assert server.world_dir.is_dir()
    # 同 seed 的生成细节可能改变 SpawnY；边界使用本轮实际出生点。
    assert server.rcon("gamerule spawnChunkRadius") == profile["spawn_chunk_radius"]
    positions = list(iter_chunk_positions(snapshot))
    assert positions, "初始世界为空，不能当作出生区检查通过"
    sx, _, sz = meta.spawn
    cx, cz = sx // 16, sz // 16
    x0, z0, x1, z1 = profile["allowed_relative_bounds"]
    outside = [(x, z) for x, z in positions if not (x0 <= x-cx <= x1 and z0 <= z-cz <= z1)]
    assert not outside, f"出生区边界之外存在 chunk：{outside[:20]}"
    spawn = read_chunk(snapshot, cx, cz)
    assert spawn is not None and spawn.status == "minecraft:full"
    assert spawn.y_min <= meta.spawn[1] < spawn.y_max_exclusive
    sentinel = server.predecessor["sentinel"]
    assert read_chunk(server.predecessor["snapshot"], *sentinel).status == "minecraft:full"
    assert read_chunk(snapshot, *sentinel) is None, "新世界遗留了 A 的远处 chunk"
    assert not (server.world_dir.parent / server.predecessor["level"]).exists()
