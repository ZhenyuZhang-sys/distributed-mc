"""读取器的独立预期值测试，不需要服务器。"""

import os
import json
import struct
import subprocess
import sys
from pathlib import Path

import pytest

from analysis.mca import (
    CorruptChunkError, CorruptRegionError, IncompleteChunkError,
    UnsupportedFormatError, iter_chunk_positions, read_chunk, read_world_metadata,
)
from mca_fixtures import chunk, section, world, write_chunk


@pytest.mark.parametrize("cx,cz", [(0, 0), (31, 31), (32, 32), (-1, -1), (-32, -32), (-33, 33)])
@pytest.mark.parametrize("compression", [1, 2, 3])
def test_location_compression_and_read_only(tmp_path, cx, cz, compression):
    root = world(tmp_path)
    path = write_chunk(root, cx, cz, compression=compression)
    before = (path.read_bytes(), path.stat().st_mtime_ns)
    result = read_chunk(root, cx, cz)
    assert (result.cx, result.cz, result.status) == (cx, cz, "minecraft:full")
    assert result.block_state(0, -64, 0).name == "minecraft:stone"
    assert result.block_state(0, -48, 0).name == "minecraft:air"
    assert list(iter_chunk_positions(root)) == [(cx, cz)]
    assert (path.read_bytes(), path.stat().st_mtime_ns) == before


def test_missing_is_not_corruption(tmp_path):
    root = world(tmp_path)
    assert read_chunk(root, 99, 99) is None
    path = write_chunk(root)
    assert read_chunk(root, 1, 0) is None
    path.write_bytes(b"bad")
    with pytest.raises(CorruptRegionError):
        read_chunk(root, 0, 0)
    with pytest.raises(FileNotFoundError):
        read_chunk(root / "missing", 0, 0)


def test_incomplete_world_is_not_missing_chunk(tmp_path):
    root = world(tmp_path)
    (root / "region").rmdir()
    with pytest.raises(FileNotFoundError):
        read_chunk(root, 0, 0)
    with pytest.raises(FileNotFoundError):
        list(iter_chunk_positions(root))


def test_io_errors_are_not_missing_chunks(tmp_path, monkeypatch):
    root = world(tmp_path)
    path = write_chunk(root)
    original = Path.open

    def denied(self, *args, **kwargs):
        if self == path:
            raise PermissionError("denied region")
        return original(self, *args, **kwargs)

    monkeypatch.setattr(Path, "open", denied)
    with pytest.raises(PermissionError, match="denied region"):
        read_chunk(root, 0, 0)
    with pytest.raises(PermissionError, match="denied region"):
        list(iter_chunk_positions(root))


@pytest.mark.parametrize("location", [0x101, 0x200, 0x301])
def test_bad_sector_location(tmp_path, location):
    root = world(tmp_path)
    path = write_chunk(root)
    data = bytearray(path.read_bytes())
    struct.pack_into(">I", data, 0, location)
    path.write_bytes(data)
    with pytest.raises(CorruptRegionError):
        read_chunk(root, 0, 0)


def test_overlapping_allocations(tmp_path):
    root = world(tmp_path)
    path = write_chunk(root)
    data = bytearray(path.read_bytes())
    data[4:8] = data[0:4]
    path.write_bytes(data)
    with pytest.raises(CorruptRegionError):
        list(iter_chunk_positions(root))


def test_valid_unpadded_last_sector(tmp_path):
    root = world(tmp_path)
    path = write_chunk(root)
    raw = path.read_bytes()
    length = struct.unpack_from(">I", raw, 8192)[0]
    path.write_bytes(raw[:8192 + 4 + length])
    assert read_chunk(root, 0, 0).status == "minecraft:full"
    path.write_bytes(path.read_bytes()[:-1])
    with pytest.raises(CorruptRegionError):
        read_chunk(root, 0, 0)


