"""真实场景（调度 11-09）：按 seed 生成的飞行路线（给 /bench fly route），和矩阵里的场景、donor 配置维度。不起服务器。"""
import math

import pytest

from analysis import run_matrix
from bench import scenarios


def segs(spec):
    out = []
    for part in spec.split(';'):
        yaw, speed, seconds = (float(x) for x in part.split(','))
        out.append((yaw, speed, seconds))
    return out


@pytest.mark.parametrize('name', scenarios.SCENARIOS)
def test_routes_are_valid_and_last_the_flight(name):
    routes = scenarios.routes(name, seed=424242, seconds=120)
    assert 1 <= len(routes) <= 2
    for spec in routes:
        s = segs(spec)
        assert 1 <= len(s) <= 64                               # 和 Java 的 Route.MAX_SEGMENTS 一样
        assert sum(x[2] for x in s) == pytest.approx(120)      # 总时长就是这一轮
        assert all(0 <= x[1] <= 1000 and x[2] > 0 for x in s)
    assert scenarios.routes(name, seed=424242, seconds=120) == routes   # 同一个 seed 每次一样（两轮成对比较要同一条路线）


def test_turning_elytra_turns_every_20_to_40_seconds_by_30_to_90_degrees():
    s = segs(scenarios.routes('turning', seed=7, seconds=300)[0])
    assert all(x[1] == scenarios.ELYTRA for x in s)
    assert all(20 <= x[2] <= 40 for x in s[:-1])               # 最后一段可能被总时长截短
    turns = [abs(((b[0] - a[0] + 180) % 360) - 180) for a, b in zip(s, s[1:])]
    assert turns and all(30 <= t <= 90 for t in turns), turns


def test_out_and_back_returns_over_the_same_ground():
    s = segs(scenarios.routes('out_and_back', seed=1, seconds=120)[0])
    assert len(s) == 2 and s[0][2] == s[1][2] == 60 and s[0][1] == s[1][1] == scenarios.ELYTRA
    assert abs(((s[1][0] - s[0][0]) % 360) - 180) < 1e-9       # 回程正好掉头：去程生成、回程读盘


def test_mixed_walk_horse_with_stops():
    s = segs(scenarios.routes('mixed', seed=3, seconds=300)[0])
    speeds = {x[1] for x in s}
    assert speeds <= {scenarios.WALK, scenarios.HORSE, 0.0}
    assert scenarios.WALK in speeds and scenarios.HORSE in speeds and 0.0 in speeds, speeds   # 走、骑马、偶尔停下都有


def test_two_players_fly_different_directions():
    a, b = scenarios.routes('two_players', seed=1, seconds=120)
    (ya, _, _), = segs(a)
    (yb, _, _), = segs(b)
    gap = abs(((ya - yb + 180) % 360) - 180)
    assert gap >= 60, (ya, yb)


def test_unknown_scenario_rejected():
    with pytest.raises(ValueError):
        scenarios.routes('teleport', seed=1, seconds=120)


def test_matrix_accepts_scenario_and_donor_profile():
    cfg = run_matrix.validate({'name': 'real', 'seed': 1, 'repeats': 1, 'order': 'paired',
                               'donor_profiles': {'1x8': {'donors': 1, 'env': {'WORKER_CPUS': '8'}},
                                                  '3x4': {'donors': 3, 'env': {'WORKER3_CPUS': '4'}}},
                               'experiments': {'flight': [
                                   {'group': 'g', 'donors': [0], 'scenario': ['turning'], 'speed': ['elytra'], 'server_cpus': [4],
                                    'features': ['v0'], 'duration_s': 120},
                                   {'group': 'g', 'donors': [1], 'donor_profile': ['1x8', '3x4'], 'scenario': ['turning'],
                                    'speed': ['elytra'], 'server_cpus': [4], 'features': ['deadline+throughput'], 'duration_s': 120}]}})
    cells = run_matrix.expand(cfg)
    assert {c['params'].get('donor_profile') for c in cells} == {None, '1x8', '3x4'}
    with pytest.raises(ValueError):
        run_matrix.validate({**cfg, 'experiments': {'flight': [{'donors': [1], 'scenario': ['teleport'], 'speed': ['elytra'],
                                                                 'server_cpus': [4], 'duration_s': 60}]}})


def test_feature_combos():
    from analysis.flight_driver import _features_on
    assert _features_on('deadline+throughput') == ('deadline', 'throughput')
    assert _features_on('v0') == ()
    with pytest.raises(ValueError):
        _features_on('deadline+warp')


