"""第 10 步 10.3：汇总和假设检验表。合成数据，不起服务器。"""
import csv
import math

import pytest

from analysis.matrix_summary import (bootstrap_ratio_ci, clopper_pearson, collect, hypotheses, summarize,
                                     t_interval, write_tables)
from analysis.run_matrix import run, validate

# ---- 区间 ----


def test_t_interval_matches_hand_calculation():
    lo, hi = t_interval([1, 2, 3, 4, 5])
    # 均值 3，标准差 √2.5，n=5，t(0.975, 4) = 2.776
    half = 2.776 * math.sqrt(2.5) / math.sqrt(5)
    assert lo == pytest.approx(3 - half, abs=1e-3) and hi == pytest.approx(3 + half, abs=1e-3)
    assert t_interval([7]) == (None, None)


def test_bootstrap_ratio_constant_and_censored():
    assert bootstrap_ratio_ci([10] * 5, [4] * 5) == (2.5, 2.5, 2.5)
    est, lo, hi = bootstrap_ratio_ci([10, 11, 9, 10, 10], [5, 5, 4, 6, 5], seed=1)
    assert est == pytest.approx(2.0) and lo < 2.0 < hi
    assert bootstrap_ratio_ci([10] * 5, [4, 4, math.inf, 4, 4]) == (None, None, None)  # donor 也没跟上，不硬算


def test_clopper_pearson_known_values():
    lo, hi = clopper_pearson(5, 5)
    assert lo == pytest.approx(0.025 ** (1 / 5), abs=1e-4) and hi == 1
    lo, hi = clopper_pearson(0, 5)
    assert lo == 0 and hi == pytest.approx(1 - 0.025 ** (1 / 5), abs=1e-4)
    lo, hi = clopper_pearson(500, 500)
    assert lo > 0.99


# ---- 从归档收集 ----


def metrics(cell, out):
    p = cell['params']
    if cell['experiment'] == 'flight':
        base = 10.0 if p['donors'] == 0 else 4.0
        return dict(latency_p95_ms=base * 1000 + cell['repeat'], void_mean=0.3 if p['donors'] == 0 else 0.05,
                    mspt_p95=40.0, mspt_max=48.0)
    return dict(detected=p['cheat'] != 'none', chunks_before_blacklist=12 if p['cheat'] != 'none' else None,
                false_positives=0, y=0.08 + 0.001 * cell['repeat'])


def campaign(tmp_path, repeats=5):
    cfg = validate({'name': 'syn', 'seed': 1, 'repeats': repeats, 'experiments': {
        'flight': [{'donors': [0, 2], 'speed': ['elytra'], 'server_cpus': [2], 'bots': [1], 'features': ['all'], 'duration_s': 60}],
        'verify': [{'cheat': ['none', 'ore'], 'duration_s': 300}]}})
    run(cfg, {'flight': metrics, 'verify': metrics}, tmp_path)
    return tmp_path / 'matrix-syn'


def test_collect_one_row_per_passed_run(tmp_path):
    rows = collect(campaign(tmp_path))
    assert len(rows) == 4 * 5
    r = next(x for x in rows if x['experiment'] == 'flight' and x['donors'] == 2)
    assert {'cell_id', 'repeat', 'concurrent', 'speed', 'latency_p95_ms'} <= set(r)


def test_summary_per_config(tmp_path):
    s = summarize(collect(campaign(tmp_path)))
    row = next(x for x in s if x['experiment'] == 'flight' and x['donors'] == 2)
    assert row['n'] == 5 and row['latency_p95_ms.mean'] == pytest.approx(4003)
    assert row['latency_p95_ms.ci_low'] < 4003 < row['latency_p95_ms.ci_high']


def test_tables_written(tmp_path):
    c = campaign(tmp_path)
    write_tables(c)
    for name in ('matrix-runs.csv', 'matrix-summary.csv', 'hypotheses.csv', 'x-by-n.csv'):
        assert (c / name).exists(), name
    with (c / 'hypotheses.csv').open() as f:
        items = {r['item']: r for r in csv.DictReader(f)}
    assert {'X', 'MSPT<50ms', 'detect:ore', 'Y', 'Z'} <= set(items)
    assert items['Z']['verdict'] == '数据不足'


# ---- 判定规则（事先定好，见 docs/step10-plan.md 第 3 节） ----


def flight_rows(base, donor, n=5, mspt=40.0):
    rows = []
    for i in range(n):
        for d, v in ((0, base[i % len(base)]), (2, donor[i % len(donor)])):
            rows.append(dict(experiment='flight', donors=d, speed='elytra', server_cpus=2, bots=1, features='all',
                             repeat=i + 1, latency_p95_ms=v, mspt_p95=mspt, mspt_max=mspt + 5))
    return rows


def verdict(rows, item):
    return next(h for h in hypotheses(rows) if h['item'] == item)['verdict']


