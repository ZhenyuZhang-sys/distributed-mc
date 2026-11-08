"""两层比较（analysis/mca/decor.py）的离线自测，不起服务器。"""

import json
from pathlib import Path
from types import MappingProxyType

from analysis.mca.chunk import AIR, BlockState, Chunk
from analysis.mca.decor import Diff, classify, compare_chunks, is_decor_diff, relative_check

STONE = BlockState("minecraft:stone")
WATER = BlockState("minecraft:water", (("level", "0"),))
LEAVES = BlockState("minecraft:oak_leaves", (("distance", "1"),))


def chunk(blocks: dict) -> Chunk:
    """blocks: {(x, y, z): BlockState}，其余是石头（y < 0）或空气。"""
    sections = {}
    for sy in range(-4, 20):
        base = STONE if sy < 0 else AIR
        palette = [base]
        idx = [0] * 4096
        for (x, y, z), st in blocks.items():
            if y // 16 == sy:
                if st not in palette:
                    palette.append(st)
                idx[(y % 16) * 256 + z * 16 + x] = palette.index(st)
        sections[sy] = (tuple(palette), None if len(palette) == 1 else tuple(idx))
    return Chunk(0, 0, "minecraft:full", 3955, MappingProxyType(sections))


def test_identical():
    d = compare_chunks(chunk({}), chunk({}))
    assert (d.strict, d.decor, d.blocks) == (0, 0, 98304)


def test_air_vs_stone_is_strict():
    d = compare_chunks(chunk({(1, 5, 2): STONE}), chunk({}))
    assert (d.strict, d.decor) == (1, 0)
    assert d.examples[0] == {"chunk": (0, 0), "x": 1, "z": 2, "y": 5, "a": "minecraft:stone", "b": "minecraft:air"}


def test_leaves_vs_air_is_decor():
    d = compare_chunks(chunk({(3, 70, 3): LEAVES}), chunk({}))
    assert (d.strict, d.decor) == (0, 1)


def test_terrain_pairs_counted_separately():
    # 列出的地形互换（泉水）单独计数；没列出的地形组合、空气对地形都是严格差异
    assert classify(STONE, WATER) == "terrain"
    assert classify(STONE, BlockState("minecraft:sand")) == "strict"
    assert classify(AIR, WATER) == "strict"
    assert classify(AIR, STONE) == "strict"
    d = compare_chunks(chunk({(0, -10, 0): WATER}), chunk({}))
    assert (d.strict, d.decor, d.terrain) == (0, 0, 1)


def test_relative_check():
    inside, outside = Diff(blocks=10**6, decor=100, terrain=0), Diff(blocks=10**6, decor=100, terrain=0)
    assert relative_check(inside, outside) == []
    inside.terrain = 50  # 没导入部分是 0，导入部分 5e-5 高于下限
    assert len(relative_check(inside, outside)) == 1
    inside.terrain, inside.decor = 0, 1000
    assert len(relative_check(inside, outside)) == 1


def test_property_only_difference():
    snowy = BlockState("minecraft:grass_block", (("snowy", "true"),))
    plain = BlockState("minecraft:grass_block", (("snowy", "false"),))
    assert is_decor_diff(snowy, plain)
    # 不在清单里的方块，属性不同也是严格差异
    a = BlockState("minecraft:oak_stairs", (("facing", "east"),))
    b = BlockState("minecraft:oak_stairs", (("facing", "west"),))
    assert not is_decor_diff(a, b)


def test_every_vanilla_difference_is_decor():
    # kexu 第 1 步：两个原版世界互比出现过的所有方块对，在严格层上必须都被放过（原版自己是 0）
    data = json.loads((Path(__file__).parent / "data" / "vanilla-diff-pairs.json").read_text())
    tagged = (("_", "x"),)  # 汇总不含属性：加一个假属性，让同名的也走"属性不同"的分支
    bad = [p for p in data["pairs"] if not is_decor_diff(BlockState(p["a"], tagged), BlockState(p["b"]))]
    assert not bad, bad[:10]
