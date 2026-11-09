"""最狠的场景（用户 11-09）：烟花鞘翅从预先生成好的区域（缓存区）中心飞出去，出了缓存区以后完全靠生成。不起服务器。"""
import math

import pytest

from analysis import firework
from bench import scenarios


def segs(spec):
    return [tuple(float(x) for x in p.split(',')) for p in spec.split(';')]


def test_firework_speed_and_routes():
    # 连续放烟花平飞约 33.5 格/秒（Minecraft Wiki 的 Elytra 页：烟花推进的最大水平速度约 33.5 m/s）
    assert scenarios.FIREWORK == 33.5
    (s,) = segs(scenarios.routes('firework', seed=1, seconds=90)[0])
    assert s == (-90.0, 33.5, 90.0)
    t = segs(scenarios.routes('firework_turning', seed=1, seconds=120, cache_radius_blocks=256)[0])
    assert t[0][0] == -90 and t[0][2] >= 256 / 33.5          # 第一段直线飞出缓存区，出了才开始转
    assert all(x[1] == 33.5 for x in t) and sum(x[2] for x in t) == pytest.approx(120)
    turns = [abs(((b[0] - a[0] + 180) % 360) - 180) for a, b in zip(t, t[1:])]
    assert turns and all(30 <= d <= 90 for d in turns)
    # 速度可以换（找"能扛住的最高速度"时从 10 格/秒往上加）
    (s20,) = segs(scenarios.routes('firework', seed=1, seconds=60, speed=20)[0])
    assert s20[1] == 20


def rows(xs, stalled=None):
    return [dict(t_s=i + 1, x=x, z=0.0, ticks=20, stalled_ticks=(stalled or {}).get(i + 1, 0)) for i, x in enumerate(xs)]


def test_after_exit_metrics():
    # 起点 0，缓存区半径 100 格；每秒 30 格：第 4 秒（x=120）出缓存区
    xs = [30, 60, 90, 120, 150, 170, 200, 230]          # 第 6 秒只走了 20 格（卡了）
    m = firework.after_exit(rows(xs, {6: 10}), start=(0, 0), radius=100, speed=30)
    assert m['exit_s'] == 4
    assert m['stall_after_exit'] == pytest.approx(10 / 80)       # 出去以后 4 秒、80 个 tick 里卡了 10 个
    assert m['speed_ratio_after_exit'] == pytest.approx((230 - 120) / (30 * 4))
    assert m['first_stall_after_exit_s'] == 2                     # 出去以后第 2 秒第一次卡住
    never = firework.after_exit(rows([10, 20, 30]), start=(0, 0), radius=100, speed=10)
    assert never['exit_s'] is None and never['stall_after_exit'] is None   # 没飞出缓存区：这一轮不能用


def test_turning_distance_uses_path_not_displacement():
    # 转弯以后按走过的路程算，不按离起点的直线距离
    r = [dict(t_s=1, x=30, z=0, ticks=20, stalled_ticks=0), dict(t_s=2, x=60, z=0, ticks=20, stalled_ticks=0),
         dict(t_s=3, x=60, z=30, ticks=20, stalled_ticks=0), dict(t_s=4, x=60, z=60, ticks=20, stalled_ticks=0)]
    m = firework.after_exit(r, start=(0, 0), radius=50, speed=30)
    assert m['exit_s'] == 2 and m['speed_ratio_after_exit'] == pytest.approx(1.0)


def test_max_sustainable_speed():
    runs = [dict(config='原版', speed_bps=s, stall_after_exit=v) for s, v in [(10, 0), (15, .01), (20, .04), (25, .12), (30, .3)]]
    runs += [dict(config='1 donor 8 核', speed_bps=s, stall_after_exit=v) for s, v in [(10, 0), (15, 0), (20, .02), (25, .03), (30, .2)]]
    runs += [dict(config='坏的', speed_bps=s, stall_after_exit=v) for s, v in [(10, .2), (15, 0)]]
    best = firework.max_sustainable(runs, limit=0.05)
    assert best['原版'] == 20 and best['1 donor 8 核'] == 25
    assert best['坏的'] is None                # 最低速度就扛不住：没有数（不跳过中间失败的速度）


def test_driver_pregenerates_cache_and_flies_out(tmp_path):
    from step10.test_08_scenarios import real_rig
    runner, ctl, flyer, fly, drive = real_rig(tmp_path)
    runner.slot_cpuset = lambda: '16-19'
    real = runner.rcon

    def rcon(cmd):
        if cmd == 'chunky start':
            runner.server_log.append('[12:00:00] [Server thread/INFO]: [Chunky] Task finished for minecraft:overworld. Processed: 1089 chunks')
        return real(cmd)
    runner.rcon = rcon
    cell = dict(experiment='flight', repeat=1, params=dict(donors=1, donor_profile='1x8', speed='elytra', speed_bps=20, server_cpus=4,
                                                          bots=1, features='deadline+throughput', latency_ms=0, duration_s=90,
                                                          scenario='firework', cache_radius=16))
    out = drive(cell, tmp_path / 'c')
    cmds = [c[1] for c in runner.calls if c[0] == 'rcon']
    start = flyer_start = __import__('bench.demo.ab', fromlist=['DEFAULT_START']).DEFAULT_START
    assert f'chunky center {int(start[0])} {int(start[1])}' in cmds and 'chunky radius 256' in cmds   # 半径 16 个 chunk
    assert cmds.index('chunky start') < cmds.index('donor demand on')       # 先生成缓存区，再开 donor
    assert fly.calls[-1]['route'] == '-90,20,90'                          # 速度换成 20
    assert out['cache_chunks'] == 1089 and 'stall_after_exit' in out and 'exit_s' in out
