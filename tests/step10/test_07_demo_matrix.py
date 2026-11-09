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


def cell(donors, features='v0', latency_ms=0, repeat=1, duration_s=120):
    return dict(experiment='flight', repeat=repeat, params=dict(donors=donors, speed='elytra', server_cpus=2, bots=1,
                                                                features=features, latency_ms=latency_ms, duration_s=duration_s))


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


def test_demo_config_expands_to_three_configs():
    cfg = run_matrix.load_config(run_matrix.ROOT / 'analysis/matrix/demo.json')
    cells = run_matrix.expand(cfg)
    configs = {(c['params']['donors'], c['params']['features'], c['params']['latency_ms']) for c in cells}
    assert configs == {(0, 'v0', 0), (2, 'deadline', 0), (2, 'deadline', 35)}
    assert all(c['params']['server_cpus'] == 2 and c['params']['speed'] == 'elytra' and c['params']['duration_s'] == 120
               for c in cells)
    assert len(cells) == 3 * cfg['repeats']


def synthetic_campaign(tmp_path):
    """三种配置各 2 轮的合成结果，走真正的 run_matrix 归档，再给图和表用。"""
    cfg = run_matrix.validate({'name': 'syn', 'seed': 1, 'repeats': 2, 'experiments': {'flight': [
        {'donors': [0], 'speed': ['elytra'], 'server_cpus': [2], 'features': ['v0'], 'latency_ms': [0], 'bots': [1], 'duration_s': 120},
        {'donors': [2], 'speed': ['elytra'], 'server_cpus': [2], 'features': ['deadline'], 'latency_ms': [0, 35], 'bots': [1], 'duration_s': 120}]}})

    def fake(c, out):
        p = c['params']
        d, net = p['donors'], p['latency_ms'] > 0
        stall = .36 if d == 0 else (.27 if net else .24)
        return dict(stall_fraction=stall + .01 * c['repeat'], stall_s=stall * 120, distance_ratio=1 - stall, latency_p95_ms='inf',
                    void_mean=.8 - (.1 if d else 0), mspt_p95=40.0, mspt_max=120.0, cpu_cores_mean=1.2,
                    full_generated=1100 if d == 0 else 700, full_resumed=0 if d == 0 else 350, full_loaded=0,
                    donor_events=0 if d == 0 else 400, ahead_total=None if d == 0 else (.9 if net else 1.0), netem=net, valid=True)
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
    assert table['configs'] == ['原版（0 donor）', 'deadline（2 donor）', 'deadline + 网络（2 donor）']


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
    assert h['stall']['verdict'] == '记录' and h['stall']['value'] == pytest.approx((.25 + .26) / 2)
