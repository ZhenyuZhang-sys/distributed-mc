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
