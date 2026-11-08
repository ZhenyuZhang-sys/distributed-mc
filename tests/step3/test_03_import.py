"""第 3 步（3.4）：服务器上的 /donor import <文件名>。

只读交换目录里的文件；内存里有的 chunk 永远不写，存档里已有的只有导入的更靠后才覆盖。
命令返回一行 JSON：{"file", "chunks", "written", "skipped": {"loaded": n, "exists": n}}。
/donor peek <x> <z>：从存档读某个 chunk 的状态（不加载），没有就是 "none"。
"""

import json

import pytest

from mcd import EXCHANGE, wait_until

# 两个都远离出生点、互不相邻
TILE = (30, 30)          # 正常导入
LOADED = (-30, 25)       # 服务器上放一个假玩家，chunk 已加载


def tile_center(t):
    return t[0] * 64 + 32, t[1] * 64 + 32


def export(worker, t) -> dict:
    return json.loads(worker.rcon(f"donor export {t[0]} {t[1]}"))


def import_(server, name) -> str:
    return server.rcon(f"donor import {name}")


def peek(server, x, z) -> str:
    return server.rcon(f"donor peek {x} {z}")


def saved_status(server, x, z) -> str:
    """先存盘再看：内存里的成品要存盘后存档里才看得到。"""
    server.rcon("save-all flush")
    return peek(server, x, z)


@pytest.fixture(scope="module")
def imported(server, worker):
    exp = export(worker, TILE)
    return exp, json.loads(import_(server, exp["file"]))


def test_import_writes_all(imported):
    exp, res = imported
    assert res["file"] == exp["file"]
    assert res["chunks"] == 36
    assert res["written"] == 36, res


def test_tile_chunk_status_in_save(server, imported):
    x, z = TILE[0] * 4 + 1, TILE[1] * 4 + 2
    assert peek(server, x, z) == "minecraft:initialize_light"
    assert peek(server, TILE[0] * 4 - 1, TILE[1] * 4) == "minecraft:carvers"  # 那一圈
    assert peek(server, TILE[0] * 4 - 2, TILE[1] * 4) == "none"  # 圈外没写


def test_import_twice_skips(server, imported):
    exp, _ = imported
    res = json.loads(import_(server, exp["file"]))
    assert res["written"] == 0 and res["skipped"]["exists"] == 36, res


def test_import_into_loaded_chunks_refused(server, worker):
    x, z = tile_center(LOADED)
    server.rcon(f"player importbot spawn at {x} 200 {z} facing 0 0 in minecraft:overworld in spectator")
    try:
        cx, cz = LOADED[0] * 4 + 1, LOADED[1] * 4 + 1
        wait_until(lambda: saved_status(server, cx, cz) == "minecraft:full", timeout=60, interval=2,
                   what="假玩家周围的 chunk 存成成品")
        exp = export(worker, LOADED)
        res = json.loads(import_(server, exp["file"]))
        assert res["written"] == 0 and res["skipped"]["loaded"] == 36, res
        assert f"导入 {exp['file']}：written=0 loaded=36" in server.logs(), "要记一条日志"
        assert peek(server, cx, cz) == "minecraft:full"
    finally:
        server.rcon("player importbot kill")


@pytest.mark.parametrize("name", ["../test-data/x.nbt", "/etc/passwd", "a/b.nbt", ".."])
def test_rejects_paths(server, name):
    out = import_(server, name)
    assert "refused" in out.lower(), out


def test_rejects_garbage_file(server):
    (EXCHANGE / "garbage.nbt").write_bytes(b"\x1f\x8b not really gzip")
    out = import_(server, "garbage.nbt")
    assert "refused" in out.lower(), out


def test_rejects_missing_file(server):
    out = import_(server, "tile_999_999.nbt")
    assert "refused" in out.lower(), out


def test_save_matches_export():
    pytest.skip("等 analysis/mca 存档读取器（kexu）：导入后存档里 tile 内的 chunk 和导出文件逐列 hash 完全一致")
