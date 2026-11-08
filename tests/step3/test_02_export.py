"""第 3 步（3.3）：worker 上的 /donor export <tx> <tz>。

把 tile 生成到 initialize_light（地形、表面、洞穴、地物都做完，只差光照、生怪和 full），
连同紧挨着的一圈写成 tile_<tx>_<tz>.nbt 放进交换目录。tile 放地物时会写进这一圈。
命令返回一行 JSON：{"tile": [tx, tz], "file": ..., "chunks": 36, "rings": [{状态: 个数}, ...]}。

不导出到 spawn：原版的票等级只能让 chunk 停在 initialize_light 或直接做成 full，停不在 spawn。
"""

import json

import pytest

from mcd import EXCHANGE, wait_until

# 远离出生点，tile 坐标用负数，顺便验证负数
TILE = (40, -30)

# 生成阶段的先后顺序（1.21.1）
ORDER = ["minecraft:empty", "minecraft:structure_starts", "minecraft:structure_references",
         "minecraft:biomes", "minecraft:noise", "minecraft:surface", "minecraft:carvers",
         "minecraft:features", "minecraft:initialize_light", "minecraft:light",
         "minecraft:spawn", "minecraft:full"]


@pytest.fixture(scope="module")
def exported(worker):
    out = worker.rcon(f"donor export {TILE[0]} {TILE[1]}")
    return json.loads(out)


def test_export_file_written(exported):
    f = EXCHANGE / f"tile_{TILE[0]}_{TILE[1]}.nbt"
    assert exported["file"] == f.name
    assert f.is_file() and f.stat().st_size > 0
    assert not list(EXCHANGE.glob("*.tmp")), "留下了临时文件"


def test_export_has_tile_and_one_ring(exported):
    assert exported["tile"] == list(TILE)
    assert exported["chunks"] == 36
    rings = exported["rings"]
    assert [sum(r.values()) for r in rings] == [16, 20], rings


def test_tile_chunks_at_initialize_light(exported):
    assert exported["rings"][0] == {"minecraft:initialize_light": 16}, exported["rings"][0]


def test_ring_statuses(exported):
    # 这一圈至少到 carvers（tile 放地物要它们），不能是成品
    for status in exported["rings"][1]:
        assert ORDER.index("minecraft:carvers") <= ORDER.index(status) < ORDER.index("minecraft:full"), status


def test_refuses_tile_with_full_chunks(worker):
    # 有玩家的地方 chunk 已经是成品，生物存在别处，导出会丢东西，要拒绝。
    # tile (-20, 20) 是 chunk x -80..-77、z 80..83，中心在方块 (-1248, 1312)
    worker.rcon("player donorbot spawn at -1248 200 1312 facing 0 0 in minecraft:overworld in spectator")
    try:
        wait_until(lambda: "donorbot" in worker.rcon("list").lower(), timeout=30, what="假玩家上线")
        out = wait_until(lambda: "refused" in (o := worker.rcon("donor export -20 20")).lower() and o,
                         timeout=30, interval=2, what="导出被拒绝")
        assert "full" in out.lower(), out
        assert not (EXCHANGE / "tile_-20_20.nbt").exists()
    finally:
        worker.rcon("player donorbot kill")


def test_same_tile_identical_across_worlds():
    pytest.skip("等 analysis/mca 存档读取器（kexu）：同一个 tile 在两个全新 worker 世界里导出，tile 内逐列 hash 一致")