# ---- 驱动：路线、两个玩家、donor 配置、新指标 ----

from step10.test_06_demo import Clock, FakeFly, FakeRunner   # noqa: E402
from analysis.flight_driver import make_flight_driver       # noqa: E402
from bench.demo.control import Controller, Flyer           # noqa: E402
from bench.demo.poll import Poller                         # noqa: E402


class RouteFly(FakeFly):
    def __call__(self, server, run, player, start, speed, seconds, out_dir, **kw):
        self.runner.status['import'] = dict(totalMs=self.runner.status.get('import', {}).get('totalMs', 0) + 250, count=5)
        for w in self.runner.status['workers']:
            w['done'] = w.get('done', 0) + 12
        self.runner.server_log += ["[12:00:01] [Server thread/WARN]: Can't keep up! Is the server overloaded?",
                                   "[12:00:02] [Server thread/WARN]: abl moved too quickly! 3,0,0"]
        out = super().__call__(server, run, player, start, speed, seconds, out_dir, **kw)
        self.calls[-1].update(route=kw.get('route'), player=player)
        return out


def real_rig(tmp_path):
    runner = FakeRunner()
    runner.netem = True
    runner.server_log = []
    runner.server_log_since = lambda t: list(runner.server_log)
    runner.worker_cpu_cores = lambda services, seconds: {s: 1.5 for s in services}
    poller = Poller(runner, runs_dir=tmp_path / 'runs', clock=Clock())
    fly = RouteFly(runner)
    flyer = Flyer(runner, poller, tmp_path / 'artifacts', fly=fly)
    ctl = Controller(runner, poller, flyer, wait_timeout=1, wait_interval=0)
    profiles = {'1x8': {'donors': 1, 'env': {'WORKER_CPUS': '8', 'WORKER_CPUSET': '24-31'}},
                '3x4': {'donors': 3, 'env': {'WORKER3_CPUS': '4'}}}
    return runner, ctl, flyer, fly, make_flight_driver(runner, ctl, flyer, events=lambda a, b: [], donor_profiles=profiles)


def rcell(scenario, donors=1, profile='1x8', features='deadline+throughput', cpus=4):
    p = dict(donors=donors, speed='elytra', server_cpus=cpus, bots=1, features=features, latency_ms=0, duration_s=120,
             scenario=scenario)
    if donors:
        p['donor_profile'] = profile
    return dict(experiment='flight', repeat=1, params=p)


def test_driver_flies_the_scenario_route(tmp_path):
    runner, ctl, flyer, fly, drive = real_rig(tmp_path)
    runner.slot_cpuset = lambda: '16-19'
    out = drive(rcell('turning'), tmp_path / 'c1')
    assert fly.calls[-1]['route'] == scenarios.routes('turning', seed=424242, seconds=120)[0]
    up = [c for c in runner.calls if c[:2] == ('compose', 'up')][-1]
    assert ('WORKER_CPUS', '8') in up and ('WORKER_CPUSET', '24-31') in up and 'worker2' not in up   # 1 个强 donor
    assert fly.calls[-1]['features'] == ['deadline', 'throughput']
    # 新指标
    assert out['cant_keep_up'] == 1 and out['moved_too_quickly'] == 1
    assert out['import_ms'] == 250
    assert out['worker_cores'] == 1.5 and out['tile_s'] == pytest.approx(12 / 120)
    assert out['scenario_route'].startswith('-90,30,')


def test_driver_two_players_at_once(tmp_path):
    runner, ctl, flyer, fly, drive = real_rig(tmp_path)
    runner.slot_cpuset = lambda: '16-19'
    out = drive(rcell('two_players', donors=0, profile=None, features='v0'), tmp_path / 'c2')
    routes = scenarios.routes('two_players', seed=424242, seconds=120)
    assert sorted(c['route'] for c in fly.calls[-2:]) == sorted(routes)
    assert len({c['player'] for c in fly.calls[-2:]}) == 2              # 两个不同的假玩家
    assert out['players'] == 2 and 'stall_fraction' in out             # 两个人的卡住占比取均值
    assert out['stall_fraction_max'] >= out['stall_fraction']


def test_profile_must_exist(tmp_path):
    runner, ctl, flyer, fly, drive = real_rig(tmp_path)
    runner.slot_cpuset = lambda: '16-19'
    with pytest.raises(ValueError, match='donor_profile'):
        drive(rcell('turning', profile='9x9'), tmp_path / 'c3')
