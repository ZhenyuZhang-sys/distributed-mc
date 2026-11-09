"""第 10 步按"能演示"收尾（用户 11-09）：flight 真驱动（10.6）、演示用的小矩阵、几张关键的图。不起服务器，全用假的。"""
import json
import math

import pytest

from analysis import demo_figures, run_matrix
from analysis.flight_driver import make_flight_driver
from analysis.matrix_summary import collect, hypotheses, write_tables
from bench.demo.control import Controller, Flyer
from bench.demo.poll import Poller

from step10.test_06_demo import Clock, FakeFly, FakeRunner   # 同一套假服务器（--import-mode=importlib，按 tests 下的路径导入）



def rig(tmp_path, **fly_kw):
    runner = FakeRunner()
    poller = Poller(runner, runs_dir=tmp_path / 'runs', clock=Clock())
    fly = FakeFly(runner, **fly_kw)
    flyer = Flyer(runner, poller, tmp_path / 'artifacts', fly=fly)
    ctl = Controller(runner, poller, flyer, wait_timeout=1, wait_interval=0)
    runner.netem = True
    return runner, ctl, flyer, fly


def cell(donors, features='v0', latency_ms=0, repeat=1, duration_s=120, lookahead=None):
    p = dict(donors=donors, speed='elytra', server_cpus=2, bots=1, features=features, latency_ms=latency_ms, duration_s=duration_s)
    if lookahead:
        p['lookahead'] = lookahead
    return dict(experiment='flight', repeat=repeat, params=p)


def test_driver_sets_up_each_config(tmp_path):
    runner, ctl, flyer, fly = rig(tmp_path)
    drive = make_flight_driver(runner, ctl, flyer, events=lambda a, b: [])
    out = drive(cell(0), tmp_path / 'c0')
    assert fly.calls[-1]['workers'] == 0 and not fly.calls[-1]['demand']
    out2 = drive(cell(2, 'deadline', 35), tmp_path / 'c1')
    last = fly.calls[-1]
    assert last['workers'] == 2 and last['demand'] and last['features'] == ['deadline'] and last['seconds'] == 120
    worlds = [c for c in runner.calls if c[:2] == ('script', 'new_world.sh')]
    assert len(worlds) == 2 and all(('MC_CPUS', '2') in c for c in worlds)        # 每个单元一个全新世界，2 核
    assert ('rcon', 'donor reset') in runner.calls
    netem = [c for c in runner.calls if c[:2] == ('script', 'netem.sh')]
    assert netem and netem[-1][2] == 'on'                                          # 有网络的单元开 netem
    # 指标不能和参数同名（汇总会当成参数）；有效、donor 事件、前方比例、三类来源都在
    assert not set(out2) & run_matrix.SCHEMA['flight']['dims']
    for k in ('stall_fraction', 'stall_s', 'distance_ratio', 'latency_p95_ms', 'mspt_p95', 'full_generated', 'full_resumed',
              'full_loaded', 'donor_events', 'netem'):
        assert k in out2, k
    assert out2['netem'] is True and out['netem'] is False
    # 封存的 JSON 不许有无穷大：删失写成 'inf'（和第 4 步一样），汇总读回来变成无穷大
    assert out2['latency_p95_ms'] == 'inf'


def test_driver_turns_netem_off_for_no_network_cells(tmp_path):
    runner, ctl, flyer, fly = rig(tmp_path)
    drive = make_flight_driver(runner, ctl, flyer, events=lambda a, b: [])
    drive(cell(2, 'deadline', 0), tmp_path / 'c')
    netem = [c for c in runner.calls if c[:2] == ('script', 'netem.sh')]
    assert netem and all(c[2] == 'off' for c in netem)


def test_invalid_run_fails_so_the_matrix_retries(tmp_path):
    runner, ctl, flyer, fly = rig(tmp_path, donor_events=0)
    drive = make_flight_driver(runner, ctl, flyer, events=lambda a, b: [])
    with pytest.raises(RuntimeError, match='donor'):
        drive(cell(2, 'deadline'), tmp_path / 'c')


def configs_of(cells):
    return {(c['params'].get('group'), c['params']['server_cpus'], c['params']['donors'], c['params']['features'],
             c['params']['latency_ms'], c['params'].get('lookahead')) for c in cells}