def test_truncated_record_header(tmp_path):
    root = world(tmp_path)
    path = write_chunk(root)
    path.write_bytes(path.read_bytes()[:8194])
    with pytest.raises(CorruptRegionError):
        read_chunk(root, 0, 0)


@pytest.mark.parametrize("mutation,error", [
    ("length", CorruptRegionError), ("compression", UnsupportedFormatError),
    ("external", UnsupportedFormatError), ("payload", CorruptChunkError),
])
def test_invalid_payload(tmp_path, mutation, error):
    root = world(tmp_path)
    path = write_chunk(root)
    data = bytearray(path.read_bytes())
    if mutation == "length":
        struct.pack_into(">I", data, 8192, 100000)
    elif mutation == "compression":
        data[8196] = 127
    elif mutation == "external":
        data[8196] |= 128
    else:
        data[8197:8200] = b"bad"
    path.write_bytes(data)
    with pytest.raises(error):
        read_chunk(root, 0, 0)


def test_five_bit_no_span_and_axis_order(tmp_path):
    root = world(tmp_path)
    # 5 bit: 一个 long 12 项。i=11 -> palette 16；i=12 -> 7。
    words = [0] * 342
    words[0] = 0x0800000000000000
    words[1] = 0x7
    names = tuple(f"test:block_{i}" for i in range(17))
    write_chunk(root, tag=chunk(sections=[section(names=names, words=words)]))
    result = read_chunk(root, 0, 0)
    assert result.block_state(11, -64, 0).name == "test:block_16"
    assert result.block_state(12, -64, 0).name == "test:block_7"
    assert result.block_state(0, -64, 1).name == "test:block_0"
    assert result.block_state(0, -63, 0).name == "test:block_0"


def test_negative_long_and_y_boundaries(tmp_path):
    root = world(tmp_path)
    # 4 bit 的最高 nibble 是 15，NBT long 因此为负。
    words = [-1152921504606846976] + [0] * 255
    names = tuple(f"test:block_{i}" for i in range(16))
    write_chunk(root, tag=chunk(sections=[section(19), section(-4, names, words)]))
    result = read_chunk(root, 0, 0)
    assert result.block_state(15, -64, 0).name == "test:block_15"
    assert result.block_state(0, 319, 0).name == "minecraft:stone"
    for coords in [(16, 0, 0), (0, -65, 0), (0, 320, 0), (0, 0, -1)]:
        with pytest.raises(IndexError):
            result.block_state(*coords)


def test_distinct_x_z_y_indices(tmp_path):
    root = world(tmp_path)
    # i = 1*256 + 3*16 + 2 = 306；4 bit 的 word 19、shift 8。
    words = [0] * 256
    words[19] = 0x100
    write_chunk(root, tag=chunk(sections=[section(names=("minecraft:stone", "minecraft:dirt"), words=words)]))
    result = read_chunk(root, 0, 0)
    assert result.block_state(2, -63, 3).name == "minecraft:dirt"
    assert result.block_state(3, -63, 2).name == "minecraft:stone"
    assert result.block_state(2, -61, 1).name == "minecraft:stone"


def test_non_default_height_is_rejected(tmp_path):
    root = world(tmp_path)
    tag = chunk()
    tag["yPos"] = type(tag["yPos"])(0)
    write_chunk(root, tag=tag)
    with pytest.raises(UnsupportedFormatError, match="yPos"):
        read_chunk(root, 0, 0)


@pytest.mark.parametrize("kind", ["coordinate", "data_length", "palette_index", "duplicate_y", "missing_sections"])
def test_bad_chunk_schema(tmp_path, kind):
    root = world(tmp_path)
    tag = chunk()
    if kind == "coordinate":
        tag = chunk(cx=1)
    elif kind == "data_length":
        tag = chunk(sections=[section(names=("a", "b"), words=[0])])
    elif kind == "palette_index":
        tag = chunk(sections=[section(names=("a", "b"), words=[2] + [0] * 255)])
    elif kind == "duplicate_y":
        tag = chunk(sections=[section(), section()])
    else:
        del tag["sections"]
    write_chunk(root, tag=tag)
    with pytest.raises(CorruptChunkError):
        read_chunk(root, 0, 0)


