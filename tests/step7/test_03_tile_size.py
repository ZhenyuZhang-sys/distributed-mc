"""7.5：tile 大小是配置项（tileSize），服务器和 donor 必须一样。这条测试不写死 4×4，大小从 /donor status 读，
所以用 MCD_JVM_EXTRA="-Dmcdonate.tileSize=8" 之类跑也成立（默认 4）。

派一个 tile 给 worker：导出的是 (n+2)×(n+2) 个 chunk，被接受，tile 里每个 chunk 都写进存档，tile 外一圈也写了。
"""

import json

from mcd import donor_imports, wait_until, worker_id


def test_one_tile_any_size(server, worker):
    n = json.loads(server.rcon("donor status"))["config"]["tileSize"]
    tile = (-3000 // n, 3000 // n)   # 离其他测试远：chunk (-3000, 3000) 一带
    x0, z0 = tile[0] * n, tile[1] * n
    for x in range(x0 - 1, x0 + n + 1):
        for z in range(z0 - 1, z0 + n + 1):
            assert server.rcon(f"donor peek {x} {z}") == "none", (x, z)
    wid = worker_id(server, "worker")
    before = len(donor_imports(server))
    assert server.rcon(f"donor assign {tile[0]} {tile[1]} {wid}") == "queued"
    wait_until(lambda: len(donor_imports(server)) > before, timeout=180, interval=2, what="结果回来")
    r = donor_imports(server)[-1]
    print(f"tileSize={n}，导入记录 {r}")
    assert r["accepted"] and r["written"] == (n + 2) ** 2, r
    for x in range(x0 - 1, x0 + n + 1):
        for z in range(z0 - 1, z0 + n + 1):
            assert server.rcon(f"donor peek {x} {z}") != "none", (x, z)
