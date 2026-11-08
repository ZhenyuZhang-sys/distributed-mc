"""第 8 步 8.0：用第 1 步的原版对，验证第 8 步计划里的三条推测（离线，不起服务器）。

1. 生物群系：同一 chunk 两次生成，每个 section 的 biome 完全一样。
2. 地表高度：每列"最高的非空气、非装饰类方块"的高度完全一样（遮掉树、雪、海带以后的 heightmap）。
3. 方向对称：有价值的矿（钻石、绿宝石、金、青金石、红石），一边多出来的个数大于另一边的 chunk，
   两个方向一样多（单侧符号检验的前提）。

判据在看数据之前写在 tests/step8/test_01_vanilla_checks.py。结果写进 tests/step8/data/vanilla-checks.json。
用法：python -m analysis.vanilla_checks --campaign baseline-20261108T154535
"""
from __future__ import annotations

import argparse
import collections
import csv
import json
import math
from pathlib import Path

import numpy as np

from analysis.decor_rules import load
from analysis.mca import read_chunk
from bench.models import ROOT, load_areas

AIRS = frozenset({'minecraft:air', 'minecraft:cave_air', 'minecraft:void_air'})
FLUIDS = frozenset({'minecraft:water', 'minecraft:lava'})
VALUED = ('diamond', 'emerald', 'gold', 'lapis', 'redstone')
OUT = ROOT / 'tests/step8/data/vanilla-checks.json'


def valued_ore(name: str):
    n = name.removeprefix('minecraft:').removeprefix('deepslate_')
    return next((o for o in VALUED if n == o + '_ore'), None)


def biome_names(chunk):
    out = {}
    for sy, (palette, indices) in chunk.biomes.items():
        out[sy] = tuple(str(palette[0 if indices is None else indices[i]]) for i in range(64))
    return out


def surface(chunk, rules, fluids_transparent=False) -> np.ndarray:
    """每列最高的非空气、非装饰类方块的 y（16×16，[z][x]）；整列都没有记 y_min-1。
    fluids_transparent：水和岩浆也当透明（修订版判据，看过数据后改的，见 test_01）。"""
    skip = AIRS | FLUIDS if fluids_transparent else AIRS
    height = np.full((16, 16), chunk.y_min - 1, dtype=np.int32)
    found = np.zeros((16, 16), dtype=bool)
    for sy in sorted(chunk.sections, reverse=True):
        palette, indices = chunk.sections[sy]
        solid = np.array([p.name not in skip and p.name not in rules.decor for p in palette])
        if not solid.any():
            continue
        grid = solid[np.zeros(4096, dtype=np.int64) if indices is None else np.asarray(indices)].reshape(16, 16, 16)  # [y][z][x]
        for y in range(15, -1, -1):
            hit = grid[y] & ~found
            height[hit] = sy * 16 + y
            found |= hit
        if found.all():
            break
    return height


VALUE_BLOCKS = ('minecraft:chest', 'minecraft:spawner', 'minecraft:trial_spawner')


def value_counts(chunk) -> dict:
    """每种有价值矿（深板岩变种合并）和箱子、刷怪笼在这个 chunk 里的个数。"""
    out = collections.Counter()
    for palette, indices in chunk.sections.values():
        names = [p.name for p in palette]
        keys = [valued_ore(n) or (n.removeprefix('minecraft:') if n in VALUE_BLOCKS else None) for n in names]
        if not any(keys):
            continue
        counts = collections.Counter(indices) if indices is not None else {0: 4096}
        for i, k in enumerate(keys):
            if k:
                out[k] += counts.get(i, 0)
    return out


def binomial_two_sided(k: int, n: int) -> float:
    if n == 0:
        return 1.0
    tail = sum(math.comb(n, i) for i in range(0, min(k, n - k) + 1)) / 2 ** n
    return min(1.0, 2 * tail)