def test_demo_config_expands_to_all_settings():
    # 调度 11-09：找每次都能明显看出差别的设置。三组，每组自己有一份原版对照：
    # 2 核基本组（原版 / 2 donor / 2 donor + 网络）、2 核多 donor 组（原版 / 3 donor 提前量 8、16、24）、1 核组（原版 / 2 donor）
    cfg = run_matrix.load_config(run_matrix.ROOT / 'analysis/matrix/demo.json')
    cells = run_matrix.expand(cfg)
    assert configs_of(cells) == {
        ('2c', 2, 0, 'v0', 0, None), ('2c', 2, 2, 'deadline', 0, None), ('2c', 2, 2, 'deadline', 35, None),
        ('2c3d', 2, 0, 'v0', 0, None), ('2c3d', 2, 3, 'deadline', 0, 8), ('2c3d', 2, 3, 'deadline', 0, 16), ('2c3d', 2, 3, 'deadline', 0, 24),
        ('1c', 1, 0, 'v0', 0, None), ('1c', 1, 2, 'deadline', 0, None)}
    assert all(c['params']['speed'] == 'elytra' and c['params']['duration_s'] == 120 for c in cells)
    assert len(cells) == 9 * cfg['repeats']
    assert cfg['donor_env']['WORKER3_CPUS'] == '4'


def test_paired_order_keeps_vanilla_next_to_its_donor_runs():
    # 原版的数随机器负载变（25% 到 43%），每组的原版对照要在同一批里、紧挨着跑
    cfg = run_matrix.load_config(run_matrix.ROOT / 'analysis/matrix/demo.json')
    assert cfg['order'] == 'paired'
    cells = run_matrix.order(run_matrix.expand(cfg), cfg['seed'], cfg['order'])
    keys = [(c['params'].get('group'), c['repeat']) for c in cells]
    blocks = [k for i, k in enumerate(keys) if i == 0 or keys[i - 1] != k]
    assert len(blocks) == len(set(blocks))                  # 同一个（核数, 第几次）的单元连在一起
    for k in set(keys):
        block = [c for c in cells if (c['params'].get('group'), c['repeat']) == k]
        assert block[0]['params']['donors'] == 0             # 每块先跑原版，再跑有 donor 的


def test_driver_three_donors_and_discard_counts(tmp_path):
    runner, ctl, flyer, fly = rig(tmp_path)
    drive = make_flight_driver(runner, ctl, flyer, events=lambda a, b: [],
                               donor_env={'WORKER3_CPUS': '4', 'WORKER3_CPUSET': '88-91'})
    out = drive(cell(3, 'deadline', 35), tmp_path / 'c3')
    up = [c for c in runner.calls if c[:2] == ('compose', 'up')][-1]
    assert 'worker3' in up and ('WORKER3_CPUS', '4') in up and ('WORKER3_CPUSET', '88-91') in up
    assert fly.calls[-1]['workers'] == 3
    assert len([c for c in runner.calls if c[:2] == ('script', 'netem.sh') and c[2] == 'on']) == 3   # 三个 donor 都加网络
    # 飞行期间 /donor status 的增量：导入、丢弃按原因、过时删掉的；导入后接着做完的占比
    assert (out['imports_accepted'], out['discard_late'], out['discard_server'], out['discard_other_world']) == (10, 3, 20, 0)
    assert (out['stale_dropped'], out['stale_cancelled']) == (5, 1)
    assert out['resumed_share'] == 0.0     # FakeFly 的 B：生成 600、接着做完 0、读盘 0
    # 提前量：换世界时带上 -Dmcdonate.demandLookahead，服务器实际用的不对就判无效
    fly.lookahead = '16'
    drive(cell(3, 'deadline', lookahead=16), tmp_path / 'c16')
    world = [c for c in runner.calls if c[:2] == ('script', 'new_world.sh')][-1]
    assert ('MCD_JVM_EXTRA', '-Dmcdonate.demandLookahead=16') in world
    fly.lookahead = None
    with pytest.raises(RuntimeError, match='提前量'):
        drive(cell(3, 'deadline', lookahead=24), tmp_path / 'c24')
    # 下一个 2 donor 的单元：worker3 要停掉
    drive(cell(2, 'deadline'), tmp_path / 'c2')
    assert fly.calls[-1]['workers'] == 2
    assert any(c[:2] == ('compose', 'stop') and 'worker3' in c for c in runner.calls)


