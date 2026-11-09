"""11-09 调度：派出到交回要 7～9 秒，worker 生成一个 tile 只要约 1.2 秒，拆开看时间去哪了。
/donor imports 每条带 seg：入队→派出→worker 收到→开始生成→生成完→打包完→coordinator 收到→主线程开始导入→导入完，
共 8 段（毫秒）。worker 和服务器在同一台机器上（同一个时钟），各段不为负，worker 上几段加起来等于派出到收到。
"""

import json

from mcd import donor_imports, wait_until, worker_id

KEYS = ["queue", "to_worker", "wait", "gen", "pack", "back", "main_wait", "import"]


def test_import_has_segments(server, worker):
    n = json.loads(server.rcon("donor status"))["config"]["tileSize"]
    tile = (-3100 // n, 3100 // n)   # 离其他测试远
    wid = worker_id(server, "worker")
    before = len(donor_imports(server))
    assert server.rcon(f"donor assign {tile[0]} {tile[1]} {wid}") == "queued"
    wait_until(lambda: len(donor_imports(server)) > before, timeout=180, interval=2, what="结果回来")
    r = donor_imports(server)[-1]
    print(f"导入记录 {r}")
    seg = r["seg"]
    assert sorted(seg) == sorted(KEYS), seg
    for k in KEYS:
        # queue：手动 assign 的也是先入队再派，知道入队时间
        assert seg[k] >= 0, (k, seg)
    assert seg["gen"] > 0, seg
    worker_side = seg["to_worker"] + seg["wait"] + seg["gen"] + seg["pack"] + seg["back"]
    assert abs(worker_side - r["ms"]) <= 5, (worker_side, r["ms"], seg)


def test_status_reports_frontier(server):
    s = json.loads(server.rcon("donor status"))
    assert "frontier" in s["scan"], s["scan"]
    assert {"importFrontier", "importMarginMs"} <= set(s["config"]), s["config"]
    assert "import_late" in s["stale"], s["stale"]