def test_unsupported_version_and_partial(tmp_path):
    root = world(tmp_path)
    write_chunk(root, tag=chunk(version=1))
    with pytest.raises(UnsupportedFormatError):
        read_chunk(root, 0, 0)
    write_chunk(root, tag=chunk(status="minecraft:noise"))
    result = read_chunk(root, 0, 0)
    assert result.status == "minecraft:noise"
    with pytest.raises(IncompleteChunkError):
        result.column_hashes()


def test_metadata(tmp_path):
    root = world(tmp_path)
    before = {p: (p.read_bytes(), p.stat().st_mtime_ns) for p in root.rglob("*") if p.is_file()}
    meta = read_world_metadata(root)
    assert meta.seed == 424242
    assert meta.spawn == (-17, 80, 33)
    assert meta.data_version == 3955
    assert {p: (p.read_bytes(), p.stat().st_mtime_ns) for p in root.rglob("*") if p.is_file()} == before


def test_palette_reorder_and_single_block_difference(tmp_path):
    root = world(tmp_path)
    write_chunk(root, tag=chunk(sections=[section(names=("minecraft:stone", "minecraft:dirt"), words=[0] * 256)]))
    first = read_chunk(root, 0, 0)
    write_chunk(root, tag=chunk(sections=[section(names=("minecraft:dirt", "minecraft:stone"), words=[0x1111111111111111] * 256)]))
    reordered = read_chunk(root, 0, 0)
    assert first.block_hash() == reordered.block_hash()
    assert first.column_hashes() == reordered.column_hashes()
    words = [0x1111111111111110] + [0x1111111111111111] * 255
    write_chunk(root, tag=chunk(sections=[section(names=("minecraft:dirt", "minecraft:stone"), words=words)]))
    changed = read_chunk(root, 0, 0)
    assert changed.block_hash() != first.block_hash()
    assert [i for i, (a, b) in enumerate(zip(first.column_hashes(), changed.column_hashes())) if a != b] == [0]


def test_property_order_and_air_omission(tmp_path):
    root = world(tmp_path)
    write_chunk(root, tag=chunk(sections=[section(properties={"axis": "x", "waterlogged": "false"})]))
    one = read_chunk(root, 0, 0).block_hash()
    write_chunk(root, tag=chunk(sections=[section(properties={"waterlogged": "false", "axis": "x"}), section(0, ("minecraft:air",))]))
    assert read_chunk(root, 0, 0).block_hash() == one
    write_chunk(root, tag=chunk(sections=[section(properties={"axis": "z", "waterlogged": "false"})]))
    assert read_chunk(root, 0, 0).block_hash() != one


def test_hash_across_processes(tmp_path):
    root = world(tmp_path)
    write_chunk(root)
    expected = read_chunk(root, 0, 0).block_hash()
    code = 'import sys; from analysis.mca import read_chunk; print(read_chunk(sys.argv[1],0,0).block_hash())'
    result = subprocess.check_output([sys.executable, "-c", code, str(root)], text=True, env={**os.environ, "PYTHONHASHSEED": "77"})
    assert result.strip() == expected


def test_real_java_1_21_1_fixture():
    root = Path(__file__).resolve().parents[1] / "fixtures/mca/java-1.21.1"
    expected = json.loads((root / "expected.json").read_text())
    result = read_chunk(root, *expected["chunk"])
    assert result.data_version == expected["data_version"]
    assert result.status == "minecraft:full"
    x, y, z = expected["marker"]
    assert result.block_state(x % 16, y, z % 16).name == expected["block"]
    assert len(result.column_hashes()) == 256
