"""第 10 步 10.1：矩阵配置和单元。纯逻辑，不起服务器。"""
import json

import pytest

from analysis.run_matrix import cell_id, expand, load_config, order, plan, validate
from bench.models import ROOT

SMOKE = ROOT / 'analysis/matrix/smoke.json'
FULL = ROOT / 'analysis/matrix/full.json'


def base():
    return {'name': 't', 'seed': 1, 'repeats': 1, 'experiments': {
        'flight': [{'donors': [0, 2], 'speed': ['elytra'], 'server_cpus': [2], 'latency_ms': [0],
                    'features': ['v0'], 'bots': [1], 'duration_s': 60}]}}


def test_cell_id_ignores_key_order():
    a = dict(experiment='flight', params=dict(donors=2, speed='elytra'), repeat=1)
    b = dict(repeat=1, params=dict(speed='elytra', donors=2), experiment='flight')
    assert cell_id(a) == cell_id(b)
    assert cell_id(a) != cell_id(dict(a, repeat=2))
    assert len(cell_id(a)) == 16


def test_zero_donors_not_split_by_features():
    c = base()
    c['experiments']['flight'][0]['features'] = ['v0', 'all']
    cells = expand(validate(c))
    zero = [x for x in cells if x['params']['donors'] == 0]
    assert len(zero) == 1 and zero[0]['params']['features'] == 'v0'
    assert len([x for x in cells if x['params']['donors'] == 2]) == 2


def test_grids_deduplicated_and_repeats():
    c = base()
    c['repeats'] = 3
    c['experiments']['flight'].append(dict(c['experiments']['flight'][0]))  # 同一个网格写两遍
    cells = expand(validate(c))
    assert len(cells) == 2 * 3
    assert len({cell_id(x) for x in cells}) == len(cells)


def test_smoke_matrix():
    cells = expand(load_config(SMOKE))
    by = {}
    for x in cells:
        by.setdefault(x['experiment'], []).append(x)
    assert len(by['flight']) == 2 and {x['params']['donors'] for x in by['flight']} == {0, 2}
    assert {x['params']['cheat'] for x in by['verify']} == {'none', 'ore'}
    assert 'capacity' not in by
    assert plan(load_config(SMOKE))['hours'] < 0.75


def test_full_matrix_counts_and_hours():
    cfg = load_config(FULL)
    cells = expand(cfg)
    count = lambda e, **kw: sum(1 for x in cells if x['experiment'] == e and all(x['params'].get(k) == v for k, v in kw.items()))
    assert count('flight', latency_ms=0) == 210       # 主网格 42 个配置 × 5
    assert count('flight', latency_ms=50) + count('flight', latency_ms=150) == 10
    assert {x['params']['donors'] for x in cells if x['experiment'] == 'flight'} >= {0, 1, 2, 4}
    assert count('capacity') == 4
    assert count('verify') == 75 + 5                  # 五种作弊 × 三档抽查率 × 5，加诚实 30 分钟 × 5
    hours = plan(cfg)['hours']
    assert 35 <= hours <= 45, hours


def test_order_is_seeded_permutation():
    cells = expand(load_config(FULL))
    a, b = order(cells, 424242), order(cells, 424242)
    assert [cell_id(x) for x in a] == [cell_id(x) for x in b]
    assert sorted(map(cell_id, a)) == sorted(map(cell_id, cells))
    assert [cell_id(x) for x in a] != [cell_id(x) for x in order(cells, 1)]
    # 不是"先跑完所有 0 donor"：前 20 个里 donor 数不止一种
    assert len({x['params'].get('donors') for x in a[:20] if x['experiment'] == 'flight'}) > 1


@pytest.mark.parametrize('bad', [
    lambda c: c.update(repeats=0),
    lambda c: c['experiments'].update(lava=[{}]),
    lambda c: c['experiments']['flight'][0].update(speed=['teleport']),
    lambda c: c['experiments']['flight'][0].update(donors=[-1]),
    lambda c: c['experiments']['flight'][0].update(donors=[]),
    lambda c: c['experiments']['flight'][0].update(colour=['red']),
    lambda c: c['experiments']['flight'][0].pop('duration_s'),
    lambda c: c.pop('seed'),
])
def test_invalid_config_rejected(bad):
    c = base()
    bad(c)
    with pytest.raises(ValueError):
        validate(c)


def test_plan_cli_prints_without_running(capsys):
    from analysis.run_matrix import main
    main(['--config', str(SMOKE), '--plan'])
    out = json.loads(capsys.readouterr().out)
    assert out['cells'] == len(expand(load_config(SMOKE))) and 'hours' in out