def test_x_verdicts():
    assert verdict(flight_rows([10.0], [4.0]), 'X') == '成立'            # 2.5，区间下限 ≥ 2
    assert verdict(flight_rows([10.0], [6.0]), 'X') == '不成立'          # 1.67，区间上限 < 2
    assert verdict(flight_rows([10.0, 10, 10, 10, 10], [3.0, 7, 3, 7, 5]), 'X') == '数据不足'  # 区间跨过 2
    assert verdict(flight_rows([10.0], [4.0], n=3), 'X') == '数据不足'   # n < 5
    assert verdict(flight_rows([10.0], [math.inf]), 'X') == '数据不足'   # donor 也删失


def test_mspt_verdicts():
    assert verdict(flight_rows([10.0], [4.0], mspt=40), 'MSPT<50ms') == '成立'
    assert verdict(flight_rows([10.0], [4.0], mspt=55), 'MSPT<50ms') == '不成立'
    assert verdict([], 'MSPT<50ms') == '数据不足'


def test_detection_and_y_verdicts():
    def v(cheat, k, n):
        return [dict(experiment='verify', cheat=cheat, repeat=i, detected=i < k, y=None) for i in range(n)]
    assert verdict(v('ore', 500, 500), 'detect:ore') == '成立'
    assert verdict(v('ore', 5, 5), 'detect:ore') == '数据不足'           # 5 次证明不了 99%，照实写
    assert verdict(v('ore', 0, 5), 'detect:ore') == '不成立'
    honest = [dict(experiment='verify', cheat='none', repeat=i, detected=False, y=0.08) for i in range(5)]
    assert verdict(honest, 'Y') == '已定'
    assert verdict(honest[:3], 'Y') == '数据不足'
    assert verdict([], 'Z') == '数据不足'


def test_step8_paused_gives_insufficient_data():
    # 用户 11-09：第 8 步暂停。没有 verify 结果时，检出率和 Y 两条照样列出，判"数据不足（第 8 步暂停）"
    rows = flight_rows([10.0], [4.0])
    h = {x['item']: x for x in hypotheses(rows)}
    assert h['detect']['verdict'] == '数据不足（第 8 步暂停）' and h['detect']['value'] is None
    assert h['Y']['verdict'] == '数据不足（第 8 步暂停）'
    assert h['X']['verdict'] == '成立'  # 别的不受影响


def test_metric_name_colliding_with_parameter_rejected(tmp_path):
    # 指标和参数同名（比如 capacity 的搜索上限 max_bots）会被汇总当成参数，所以直接报错。
    cfg = validate({'name': 'clash', 'seed': 1, 'repeats': 1, 'experiments': {
        'capacity': [{'donors': [2], 'server_cpus': [2], 'max_bots': 8}]}})
    run(cfg, {'capacity': lambda cell, out: dict(max_bots=3)}, tmp_path)
    with pytest.raises(ValueError, match='max_bots'):
        collect(tmp_path / 'matrix-clash')


# ---- 卡住和来源三类（11-09：报告里 X 旁边同时列卡住时长；来源按 fish 的三类算） ----


def stall_rows(n=5):
    rows = flight_rows([10.0], [4.0], n=n)
    for r in rows:
        zero = r['donors'] == 0
        r.update(stall_s=40.0 if zero else 20.0, stall_fraction=1 / 3 if zero else 1 / 6,
                 distance_ratio=0.67 if zero else 0.83,
                 full_generated=1000 if zero else 600, full_resumed=0 if zero else 300, full_loaded=0 if zero else 100)
    return rows


def test_stall_listed_next_to_x():
    h = {x['item']: x for x in hypotheses(stall_rows())}
    s = h['stall']
    # 描述性的一条，不是事先定的门槛：给出 2 donor 的卡住占比，注里有 0 donor 的对照和实际 / 理想距离
    assert s['verdict'] == '记录' and s['value'] == pytest.approx(1 / 6)
    assert '0 donor' in s['note'] and '33' in s['note'] and '17' in s['note'] and '距离' in s['note']
    items = [x['item'] for x in hypotheses(stall_rows())]
    assert items.index('stall') == items.index('X') + 1


def test_donor_share_counts_resumed_not_generated():
    # donor 分担的比例 = 导入后服务器接着做完的 ÷ 三类之和；resumed 不算成服务器自己生成的
    h = {x['item']: x for x in hypotheses(stall_rows())}
    d = h['donor_share']
    assert d['verdict'] == '记录' and d['value'] == pytest.approx(300 / 1000)
    assert '服务器自己生成' in d['note'] and '读盘' in d['note']


def test_stall_and_share_without_data():
    h = {x['item']: x for x in hypotheses(flight_rows([10.0], [4.0]))}
    assert h['stall']['verdict'] == '数据不足' and h['stall']['value'] is None
    assert h['donor_share']['verdict'] == '数据不足'


def test_x_by_n_carries_stall():
    from analysis.matrix_summary import x_by_n
    row = x_by_n(stall_rows())[0]
    assert row['donors'] == 2 and row['x'] == pytest.approx(2.5)
    assert row['stall_fraction_base'] == pytest.approx(1 / 3) and row['stall_fraction_donor'] == pytest.approx(1 / 6)
    assert row['distance_ratio_base'] == pytest.approx(0.67) and row['distance_ratio_donor'] == pytest.approx(0.83)
