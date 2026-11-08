"""第 3 步：存档读取器显式读半成品 chunk（incomplete=True）。不需要服务器。

worker 导出的、服务器导入的都是半成品（initialize_light、carvers），比较时要读它们的方块。
默认行为不变：不加 incomplete 时半成品照旧不能取方块和 hash。
"""

import gzip

import nbtlib as n
import pytest

from analysis.mca import IncompleteChunkError, read_chunk
from analysis.mca.chunk import decode_chunk
from mca_fixtures import chunk, section, world, write_chunk


def test_default_still_refuses_partial(tmp_path):
    root = world(tmp_path / "w")
    write_chunk(root, 2, 3, tag=chunk(2, 3, status="minecraft:carvers"))
    c = read_chunk(root, 2, 3)
    assert c.status == "minecraft:carvers"
    with pytest.raises(IncompleteChunkError):
        c.column_hashes()


def test_incomplete_decodes_blocks(tmp_path):
    root = world(tmp_path / "w")
    write_chunk(root, 2, 3, tag=chunk(2, 3, status="minecraft:initialize_light"))
    c = read_chunk(root, 2, 3, incomplete=True)
    assert c.status == "minecraft:initialize_light"
    assert c.block_state(0, -64, 0).name == "minecraft:stone"
    assert len(c.column_hashes()) == 256


def test_partial_and_full_same_blocks_same_hash(tmp_path):
    # 状态不进 hash：方块一样，hash 就一样
    # write_chunk 每次重写整个 region 文件，两个 chunk 放在不同 region
    root = world(tmp_path / "w")
    write_chunk(root, 0, 0, tag=chunk(0, 0, status="minecraft:carvers"))
    write_chunk(root, 32, 0, tag=chunk(32, 0, status="minecraft:full"))
    a = read_chunk(root, 0, 0, incomplete=True)
    b = read_chunk(root, 32, 0)
    assert a.column_hashes() == b.column_hashes()


def test_decode_from_tag_in_memory():
    # 导出文件里的 chunk 数据不在 region 文件里，直接从 NBT 解码
    tag = chunk(-5, 7, sections=[section(-4, ("minecraft:dirt",))], status="minecraft:carvers")
    c = decode_chunk(tag, -5, 7, incomplete=True)
    assert c.block_state(3, -60, 3).name == "minecraft:dirt"


def test_early_status_without_sections(tmp_path):
    # biomes 之前的半成品可能还没有方块 section，读成全空气而不是报错
    root = world(tmp_path / "w")
    tag = chunk(4, 4, sections=[], status="minecraft:structure_starts")
    write_chunk(root, 4, 4, tag=tag)
    c = read_chunk(root, 4, 4, incomplete=True)
    assert c.block_state(0, 0, 0).name == "minecraft:air"
