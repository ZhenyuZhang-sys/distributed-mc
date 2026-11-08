"""第 3 步（3.6）关卡：导入的 tile 和服务器自己生成的没有区别，边界接得上。

1. worker 导出 tile，服务器导入。
2. 服务器用 Chunky 把 tile 和外面三圈（10×10 chunk）全部做成成品：
   tile 由服务器接着做完，周围由服务器自己生成，边界在这里接上。
3. 对照：worker 也用 Chunky 生成同一块 10×10。worker 是同一个 seed 的另一个全新世界，
   跑的就是原版生成（导出不改方块），它就是原版的标准答案。
4. 两边拍快照，100 个 chunk 逐列比较，按圈统计差异。

原版不确定率要等第 1 步（kexu）测出来。拿到之前用临时阈值（见 ALLOWED_DIFF），
另外检查"边界不比外圈差"：外圈和导入无关，它的差异就是这一轮原版自己的噪声。
差异明细写进证据目录的 gate-diff.json。
"""

import json

import pytest

from analysis.mca import read_chunk

TILE = (45, -45)   # 远离出生点和其他测试的 tile
MARGIN = 3         # tile 外面比较几圈

# 允许的差异列比例。临时值（11-08）：第 1 步的原版不确定率出来后收紧。
# 第一次实测：tile 0%，边界 0.68%，第 2 圈 1.9%，第 3 圈 1.6%。
# 第二次：tile 0%，边界 1.09%，第 2 圈 4.4%，第 3 圈 2.6%。
# 差异几乎全是地下矿团（安山岩/闪长岩/花岗岩/煤矿）：相邻 chunk 放地物的先后不同，
# 重叠处谁后放谁覆盖，是原版自己的不确定性；离导入区域越远差异反而越多。
# 只用来判 tile 和边界：外圈和导入无关，每轮噪声在 2～4% 之间跳，只当基准。
ALLOWED_DIFF = 0.03
# 外圈只粗查，防止明显坏掉
OUTSIDE_SANITY = 0.10


def area():
    x0, z0 = TILE[0] * 4 - MARGIN, TILE[1] * 4 - MARGIN
    return x0, z0, 4 + 2 * MARGIN


def ring(x, z):
    dx = max(0, TILE[0] * 4 - x, x - (TILE[0] * 4 + 3))
    dz = max(0, TILE[1] * 4 - z, z - (TILE[1] * 4 + 3))
    return max(dx, dz)


def compare(a_root, b_root) -> dict:
    """逐列比较两个快照里的这块区域，按圈返回 {圈: [不同的列数, 总列数]}，以及不同的 chunk 明细。"""
    x0, z0, size = area()
    by_ring, details = {}, []
    for x in range(x0, x0 + size):
        for z in range(z0, z0 + size):
            a, b = read_chunk(a_root, x, z), read_chunk(b_root, x, z)
            assert a is not None and b is not None, (x, z)
            assert a.status == b.status == "minecraft:full", (x, z, a.status, b.status)
            diff = sum(1 for p, q in zip(a.column_hashes(), b.column_hashes()) if p != q)
            r = by_ring.setdefault(ring(x, z), [0, 0])
            r[0] += diff
            r[1] += 256
            if diff:
                details.append({"chunk": [x, z], "ring": ring(x, z), "columns": diff})
    return {"rings": by_ring, "details": details}


@pytest.fixture(scope="module")
def gate(server, worker, tmp_path_factory):
    exp = json.loads(worker.rcon(f"donor export {TILE[0]} {TILE[1]}"))
    res = json.loads(server.rcon(f"donor import {exp['file']}"))
    assert res["written"] == 36, res

    x0, z0, size = area()
    server.chunky_square(x0, z0, size)
    worker.chunky_square(x0, z0, size)
    base = tmp_path_factory.mktemp("gate")
    result = compare(server.snapshot(base / "server"), worker.snapshot(base / "worker"))
    if server.artifact_dir:
        server.artifact_dir.mkdir(parents=True, exist_ok=True)
        (server.artifact_dir / "gate-diff.json").write_text(json.dumps(result, indent=2) + "\n")
    return result, base


def _rate(rings, which) -> float:
    diff = sum(rings[r][0] for r in which if r in rings)
    total = sum(rings[r][1] for r in which if r in rings)
    return diff / total


def test_tile_matches_vanilla(gate):
    result, _ = gate
    rate = _rate(result["rings"], [0])
    print(f"tile 内差异列比例 {rate:.4%}")
    assert rate <= ALLOWED_DIFF, result["details"]


def test_boundary_matches_vanilla(gate):
    # 紧挨 tile 的一圈：导出时它是半成品，带着 tile 写过来的树和矿；服务器接着做完
    result, _ = gate
    rate = _rate(result["rings"], [1])
    print(f"边界一圈差异列比例 {rate:.4%}")
    assert rate <= ALLOWED_DIFF, result["details"]


def test_outer_rings_sane(gate):
    # 外圈和导入无关，是这一轮原版自己的噪声基准；只粗查
    result, _ = gate
    rate = _rate(result["rings"], [2, 3])
    print(f"外圈差异列比例 {rate:.4%}")
    assert rate <= OUTSIDE_SANITY, result["details"]


def test_boundary_not_worse_than_outside(gate):
    # 不依赖阈值：导入要是破坏了边界，边界一圈的差异会明显高过和导入无关的外圈
    result, _ = gate
    boundary, outside = _rate(result["rings"], [1]), _rate(result["rings"], [2, 3])
    print(f"边界 {boundary:.4%}，外圈 {outside:.4%}")
    assert boundary <= max(outside * 1.5, 0.005), (boundary, outside)


def test_restart_keeps_imported(server, gate):
    # 重启后导入的 chunk 照常加载，方块不变
    result, base = gate
    server.restart()
    after = server.snapshot(base / "server-after-restart")
    x0, z0, size = area()
    for x in range(TILE[0] * 4, TILE[0] * 4 + 4):
        for z in range(TILE[1] * 4, TILE[1] * 4 + 4):
            before = read_chunk(base / "server", x, z)
            now = read_chunk(after, x, z)
            assert now is not None and now.status == "minecraft:full", (x, z)
            assert now.column_hashes() == before.column_hashes(), (x, z)
