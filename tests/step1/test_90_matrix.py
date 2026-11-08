"""第 1 步验收：复核已发布的粗测结果（用户 11-08 决定第 1 步只粗测）。

只读归档，不起服务器，不 skip。完整矩阵以后在部署机器上重新 pilot 后用
`scripts/bench.sh run` 跑满（轮数、预热量、阶段分析槽位在 bench/protocol.json）。"""
import csv
import json

from analysis.summarize_layer1 import summarize_campaign
from bench.models import AREA_IDS, ROOT, digest

OUT = ROOT / 'analysis/results'
TIERS = ('same', 'few', 'many')


def rows(name):
    with (OUT / name).open() as f:
        return list(csv.DictReader(f))


def published():
    state = json.loads((OUT / 'coarse.json').read_text())
    assert state['coarse'] is True
    for name, value in state['artifacts'].items():
        assert digest(OUT / name) == value, f'{name} 与 coarse.json 记录的摘要不符'
    return state


def test_coarse_throughput():
    published()
    summary = rows('layer1-summary.csv')
    assert {(r['area_id'], int(r['cpu_count'])) for r in summary} == {(a, c) for a in AREA_IDS for c in (1, 2, 4)}
    assert all(int(r['n']) >= 2 and float(r['cv']) < .1 for r in summary)


def test_coarse_uncertainty_has_three_tiers():
    published()
    diff = rows('determinism.csv')
    for area in AREA_IDS:
        groups = {r['comparison_group'] for r in diff if r['area_id'] == area}
        assert {'repeat_1cpu', 'repeat_4cpu', 'cross_1_4cpu'} <= groups
    for r in diff:
        assert sum(int(r[t]) for t in TIERS) == 256
    summary = json.loads((OUT / 'difference-summary.json').read_text())
    assert {'block_types', 'y', 'boundary'} <= set(summary)


def test_coarse_report_and_stages():
    published()
    report = (OUT / 'baseline.md').read_text()
    assert '粗测' in report and 'N100 上要重测' in report and '整块一样' in report
    assert rows('stages.csv')


def test_archive_recomputes_same_numbers():
    # 本机有归档就离线重算一遍，和发布的数一致；没有归档（别的机器）只核对发布文件。
    state = published()
    campaign = ROOT / 'analysis/results/runs' / state['campaign_id']
    if not campaign.exists():
        return
    summaries, differences = summarize_campaign(campaign)
    by = {(r['area_id'], int(r['cpu_count'])): r for r in rows('layer1-summary.csv')}
    for s in summaries:
        assert abs(float(by[s['area_id'], s['cpu_count']]['mean']) - s['mean']) < 1e-9
    assert len(differences) == len(rows('determinism.csv'))
