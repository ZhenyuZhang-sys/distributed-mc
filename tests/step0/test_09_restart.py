"""重启必须保留人为标记和未加载远处 chunk，不能只验证 seed。"""

import pytest
from analysis.mca import read_chunk, read_world_metadata
from mcd import compose


@pytest.mark.long
def test_restart_keeps_world(server):
    meta = read_world_metadata(server.initial_snapshot)
    sx, _, sz = meta.spawn
    marker_y = 200
    server.rcon(f"setblock {sx} {marker_y} {sz} minecraft:diamond_block")
    assert "Test passed" in server.rcon(f"execute if block {sx} {marker_y} {sz} minecraft:diamond_block")
    distant = (sx // 16 + 256, sz // 16 + 256)
    server.chunky_square(*distant, 2)
    server.rcon("save-all flush")
    compose("stop", timeout=180)
    before = server._copy_world(server.artifact_dir / "restart-before")
    server.archive_logs(server.artifact_dir / "restart-stop")
    chunk_before = read_chunk(before, *distant)
    assert chunk_before is not None and chunk_before.status == "minecraft:full"
    assert read_chunk(before, sx // 16, sz // 16).block_state(sx % 16, marker_y, sz % 16).name == "minecraft:diamond_block"
    compose("up", "-d", "--wait", "--wait-timeout", "600", timeout=660)
    server.sealed = False
    assert server.health() == "healthy"
    assert server.rcon("seed") == f"Seed: [{server.seed}]"
    after = server.snapshot(server.artifact_dir / "restart-after")
    chunk_after = read_chunk(after, *distant)
    assert chunk_after is not None and chunk_after.status == "minecraft:full"
    assert chunk_after.block_hash() == chunk_before.block_hash()
    assert chunk_after.column_hashes() == chunk_before.column_hashes()
    marker_chunk = read_chunk(after, sx // 16, sz // 16)
    assert marker_chunk.block_state(sx % 16, marker_y, sz % 16).name == "minecraft:diamond_block"
    assert server.error_lines() == []
