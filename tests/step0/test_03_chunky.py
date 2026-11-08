"""第 0 步：出生点附近的小区域，每个目标都能从存档读出来。"""

import pytest

from analysis.mca import iter_chunk_positions, read_chunk, read_world_metadata


@pytest.fixture(scope="module")
def generated(server):
    meta = read_world_metadata(server.initial_snapshot)
    cx, cz = meta.spawn[0] // 16, meta.spawn[2] // 16
    result = server.chunky_square(cx - 3, cz - 3, 6)
    result["snapshot"] = server.snapshot(server.artifact_dir / "chunky")
    return result


def test_chunky_finishes(generated):
    assert generated["count"] == len(generated["expected"]) == 36


def test_region_file_written(server, generated):
    snapshot = generated["snapshot"]
    for cx, cz in generated["expected"]:
        assert (snapshot / "region" / f"r.{cx // 32}.{cz // 32}.mca").is_file()


def test_reader_sees_full_chunks(server, generated):
    snapshot = generated["snapshot"]
    bad = []
    for cx, cz in generated["expected"]:
        chunk = read_chunk(snapshot, cx, cz)
        if chunk is None or chunk.status != "minecraft:full":
            bad.append((cx, cz, None if chunk is None else chunk.status))
    assert not bad, f"目标 chunk 不完整：{bad}"
    pos = generated["expected"][0]
    first, second = read_chunk(snapshot, *pos), read_chunk(snapshot, *pos)
    assert first.block_hash() == second.block_hash()
    assert len(first.column_hashes()) == 256
    assert first.column_hashes() == second.column_hashes()
    positions = set(iter_chunk_positions(snapshot))
    absent = (max(x for x, z in positions) + 1024, max(z for x, z in positions) + 1024)
    assert absent not in positions
    assert read_chunk(snapshot, *absent) is None

