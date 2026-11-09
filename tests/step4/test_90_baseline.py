"""第 4 步验收（4.7、4.8，降级为冒烟测量，调度 11-08）。

- slow：按 bench/layer2-protocol.json 跑两条指标自检和冒烟矩阵，全部通过后发布到 analysis/results/。
  自检有并发只算参考；自检没过时，先重跑那一条再下结论（调度 11-08），所以这里没过就失败，不自动发布。
- 非 slow：复核已发布的冒烟结果（摘要、每轮 p50/p95/p99、有 2 次的配置给出波动、自检、报告文字）。不起服务器，不 skip。
"""
import csv
import json
import os
from datetime import datetime, timezone

import pytest

from bench import layer2_campaign as lc
from bench.models import ROOT, digest

OUT = ROOT / 'analysis/results'
PROTOCOL = ROOT / 'bench/layer2-protocol.json'


def rows(name):
    with (OUT / name).open() as f:
        return list(csv.DictReader(f))


@pytest.mark.slow
def test_smoke_measurement(server_manager):
    # 先停掉第 4 步前面测试用的服务器；每个槽位自己开新世界（enter_benchmark 只给第 1 步用，阶段 4 回不去）
    server_manager.finish()
    p = lc.load_protocol(PROTOCOL)
    campaign = ROOT / 'analysis/results/runs' / os.environ.get(
        'MCD_LAYER2_CAMPAIGN', 'layer2-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S'))
    result = lc.run_campaign(p, campaign, lc.real_fly_slot(p))
    assert result['failed'] == 0, result
    lc.publish(campaign, p, result, OUT)
    lc.update_readme(ROOT / 'README.md', OUT)  # 4.9：README 第 2 层的数字从发布结果生成


def published():
    state = json.loads((OUT / 'layer2-smoke.json').read_text())
    assert state['smoke'] is True
    for name, value in state['artifacts'].items():
        assert digest(OUT / name) == value, f'{name} 和 layer2-smoke.json 记录的摘要不符'
    return state


def test_published_runs_have_percentiles():
    published()
    p = lc.load_protocol(PROTOCOL)
    runs = rows('layer2-runs.csv')
    assert sorted(r['slot'] for r in runs) == sorted(s['slot'] for s in lc.slots(p))
    for r in runs:
        for k in ('latency_p50_ms', 'latency_p95_ms', 'latency_p99_ms', 'void_mean', 'mspt_mean', 'cpu_cores_mean'):
            assert r[k] != '', (r['slot'], k)


def test_published_spread_for_repeated_configs():
    published()
    summary = rows('layer2-summary.csv')
    for s in summary:
        if int(s['n']) == 2:
            assert s['void_mean_diff'] != ''
    assert any(int(s['n']) == 2 for s in summary)


def test_published_selfchecks_and_report():
    published()
    check = json.loads((OUT / 'selfcheck.json').read_text())
    assert {check['pregen']['status'], check['one_core']['status']} <= {'passed', 'reference'}
    report = (OUT / 'baseline-layer2.md').read_text()
    assert '冒烟' in report and 'N100 上要重测' in report and '生成线程' in report


def test_readme_layer2_numbers_match_published():
    # 4.9：README 第 2 层的数字由已发布结果生成，手改了或者结果重发了没更新都会失败
    published()
    text = (ROOT / 'README.md').read_text()
    assert lc.readme_block(OUT) in text
