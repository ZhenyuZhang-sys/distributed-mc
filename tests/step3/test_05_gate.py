"""第 3 步（3.6）关卡：导入的 tile 和服务器自己生成的没有区别，边界接得上。

1. worker 导出 tile，服务器导入。
2. 服务器用 Chunky 把 tile 和外面三圈（10×10 chunk）全部做成成品：
   tile 由服务器接着做完，周围由服务器自己生成，边界在这里接上。
3. 对照：worker 也用 Chunky 生成同一块 10×10。worker 是同一个 seed 的另一个全新世界，
   跑的就是原版生成（导出不改方块），它就是原版的标准答案。
4. 两边拍快照，100 个 chunk 逐块比较，按圈统计差异。

比较分两层（analysis/mca/decor.py，kexu 第 1 步的原版不确定率之后定的）：
- 严格层：地物放的"装饰类"方块（树、草、矿团、sculk、海带……）和列出的几对地形互换（泉水、海底沙盘……）
  不算，其余方块必须逐块一致，差异为 0。
  外圈和导入无关，是两个原版世界互比：它在严格层上也必须是 0，否则说明清单不全。
- 统计层：tile 和边界的装饰类差异率、地形互换率，各自不高于外圈（同一轮、同一块地方的原版互比）的 1.5 倍。
差异明细写进证据目录的 gate-diff.json。
"""

import json

import pytest

from analysis.mca import read_chunk
from analysis.mca.decor import Diff, compare_chunks, relative_check

TILE = (45, -45)   # 远离出生点和其他测试的 tile
MARGIN = 3         # tile 外面比较几圈

# 统计层的余量：装饰类差异率不高于外圈的这么多倍
DECOR_FACTOR = 1.5


def area():
    x0, z0 = TILE[0] * 4 - MARGIN, TILE[1] * 4 - MARGIN
    return x0, z0, 4 + 2 * MARGIN


def ring(x, z):
    dx = max(0, TILE[0] * 4 - x, x - (TILE[0] * 4 + 3))
    dz = max(0, TILE[1] * 4 - z, z - (TILE[1] * 4 + 3))
    return max(dx, dz)


def compare(a_root, b_root) -> dict:
    """逐块比较两个快照里的这块区域，按圈返回 {圈: Diff}，以及有严格差异或装饰差异的 chunk 明细。"""
    x0, z0, size = area()
    by_ring, details = {}, []
    for x in range(x0, x0 + size):
        for z in range(z0, z0 + size):
            a, b = read_chunk(a_root, x, z), read_chunk(b_root, x, z)
            assert a is not None and b is not None, (x, z)
            assert a.status == b.status == "minecraft:full", (x, z, a.status, b.status)
            d = compare_chunks(a, b)
            by_ring.setdefault(ring(x, z), Diff(examples=[])).add(d)
            if d.strict or d.decor or d.terrain:
                details.append({"chunk": [x, z], "ring": ring(x, z), "strict": d.strict, "decor": d.decor,
                                "terrain": d.terrain, "examples": d.examples})
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
        rings = {r: {"blocks": d.blocks, "strict": d.strict, "decor": d.decor, "terrain": d.terrain}
                 for r, d in result["rings"].items()}
        (server.artifact_dir / "gate-diff.json").write_text(
            json.dumps({"rings": rings, "details": result["details"]}, indent=2) + "\n")
    return result, base


def _sum(rings, which) -> Diff:
    d = Diff(examples=[])
    for r in which:
        if r in rings:
            d.add(rings[r])
    return d


def _strict_examples(result, which):
    return [c for c in result["details"] if c["ring"] in which and c["strict"]][:10]


def test_tile_matches_vanilla(gate):
    result, _ = gate
    d = _sum(result["rings"], [0])
    print(f"tile 内：严格差异 {d.strict}，装饰类差异率 {d.decor_rate:.4%}")
    assert d.strict == 0, _strict_examples(result, [0])


def test_boundary_matches_vanilla(gate):
    # 紧挨 tile 的一圈：导出时它是半成品，带着 tile 写过来的树和矿；服务器接着做完
    result, _ = gate
    d = _sum(result["rings"], [1])
    print(f"边界一圈：严格差异 {d.strict}，装饰类差异率 {d.decor_rate:.4%}")
    assert d.strict == 0, _strict_examples(result, [1])


def test_outer_rings_strict_zero(gate):
    # 外圈和导入无关，是两个原版世界互比：严格层必须是 0，否则是装饰类清单不全（补 analysis/mca/decor.py）
    result, _ = gate
    d = _sum(result["rings"], [2, 3])
    print(f"外圈（原版互比）：严格差异 {d.strict}，装饰类差异率 {d.decor_rate:.4%}")
    assert d.strict == 0, _strict_examples(result, [2, 3])


def test_decor_not_worse_than_outside(gate):
    # 统计层：导入的 tile 和边界，装饰类差异率、地形互换率各自不高于同一轮原版互比（外圈）的 1.5 倍
    result, _ = gate
    inside, outside = _sum(result["rings"], [0, 1]), _sum(result["rings"], [2, 3])
    print(f"装饰类：tile+边界 {inside.decor_rate:.4%}，外圈 {outside.decor_rate:.4%}；"
          f"地形互换：tile+边界 {inside.terrain_rate:.5%}，外圈 {outside.terrain_rate:.5%}")
    bad = relative_check(inside, outside, DECOR_FACTOR)
    assert not bad, bad


@pytest.mark.long
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
