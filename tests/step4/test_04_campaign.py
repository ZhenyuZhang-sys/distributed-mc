"""第 4 步 4.7、4.8：冒烟测量的编排（bench/layer2_campaign.py）。纯逻辑，飞行用假函数代替，不起服务器。"""
import json
from pathlib import Path

import pytest

from bench import layer2_campaign as lc

PROTOCOL = Path(__file__).resolve().parents[2] / 'bench/layer2-protocol.json'


def proto(**over):
    p = json.loads(PROTOCOL.read_text())
    p.update(over)
    return p


# ---- 协议 ----

def test_shipped_protocol_is_valid_and_downgraded():
    p = lc.load_protocol(PROTOCOL)
    assert p['speeds'] == {'walk': 4.3, 'horse': 14, 'elytra': 30} and p['view_distance'] == 10
    assert p['rerun_on_interference'] is False  # 降级：有并发只标记
    assert p['repeats']['elytra'] == 2


@pytest.mark.parametrize('bad', [dict(speeds={'walk': 0}), dict(cpus=3), dict(repeats={'walk': 0, 'horse': 1, 'elytra': 1}),
                                 dict(mode='fast'), dict(max_attempts=0), dict(extra=[{'speed': 'boat', 'cpus': 4, 'repeats': 1}]),
                                 dict(selfcheck=[{'name': 'x', 'speed': 'elytra', 'cpus': 2, 'pregenerate': True}])])
def test_bad_protocol_rejected(tmp_path, bad):
    path = tmp_path / 'p.json'
    path.write_text(json.dumps(proto(**bad)))
    with pytest.raises(ValueError):
        lc.load_protocol(path)


# ---- 槽位 ----

def test_slots_selfchecks_first_then_shuffled_matrix():
    s = lc.slots(lc.load_protocol(PROTOCOL))
    assert [x['kind'] for x in s[:2]] == ['selfcheck', 'selfcheck'] and {x['name'] for x in s[:2]} == {'pregen', 'one_core'}
    smoke = s[2:]
    counts = {}
    for x in smoke:
        counts[x['speed_name'], x['cpus']] = counts.get((x['speed_name'], x['cpus']), 0) + 1
    assert counts == {('walk', 2): 1, ('horse', 2): 1, ('elytra', 2): 2, ('elytra', 4): 1}
    assert len({x['slot'] for x in s}) == len(s)  # 槽位名唯一（断点续跑靠它）
    assert lc.slots(lc.load_protocol(PROTOCOL)) == s  # 同一种子，顺序固定
    assert all(x['speed'] == {'walk': 4.3, 'horse': 14, 'elytra': 30}[x['speed_name']] for x in s)


def test_full_matrix_is_superset_of_smoke():
    # 部署机器上改成 5 次：前面的槽位名不变，已跑的能复用
    small = {x['slot'] for x in lc.slots(lc.load_protocol(PROTOCOL))}
    full = proto(repeats={'walk': 5, 'horse': 5, 'elytra': 5}, extra=[{'speed': 'elytra', 'cpus': 4, 'repeats': 5}])
    assert small <= {x['slot'] for x in lc.slots(lc.validate(full))}


# ---- 并发干扰（计划 5.1a） ----

def ev(cid, action, name='x', t=1):
    return dict(Type='container', Action=action, Actor=dict(ID=cid, Attributes=dict(name=name)), timeNano=t)


def test_interference_only_counts_other_containers_lifecycle():
    events = [ev('ours', 'start'), ev('ours', 'die'), ev('other', 'exec_start: sh'), ev('other', 'start', 'mc-test-fish'),
              dict(Type='network', Action='connect', Actor=dict(ID='n', Attributes={}), timeNano=2),
              ev('other2', 'destroy', 'build')]
    found = lc.interference(events, own={'ours'}, actions=('create', 'start', 'stop', 'die', 'destroy'))
    assert [(f['name'], f['action']) for f in found] == [('mc-test-fish', 'start'), ('build', 'destroy')]


# ---- 自检判定（计划 5.3） ----

CRIT = dict(pregen_void_mean_lt=.01, pregen_latency_p95_ms_lt=1000, one_core_void_mean_above_pregen=.10)


def r(void, p95, concurrent=()):
    return dict(void_mean=void, latency_p95_ms=p95, concurrent=list(concurrent))


def test_selfcheck_pass_and_fail():
    v = lc.selfcheck_verdict(dict(pregen=r(.002, 300), one_core=r(.25, 9000)), CRIT)
    assert v['pregen']['status'] == 'passed' and v['one_core']['status'] == 'passed'
    v = lc.selfcheck_verdict(dict(pregen=r(.02, 300), one_core=r(.05, 'inf')), CRIT)
    assert v['pregen']['status'] == 'failed' and v['one_core']['status'] == 'failed'
    assert 'inf' == v['one_core']['latency_p95_ms']  # 删失照实带出


def test_selfcheck_with_concurrency_is_reference_only():
    # 调度 11-08：自检那轮有并发，结论只算"参考"，不当通过
    v = lc.selfcheck_verdict(dict(pregen=r(.002, 300, ['mc-test-fish start']), one_core=r(.25, 9000)), CRIT)
    assert v['pregen']['status'] == 'reference'
    assert v['one_core']['status'] == 'reference'  # 第 2 条要和第 1 条比，第 1 条只是参考，它也只能是参考


