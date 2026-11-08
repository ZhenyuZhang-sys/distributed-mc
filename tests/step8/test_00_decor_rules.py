"""第 8 步 8.0a：common 里共用的装饰类清单。纯逻辑，不起服务器。"""
import json
from pathlib import Path

import pytest

from analysis.decor_rules import PATH, Rules, load

CASES = json.loads((Path(__file__).parent / 'data/decor-cases.json').read_text())['cases']


def test_file_sorted_and_well_formed():
    data = json.loads(PATH.read_text())
    assert data['schema'] == 1
    for key in ('decor', 'terrain_props'):
        assert data[key] == sorted(set(data[key]))
    for key in ('terrain_pairs', 'excused_pairs'):
        assert all(p == sorted(p) and len(set(p)) == 2 for p in data[key])
        assert data[key] == sorted(data[key])
    assert all(n.startswith('minecraft:') for n in data['decor'])


@pytest.mark.parametrize('case', CASES, ids=lambda c: f"{c['a']}|{c['b']}")
def test_same_verdict_as_fish(case):
    # 样例是 fish 的 decor.classify 在第 1 步全部差异组合上的结论。
    assert load().classify_names(case['a'], case['b']) == case['expected']


def test_vanilla_never_strict():
    # 第 1 步原版互比出现过的组合，没有一个是严格差异（严格层原版为 0）。
    strict = [c for c in CASES if c['expected'] == 'strict']
    added = {frozenset(('minecraft:air', 'minecraft:stone')), frozenset(('minecraft:glass', 'minecraft:stone'))}
    assert {frozenset((c['a'], c['b'])) for c in strict} <= added


def test_ore_stuffing_is_decor_not_strict():
    # 计划第 1 节：塞矿落在装饰类，严格层看不出来，只能靠统计层。
    assert load().classify_names('minecraft:stone', 'minecraft:diamond_ore') == 'decor'
    assert load().classify_names('minecraft:air', 'minecraft:stone') == 'strict'


def test_bad_schema_rejected(tmp_path):
    data = json.loads(PATH.read_text())
    with pytest.raises(ValueError):
        Rules({**data, 'schema': 2})
    with pytest.raises(ValueError):
        Rules({**data, 'terrain_pairs': [['minecraft:dirt', 'minecraft:dirt']]})


# ---- 和 fish 的 analysis/mca/decor.py 同步（合进 main 以后，以它为准） ----

def test_json_is_exported_from_fish_decor_py():
    # decor.json 由 analysis/export_decor.py 从 decor.py 导出；decor.py 改了没重新导出，这里会失败。
    from analysis import export_decor
    assert json.loads(PATH.read_text()) == export_decor.build()


def test_json_carries_terrain_test_parameters():
    # 地形互换的判法参数也放进 JSON，Java 和 Python 读同一份：每处 ≤ 66 方块，处数单侧二项检验 p < 0.01。
    from analysis.mca import decor as fish
    rules = load()
    assert rules.terrain_cluster_max == fish.TERRAIN_CLUSTER_MAX == 66
    assert rules.terrain_alpha == fish.TERRAIN_ALPHA == 0.01


def test_cases_agree_with_fish_classify():
    from collections import namedtuple

    from analysis.mca import decor as fish
    state = namedtuple('State', 'name')
    for c in CASES:
        assert fish.classify(state(c['a']), state(c['b'])) == c['expected'], c
    pairs = {frozenset((c['a'], c['b'])): c['expected'] for c in CASES}
    assert pairs[frozenset(('minecraft:air', 'minecraft:water'))] == 'terrain'  # 湖（fish d81a7ad）
    assert pairs[frozenset(('minecraft:dirt', 'minecraft:grass_block'))] == 'decor'  # 树干正下方（fish 66fa803）
    assert pairs[frozenset(('minecraft:dirt', 'minecraft:snow_block'))] == 'decor'