def run(campaign: Path) -> dict:
    rules = load()
    areas = {a.id: a for a in load_areas(campaign / 'areas.json')}
    with (campaign / 'layer1.csv').open() as f:
        attempt = {r['run_id']: r['attempt_id'] for r in csv.DictReader(f)}
    with (campaign / 'determinism.csv').open() as f:
        pairs = [r for r in csv.DictReader(f) if r['comparison_group'] != 'repeat_2cpu']
    snap = lambda run_id: campaign / run_id / attempt[run_id] / 'snapshot'
    result = dict(campaign=campaign.name, pairs=[], valued_ores=list(VALUED))
    chunk_max = collections.defaultdict(int)  # 每个 chunk 的最大值（统计层阈值的起点）
    seen_worlds = set()
    for p in pairs:
        area = areas[p['area_id']]
        biome_sections = biome_diff_sections = height_columns = height_diff_columns = 0
        height_examples = []
        revised_diff_columns = 0
        revised_max_abs = 0
        height_max_abs = 0
        for cx, cz in area.positions():
            a, b = read_chunk(snap(p['run_a']), cx, cz), read_chunk(snap(p['run_b']), cx, cz)
            ba, bb = biome_names(a), biome_names(b)
            biome_sections += len(set(ba) | set(bb))
            biome_diff_sections += sum(ba.get(s) != bb.get(s) for s in set(ba) | set(bb))
            ha, hb = surface(a, rules), surface(b, rules)
            diff = np.argwhere(ha != hb)
            chunk_max['height_diff_columns'] = max(chunk_max['height_diff_columns'], len(diff))
            for run_id, ch in ((p['run_a'], a), (p['run_b'], b)):
                if (run_id, cx, cz) in seen_worlds:
                    continue
                seen_worlds.add((run_id, cx, cz))
                for k, v in value_counts(ch).items():
                    chunk_max['count.' + k] = max(chunk_max['count.' + k], v)
            height_columns += 256
            height_diff_columns += len(diff)
            height_max_abs = max(height_max_abs, int(np.abs(ha - hb).max()))
            ra, rb = surface(a, rules, True), surface(b, rules, True)
            revised_diff_columns += int((ra != rb).sum())
            revised_max_abs = max(revised_max_abs, int(np.abs(ra - rb).max()))
            for z, x in diff[:max(0, 5 - len(height_examples))]:
                height_examples.append(dict(cx=cx, cz=cz, x=int(x), z=int(z), a=int(ha[z, x]), b=int(hb[z, x])))
        result['pairs'].append(dict(area_id=p['area_id'], group=p['comparison_group'], run_a=p['run_a'], run_b=p['run_b'],
                                    biome_sections=biome_sections, biome_diff_sections=biome_diff_sections,
                                    height_columns=height_columns, height_diff_columns=height_diff_columns,
                                    height_examples=height_examples, height_max_abs=height_max_abs,
                                    revised_height_diff_columns=revised_diff_columns, revised_height_max_abs=revised_max_abs))
    # 方向：逐方块差异明细里数每个 chunk 两边多出来的有价值矿。
    keep = {(p['area_id'], p['run_a'], p['run_b']) for p in pairs}
    extra = collections.defaultdict(lambda: [0, 0])
    per_ore = collections.defaultdict(lambda: [0, 0])
    with (campaign / 'block-differences.jsonl').open() as f:
        for line in f:
            if not any(o in line for o in VALUED):
                continue
            d = json.loads(line)
            key = (d['area_id'], d['run_a'], d['run_b'])
            if key not in keep:
                continue
            a, b = valued_ore(d['before'][0]), valued_ore(d['after'][0])
            if a == b:
                continue
            if a:
                extra[key + ((d['cx'], d['cz']),)][0] += 1
                per_ore[key + ((d['cx'], d['cz']), a)][0] += 1
            if b:
                extra[key + ((d['cx'], d['cz']),)][1] += 1
                per_ore[key + ((d['cx'], d['cz']), b)][1] += 1
    direction = {}
    for area in list(areas) + ['all']:
        rows = [v for k, v in extra.items() if area in ('all', k[0]) and v[0] != v[1]]
        a_more = sum(v[0] > v[1] for v in rows)
        direction[area] = dict(chunks=len(rows), a_more=a_more, b_more=len(rows) - a_more,
                               p_two_sided=binomial_two_sided(a_more, len(rows)),
                               max_extra=max((max(v) for v in rows), default=0))
    result['direction'] = direction
    for k, v in extra.items():
        chunk_max['extra_valued'] = max(chunk_max['extra_valued'], max(v))
    for ore in VALUED:
        chunk_max['extra.' + ore] = max((max(v) for k, v in per_ore.items() if k[-1] == ore), default=0)
    result['chunk_max'] = dict(sorted(chunk_max.items()))
    result['chunk_max_worlds'] = len({w for w, _, _ in seen_worlds})
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--campaign', required=True)
    args = parser.parse_args()
    from bench.models import validate_campaign_id
    validate_campaign_id(args.campaign)
    data = run(ROOT / 'analysis/results/runs' / args.campaign)
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=1, sort_keys=True) + '\n')
    print(json.dumps(dict(direction=data['direction'],
                          biome=sum(p['biome_diff_sections'] for p in data['pairs']),
                          height={p['area_id'] + '/' + p['group'] + '/' + p['run_a']: [p['height_diff_columns'], p['revised_height_diff_columns'], p['revised_height_max_abs']] for p in data['pairs']}),
                     ensure_ascii=False, indent=1))