def synthetic_campaign(tmp_path):
    """三种配置各 2 轮的合成结果，走真正的 run_matrix 归档，再给图和表用。"""
    cfg = run_matrix.validate({'name': 'syn', 'seed': 1, 'repeats': 2, 'experiments': {'flight': [
        {'donors': [0], 'speed': ['elytra'], 'server_cpus': [2], 'features': ['v0'], 'latency_ms': [0], 'bots': [1], 'duration_s': 120},
        {'donors': [2], 'speed': ['elytra'], 'server_cpus': [2], 'features': ['deadline'], 'latency_ms': [0, 35], 'bots': [1], 'duration_s': 120},
        {'donors': [0, 2], 'speed': ['elytra'], 'server_cpus': [1], 'features': ['deadline'], 'latency_ms': [0], 'bots': [1], 'duration_s': 120}]}})

    def fake(c, out):
        p = c['params']
        d, net, one = p['donors'], p['latency_ms'] > 0, p['server_cpus'] == 1
        # 1 核：原版卡很多，deadline 降得多而且两次都稳；2 核：降得少，第 2 次几乎没降
        if one:
            stall = .60 if d == 0 else .30
        else:
            stall = .36 if d == 0 else (.27 if net else (.24 if c['repeat'] == 1 else .35))
        return dict(stall_fraction=stall + .01 * c['repeat'], stall_s=stall * 120, distance_ratio=1 - stall, latency_p95_ms='inf',
                    void_mean=.8 - (.1 if d else 0), mspt_p95=40.0, mspt_max=120.0, cpu_cores_mean=1.2,
                    full_generated=1100 if d == 0 else 700, full_resumed=0 if d == 0 else 350, full_loaded=0,
                    donor_events=0 if d == 0 else 400, ahead_total=None if d == 0 else (.9 if net else 1.0), netem=net, valid=True,
                    resumed_share=0 if d == 0 else 350 / 1050, imports_accepted=0 if d == 0 else 30, discard_late=0 if d == 0 else 5,
                    discard_server=0 if d == 0 else 40, discard_other_world=0, stale_dropped=0 if d == 0 else 8, stale_cancelled=0)
    run_matrix.run(cfg, {'flight': fake}, tmp_path)
    return tmp_path / 'matrix-syn'


def test_demo_figures_drawn(tmp_path):
    campaign = synthetic_campaign(tmp_path)
    write_tables(campaign)
    status = demo_figures.draw_all(campaign, tmp_path / 'figs')
    assert set(status) == set(demo_figures.FIGURES)
    for name in demo_figures.FIGURES:
        assert status[name] == 'ok', (name, status[name])
        assert (tmp_path / 'figs' / f'{name}.png').stat().st_size > 1000
    # 图下面的数据表也写出来（报告和无障碍用）
    table = json.loads((tmp_path / 'figs' / 'demo-figures.json').read_text())
    assert table['configs'][:3] == ['2 核 原版', '2 核 2 donor deadline', '2 核 2 donor deadline + 网络']
    # 哪个设置下 donor 的效果最大、两次之间最稳：同一组里、同一个第几次的原版减有 donor 的，取两次里小的那个，最大的胜出
    best = table['best']
    assert best['setting'] == '1 核 2 donor deadline' and best['paired_reduction'] == pytest.approx([.30, .30], abs=1e-9)
    two = next(s for s in table['settings'] if s['setting'] == '2 核 2 donor deadline')
    assert two['paired_reduction'] == pytest.approx([.12, .01], abs=1e-9)
    assert all(s['setting'] != '2 核 2 donor deadline + 网络' for s in table['settings'])   # 网络那组是看网络影响的，不参加挑选


def test_demo_figures_without_data(tmp_path):
    (tmp_path / 'matrix-empty').mkdir()
    status = demo_figures.draw_all(tmp_path / 'matrix-empty', tmp_path / 'figs')
    assert all(v == '无数据' for v in status.values())


def test_hypotheses_y_and_z_wording(tmp_path):
    rows = collect(synthetic_campaign(tmp_path))
    assert all(math.isinf(r['latency_p95_ms']) for r in rows)   # 'inf' 读回来是无穷大
    h = {x['item']: x for x in hypotheses(rows)}
    assert h['Y']['verdict'] == '数据不足（第 8 步暂停）'
    assert h['Z']['verdict'] == '数据不足（要真显卡）'
    assert h['stall']['verdict'] == '记录' and h['stall']['value'] == pytest.approx((.25 + .37) / 2)
