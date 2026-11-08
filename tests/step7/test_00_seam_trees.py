"""第 7 步 7.0b：导入边界一圈的树差异，是不是系统性地高于原版自己的不确定性（docs/step7-plan.md）。

12 个树多的位置（tests/step7/data/seam-sites.json），每个位置：
- 导入组：worker 导出 tile，服务器导入；服务器和 worker 都把 tile 外 3 圈（10×10 chunk）做成成品。服务器 vs worker。
- 原版组：worker2（同 seed 的另一个世界，不导入）也生成同一块。worker2 vs worker。
参照都是 worker 的世界。按圈数树类方块差异（analysis/seam.py，区域最外一行不算）。

判定（事先定死，调度 11-08）：边界一圈"导入组 > 原版组"的位置数做配对符号检验，另算跨位置合计比值的 bootstrap 区间。
- 真问题：p < 0.01（12 个里 ≥11 个偏高）→ 测试失败，按截断/多出/错位分类给出证据。
- 可疑：9～10 个偏高，或区域下限 > 1 → 测试通过，但打醒目警告，结果写进 log.md 的"待你决定"。
- 其余：干净，只记录。
明细写进证据目录 seam-trees.json。
"""

import json
import warnings
from pathlib import Path

import pytest

from analysis.seam import bootstrap_ratio, ring_density, ring_of, sign_test, tree_direction

pytestmark = pytest.mark.long

SITES = json.loads((Path(__file__).parent / "data" / "seam-sites.json").read_text())["sites"]
MARGIN = 3


def area(tile):
    tx, tz = tile
    return tx * 4 - MARGIN, tz * 4 - MARGIN, 4 + 2 * MARGIN


def test_seam_trees(server, worker, worker2, tmp_path_factory):
    assert len(SITES) >= 12, "至少 12 个位置"
    # 1. 每个位置：导入 + 三边生成
    for s in SITES:
        tx, tz = s["tile"]
        for dx in (-1, 4):
            for dz in (-1, 4):
                assert server.rcon(f"donor peek {tx * 4 + dx} {tz * 4 + dz}") == "none", f"{s} 不干净"
        exp = json.loads(worker.rcon(f"donor export {tx} {tz}"))
        res = json.loads(server.rcon(f"donor import {exp['file']}"))
        assert res["written"] == 36, (s, res)
        x0, z0, size = area(s["tile"])
        for srv in (server, worker, worker2):
            srv.chunky_square(x0, z0, size)
    base = tmp_path_factory.mktemp("seamtrees")
    snap = {name: srv.snapshot(base / name) for name, srv in (("server", server), ("worker", worker), ("worker2", worker2))}

    # 2. 按圈统计
    rows = []
    for s in SITES:
        x0, z0, size = area(s["tile"])
        imp = ring_density(snap["server"], snap["worker"], [s["tile"]], x0, z0, size)
        van = ring_density(snap["worker2"], snap["worker"], [s["tile"]], x0, z0, size)
        ring1 = [(x, z) for x in range(x0 + 1, x0 + size - 1) for z in range(z0 + 1, z0 + size - 1)
                 if ring_of(x, z, [s["tile"]]) == 1]
        rows.append({
            "site": s, "import": imp, "vanilla": van,
            "ring1_tree": [imp.get(1, {}).get("tree", 0), van.get(1, {}).get("tree", 0)],
            "ring1_direction_import": tree_direction(snap["server"], snap["worker"], ring1),
            "ring1_direction_vanilla": tree_direction(snap["worker2"], snap["worker"], ring1),
            "strict": [sum(v["strict"] for v in imp.values()), sum(v["strict"] for v in van.values())],
        })
    pairs = [tuple(r["ring1_tree"]) for r in rows]
    sign = sign_test(pairs)
    boot = bootstrap_ratio(pairs)
    if sign["p"] < 0.01:
        verdict = "problem"
    elif sign["higher"] >= 9 or boot["low"] > 1:
        verdict = "suspicious"
    else:
        verdict = "clean"
    report = {"verdict": verdict, "sign_test": sign, "bootstrap": boot, "sites": rows}
    summary = {"verdict": verdict, "sign_test": sign, "bootstrap": boot,
               "ring1_tree": pairs, "direction_import": [r["ring1_direction_import"] for r in rows]}
    print(json.dumps(summary, ensure_ascii=False))
    if server.artifact_dir:
        server.artifact_dir.mkdir(parents=True, exist_ok=True)
        (server.artifact_dir / "seam-trees.json").write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n")
    # 严格层：两组都必须是 0（导入组靠第 3 步的两层比较，原版组是清单完整性的复查）
    assert all(r["strict"] == [0, 0] for r in rows), [(r["site"], r["strict"]) for r in rows if r["strict"] != [0, 0]]
    if verdict == "suspicious":
        warnings.warn(f"7.0b 可疑：边界一圈树差异 {sign['higher']}/{sign['untied']} 个位置导入组更高，"
                      f"合计比值 {boot['ratio']:.2f}（95% 区间 {boot['low']:.2f}～{boot['high']:.2f}），写进'待你决定'")
    assert verdict != "problem", f"导入边界一圈的树差异系统性地高于原版：{json.dumps(summary, ensure_ascii=False)}"
