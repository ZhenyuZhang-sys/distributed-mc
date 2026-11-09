"""第 6 步：donor 导入的 chunk 要报来源事件（common.Provenance，第 4 步 4.3 的实时地图按它上色）。
DEV_PLAN 第 6 步的验收"实时地图上能看到 donor 生成的 chunk 走在玩家前面"靠它。

coordinator 把最近的事件留一份，/donor provenance 能看到：[[cx, cz, "donor", workerId], ...]。
正式的 provenance.jsonl 由 bench 的飞行记录写（kexu 4.3），进 main 后在飞行测试里再查文件。
"""

import json

from mcd import donor_imports, wait_until, worker_id

TILE = (-640, 640)   # 远离其他测试


def test_import_reports_donor_events(server, worker):
    tx, tz = TILE
    assert server.rcon(f"donor peek {tx * 4} {tz * 4}") == "none"
    wid = worker_id(server, "worker")
    before = len(donor_imports(server))
    assert server.rcon(f"donor assign {tx} {tz} {wid}") == "queued"
    wait_until(lambda: len(donor_imports(server)) > before, timeout=180, interval=1, what="结果回来")
    r = donor_imports(server)[-1]
    assert r["accepted"], r
    events = json.loads(server.rcon("donor provenance"))
    region = {(tx * 4 + dx, tz * 4 + dz) for dx in range(-1, 5) for dz in range(-1, 5)}
    donor = [e for e in events if e[2] == "donor" and (e[0], e[1]) in region]
    print(f"这个 tile 的来源事件 {len(donor)} 条", donor[:3])
    assert len(donor) == 36, donor
    assert all(e[3] == wid for e in donor), (wid, donor)
