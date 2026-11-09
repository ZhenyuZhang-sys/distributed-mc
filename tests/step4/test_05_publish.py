"""第 4 步 4.8：冒烟测量的结果发布（bench/layer2_campaign.publish）。用合成的 campaign，不起服务器。"""
import csv
import json
from pathlib import Path

import pytest

from bench import layer2_campaign as lc
from bench.models import digest

PROTOCOL = Path(__file__).resolve().parents[2] / 'bench/layer2-protocol.json'


def fake_flight(d, void, p95):
    d.mkdir(parents=True)
    with (d / 'layer2.csv').open('w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['t_s', 'x', 'void_rate'])
        w.writerows([[1, 0.0, void], [2, 1.0, void]])
    with (d / 'layer2-chunks.csv').open('w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['cx', 'cz', 'enter_ms', 'latency_ms', 'censored'])
        w.writerow([1, 2, 100, p95, False])
    return d


def campaign(tmp_path, concurrent_slot=None, pregen_void=.002, **over):
    p = lc.validate({**lc.load_protocol(PROTOCOL), **over})

    def fly_slot(slot, directory):
        void = {'pregen': pregen_void, 'one_core': .3}.get(slot['name'], .05)
        out = fake_flight(directory / 'flight', void, 500.0)
        return dict(void_mean=void, void_p95=void * 2, latency_p50_ms=0.0, latency_p95_ms=500.0, latency_p99_ms=900.0,
                    mspt_mean=10.0, mspt_max=40.0, cpu_cores_mean=1.5, censored_fraction=0.0, available_processors=slot['cpus'],
                    scan_ms_total=5.0, scan_every_ticks=1, ticks_total=2400, initial_load_s=20.0, flight_dir=str(out),
                    stall_s=3.0, stall_fraction=.025, distance_ratio=.9, distance=3240.0, sync_loads=0,
                    concurrent=['mc-test-fish start'] if slot['slot'] == concurrent_slot else [])
    root = tmp_path / 'runs' / 'layer2-x'
    return p, lc.run_campaign(p, root, fly_slot), root


def rows(path):
    with path.open() as f:
        return list(csv.DictReader(f))


def test_publish_writes_tables_report_and_digests(tmp_path):
    p, result, root = campaign(tmp_path)
    out = tmp_path / 'results'
    state = lc.publish(root, p, result, out)
    runs = rows(out / 'layer2-runs.csv')
    assert len(runs) == 7 and {r['kind'] for r in runs} == {'selfcheck', 'smoke'}
    for col in ('latency_p50_ms', 'latency_p95_ms', 'latency_p99_ms', 'void_mean', 'available_processors', 'concurrent', 'scan_every_ticks',
                'stall_s', 'stall_fraction', 'distance_ratio', 'sync_loads'):
        assert col in runs[0]
    summary = {(r['speed_name'], r['cpus']): r for r in rows(out / 'layer2-summary.csv')}
    assert set(summary) == {('walk', '2'), ('horse', '2'), ('elytra', '2'), ('elytra', '4')}  # 只汇总冒烟矩阵，不含自检
    assert summary['elytra', '2']['n'] == '2' and summary['elytra', '2']['void_mean_diff'] != ''
    assert summary['walk', '2']['void_mean_diff'] == ''  # 1 次不报波动
    seconds = rows(out / 'layer2.csv')
    assert len(seconds) == 14 and 'slot' in seconds[0]  # 7 轮 × 2 秒，带槽位名
    assert len(rows(out / 'layer2-chunks.csv')) == 7
    check = json.loads((out / 'selfcheck.json').read_text())
    assert check['pregen']['status'] == 'passed' and check['one_core']['status'] == 'passed'
    report = (out / 'baseline-layer2.md').read_text()
    for text in ('冒烟', 'N100 上要重测', '生成线程', 'availableProcessors', '自检', 'scan_every_ticks', '个百分点', '0.0%～0.0%',
                 '卡住', '实际距离', '同步加载'):
        assert text in report, text
    assert state['smoke'] is True and state['campaign'] == 'layer2-x'
    for name, value in state['artifacts'].items():
        assert digest(out / name) == value
    assert json.loads((out / 'layer2-smoke.json').read_text()) == state


def test_publish_lists_concurrency_and_reference_selfchecks(tmp_path):
    # 部署机器上跑满时的规则（selfcheck_concurrency_counts=false）：自检有并发只算参考
    p, result, root = campaign(tmp_path, concurrent_slot='selfcheck-pregen', selfcheck_concurrency_counts=False)
    out = tmp_path / 'results'
    lc.publish(root, p, result, out)
    check = json.loads((out / 'selfcheck.json').read_text())
    assert check['pregen']['status'] == 'reference'
    report = (out / 'baseline-layer2.md').read_text()
    assert '有并发' in report and 'mc-test-fish start' in report and '参考' in report


def test_publish_refuses_failed_slots_and_failed_selfcheck(tmp_path):
    p, result, root = campaign(tmp_path)
    with pytest.raises(ValueError, match='失败'):
        lc.publish(root, p, {**result, 'failed': 1}, tmp_path / 'r1')
    p, result, root = campaign(tmp_path / 'b', pregen_void=.05)
    with pytest.raises(ValueError, match='自检'):
        lc.publish(root, p, result, tmp_path / 'r2')


def test_readme_block_rendered_from_published_results(tmp_path):
    p, result, root = campaign(tmp_path)
    out = tmp_path / 'results'
    lc.publish(root, p, result, out)
    block = lc.readme_block(out)
    assert block.startswith(lc.README_BEGIN) and block.rstrip().endswith(lc.README_END)
    assert '冒烟' in block and 'N100 上要重测' in block and 'baseline-layer2.md' in block
    for speed in ('walk', 'horse', 'elytra'):
        assert speed in block or {'walk': '走路', 'horse': '骑马', 'elytra': '鞘翅'}[speed] in block
    assert '自检' in block and '卡住' in block
    # README 里两个标记之间的内容就是它；替换函数只动标记之间
    readme = tmp_path / 'README.md'
    readme.write_text('前面\n' + lc.README_BEGIN + '\n旧的\n' + lc.README_END + '\n后面\n')
    lc.update_readme(readme, out)
    text = readme.read_text()
    assert text.startswith('前面\n') and text.endswith('后面\n') and '旧的' not in text and lc.readme_block(out) in text


def test_publish_counts_concurrent_selfcheck_under_user_decision(tmp_path):
    # 用户 11-09：粗测、允许并行。自检有并发照常判，报告里照样列出并发，并写明用户决定
    p, result, root = campaign(tmp_path, concurrent_slot='selfcheck-pregen')
    out = tmp_path / 'results'
    lc.publish(root, p, result, out)
    check = json.loads((out / 'selfcheck.json').read_text())
    assert check['pregen']['status'] == 'passed' and check['pregen']['concurrent'] == ['mc-test-fish start']
    report = (out / 'baseline-layer2.md').read_text()
    assert '允许并行' in report and 'mc-test-fish start' in report