def test_selfcheck_infinite_latency_fails_pregen():
    assert lc.selfcheck_verdict(dict(pregen=r(.0, 'inf'), one_core=r(.3, 'inf')), CRIT)['pregen']['status'] == 'failed'


# ---- 每个配置的汇总（降级：1 次不报波动，2 次报两次的差） ----

def test_config_summary_one_and_two_runs():
    rows = [dict(speed_name='walk', cpus=2, void_mean=.01, latency_p95_ms=100.0),
            dict(speed_name='elytra', cpus=2, void_mean=.2, latency_p95_ms=2000.0),
            dict(speed_name='elytra', cpus=2, void_mean=.3, latency_p95_ms='inf')]
    out = {(s['speed_name'], s['cpus']): s for s in lc.config_summary(rows, ('void_mean', 'latency_p95_ms'))}
    walk, ely = out['walk', 2], out['elytra', 2]
    assert walk['n'] == 1 and walk['void_mean_mean'] == .01 and walk['void_mean_diff'] is None
    assert ely['n'] == 2 and ely['void_mean_mean'] == pytest.approx(.25) and ely['void_mean_diff'] == pytest.approx(.1)
    assert ely['latency_p95_ms_mean'] == 'inf' and ely['latency_p95_ms_diff'] is None


# ---- 编排：尝试、重跑、断点续跑 ----

def fake(results):
    """results[槽位] 依次返回的结果；'fail' 表示抛异常。"""
    calls = []

    def fly_slot(slot, directory):
        calls.append(slot['slot'])
        out = results[slot['slot']].pop(0)
        if out == 'fail':
            raise RuntimeError('假失败')
        return dict(void_mean=.1, latency_p95_ms=500.0, **out)
    return fly_slot, calls


def test_campaign_records_attempts_and_marks_concurrency(tmp_path):
    p = lc.load_protocol(PROTOCOL)
    names = [s['slot'] for s in lc.slots(p)]
    results = {n: [dict(concurrent=[])] for n in names}
    results[names[3]] = ['fail', dict(concurrent=['mc-test-fish start'])]
    fly_slot, calls = fake(results)
    out = lc.run_campaign(p, tmp_path / 'c', fly_slot)
    assert out['passed'] == len(names) and out['failed'] == 0 and out['concurrent'] == [names[3]]
    attempts = sorted(p.name for p in (tmp_path / 'c' / names[3]).iterdir() if p.name.startswith('attempt-'))
    assert attempts == ['attempt-001', 'attempt-002']
    m1 = json.loads((tmp_path / 'c' / names[3] / 'attempt-001' / 'manifest.json').read_text())
    assert m1['status'] == 'failed' and '假失败' in m1['error']
    # 断点续跑：已通过的不再飞
    again, calls2 = fake({n: [] for n in names})
    out2 = lc.run_campaign(p, tmp_path / 'c', again)
    assert calls2 == [] and out2['passed'] == len(names)


def test_campaign_reruns_on_interference_when_configured(tmp_path):
    p = lc.validate(proto(rerun_on_interference=True))
    names = [s['slot'] for s in lc.slots(p)]
    results = {n: [dict(concurrent=[])] for n in names}
    results[names[0]] = [dict(concurrent=['a start']), dict(concurrent=['b start']), dict(concurrent=[])]
    fly_slot, _ = fake(results)
    out = lc.run_campaign(p, tmp_path / 'c', fly_slot)
    assert out['passed'] == len(names)
    m = json.loads((tmp_path / 'c' / names[0] / 'attempt-001' / 'manifest.json').read_text())
    assert m['status'] == 'invalid' and m['concurrent'] == ['a start']


def test_campaign_reruns_invalid_results(tmp_path):
    # 主线程同步加载不为 0 的一轮判无效、重跑（调度 11-09），不管 rerun_on_interference
    p = lc.load_protocol(PROTOCOL)
    names = [s['slot'] for s in lc.slots(p)]
    results = {n: [dict(concurrent=[])] for n in names}
    results[names[2]] = [dict(concurrent=[], invalid='主线程同步加载 3 次'), dict(concurrent=[])]
    fly_slot, _ = fake(results)
    out = lc.run_campaign(p, tmp_path / 'c', fly_slot)
    assert out['passed'] == len(names)
    m = json.loads((tmp_path / 'c' / names[2] / 'attempt-001' / 'manifest.json').read_text())
    assert m['status'] == 'invalid' and '同步加载' in m['invalid']


def test_campaign_gives_up_after_max_attempts(tmp_path):
    p = lc.validate(proto(max_attempts=2))
    names = [s['slot'] for s in lc.slots(p)]
    results = {n: [dict(concurrent=[])] for n in names}
    results[names[1]] = ['fail', 'fail']
    fly_slot, _ = fake(results)
    out = lc.run_campaign(p, tmp_path / 'c', fly_slot)
    assert out['failed'] == 1 and out['passed'] == len(names) - 1


def test_campaign_refuses_changed_protocol(tmp_path):
    p = lc.load_protocol(PROTOCOL)
    fly_slot, _ = fake({s['slot']: [dict(concurrent=[])] for s in lc.slots(p)})
    lc.run_campaign(p, tmp_path / 'c', fly_slot)
    with pytest.raises(ValueError, match='协议'):
        lc.run_campaign(lc.validate(proto(seconds=60)), tmp_path / 'c', fly_slot)
