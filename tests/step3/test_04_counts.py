"""第 3 步（3.5）：导入的 chunk 不算成"服务器自己生成的"。

- full_resumed：从存档读进来的半成品被服务器接着做完（导入的 tile 就是这种）。
- /bench track on 之后，/bench chunk <x> <z> 返回这个 chunk 在服务器上跑过几次 noise、features。
"""

import json

import pytest

from mcd import wait_until

TILE = (-40, -40)   # 远离出生点，也不和其他测试的 tile 相邻


def stats(server) -> dict:
    return json.loads(server.rcon("bench stats"))


def ran(server, x, z) -> dict:
    return json.loads(server.rcon(f"bench chunk {x} {z}"))


@pytest.fixture(scope="module")
def visited(server, worker):
    """worker 导出 tile，服务器导入，然后把假玩家放到 tile 中心，等 tile 变成成品。"""
    exp = json.loads(worker.rcon(f"donor export {TILE[0]} {TILE[1]}"))
    res = json.loads(server.rcon(f"donor import {exp['file']}"))
    assert res["written"] == 36, res
    server.rcon("bench track on")
    server.rcon("bench reset")
    x, z = TILE[0] * 64 + 32, TILE[1] * 64 + 32
    server.rcon(f"player countbot spawn at {x} 200 {z} facing 0 0 in minecraft:overworld in spectator")
    tile = [(cx, cz) for cx in range(TILE[0] * 4, TILE[0] * 4 + 4) for cz in range(TILE[1] * 4, TILE[1] * 4 + 4)]

    def all_full():
        server.rcon("save-all flush")
        return all(server.rcon(f"donor peek {cx} {cz}") == "minecraft:full" for cx, cz in tile)

    try:
        wait_until(all_full, timeout=120, interval=3, what="tile 变成成品")
        yield tile, stats(server), {c: ran(server, *c) for c in tile}
    finally:
        server.rcon("player countbot kill")
        server.rcon("bench track off")


def test_new_counters_present(server):
    s = stats(server)
    assert "full_resumed" in s and "features" in s, s


def test_tile_chunks_not_regenerated(visited):
    _, _, per_chunk = visited
    bad = {c: r for c, r in per_chunk.items() if r["noise"] or r["features"]}
    assert not bad, f"服务器给导入的 chunk 又跑了生成：{bad}"


def test_tile_counted_as_resumed(visited):
    _, s, _ = visited
    assert s["full_resumed"] >= 16, s


def test_server_still_generates_around(visited):
    # 视野里其他地方是服务器自己生成的，计数照常
    _, s, _ = visited
    assert s["full_generated"] > 0 and s["noise"] > 0 and s["features"] > 0, s


def test_track_off_by_default_after(server, visited):
    server.rcon("bench track off")
    server.rcon("bench reset")
    assert ran(server, *visited[0][0]) == {"tracking": False, "noise": 0, "features": 0}
