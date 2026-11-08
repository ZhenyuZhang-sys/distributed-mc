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
