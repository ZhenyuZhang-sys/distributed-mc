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


def test_report_puts_max_speed_first(tmp_path):
    # 调度 11-09：原版"能扛住的最高速度"是最重要的对比基准，结果表第一行就放它和 1 个 8 核 donor 的数
    rows = []
    for prof, stalls in (('', {10: 0, 15: .01, 20: .2}), ('1x8', {10: 0, 15: 0, 20: .03}), ('1x4', {10: 0, 15: .02, 20: .3})):
        for v, st in stalls.items():
            rows.append(dict(experiment='flight', group=f'v{v}', scenario='firework', server_cpus=4, donors=1 if prof else 0,
                             donor_profile=prof or None, speed_bps=v, repeat=1, stall_after_exit=st, speed_ratio_after_exit=1 - st,
                             first_stall_after_exit_s=None, mspt_p95=9.0, mspt_max=80.0, cant_keep_up=0, moved_too_quickly=0,
                             resumed_share=.3 if prof else 0, exit_s=8, discard_late=5, discard_server=0, discard_other_world=0,
                             stale_dropped=2, import_ms=100, worker_cores=3.2 if prof else None, tile_s=1.1 if prof else None))
    md = firework.report(rows)
    lines = [l for l in md.splitlines() if l.startswith('|')]
    assert '能扛住的最高速度' in lines[0]
    first = lines[2]
    assert '原版' in first and '15' in first, first
    assert '8 核' in lines[3] and '20' in lines[3], lines[3]
    assert '4 核' in md and '演示，粗测' in md


def test_firework_default_speed_is_used_for_the_ideal(tmp_path):
    # 11-09 第一轮：烟花场景没写 speed_bps 时路线按 33.5 飞，驱动却拿鞘翅的 30 算理想速度，速度比成了 1.086
    from analysis.flight_driver import scenario_speed
    assert scenario_speed(dict(scenario='firework', speed='elytra')) == 33.5
    assert scenario_speed(dict(scenario='firework_turning', speed='elytra', speed_bps=20)) == 20
    assert scenario_speed(dict(scenario='turning', speed='elytra')) == 30


def test_recompute_after_exit_from_archive(tmp_path):
    # 报告按归档的逐秒行重算出缓存区以后的指标（第一轮的速度比要按 33.5 重算）
    import json
    raw = tmp_path / 'cell' / 'attempt-001' / 'flight' / 'raw'
    raw.mkdir(parents=True)
    rows = [dict(t_s=i + 1, x=-32000.5 + 33.5 * (i + 1), z=32008.5, ticks=20, stalled_ticks=0) for i in range(20)]
    (raw / 'seconds.jsonl').write_text(''.join(json.dumps(r) + '\n' for r in rows))
    m = firework.recompute(tmp_path / 'cell' / 'attempt-001', dict(scenario='firework', speed='elytra', cache_radius=16))
    assert m['exit_s'] == 8 and m['speed_ratio_after_exit'] == pytest.approx(1.0)


def test_driver_sets_view_distance(tmp_path):
    from step10.test_08_scenarios import real_rig
    runner, ctl, flyer, fly, drive = real_rig(tmp_path)
    runner.slot_cpuset = lambda: '16-19'
    cell = dict(experiment='flight', repeat=1, params=dict(donors=0, speed='elytra', server_cpus=4, bots=1, features='v0',
                                                          latency_ms=0, duration_s=60, scenario='firework', view_distance=16))
    drive(cell, tmp_path / 'c')
    cmds = [c[1] for c in runner.calls if c[0] == 'rcon']
    assert 'bench viewdistance 16' in cmds
    from analysis import run_matrix
    run_matrix._check_value('flight', 'view_distance', 16)
    with pytest.raises(ValueError):
        run_matrix._check_value('flight', 'view_distance', 40)


# ---- 用户的"卡"多半是前方空白（调度 11-09）：前方已就绪距离、视距圈就绪率 ----


def test_view_ready_from_void_rate():
    rows = [dict(t_s=i + 1, x=30.0 * (i + 1), z=0.0, ticks=20, stalled_ticks=0, void_rate=v)
            for i, v in enumerate([0, 0, 0, .1, .3, .2, .5])]
    m = firework.view_ready(rows, start=(0, 0), radius=100)       # 第 4 秒（x=120）出缓存区，之后 .3 .2 .5
    assert m['view_ready_mean'] == pytest.approx(1 - (.3 + .2 + .5) / 3)
    assert m['view_ready_min'] == pytest.approx(.5)


def test_forward_ready_distance():
    # 沿 +x 飞，每秒 32 格（2 个 chunk）。chunk 就绪的记录：前方 x=0..5 的 3 列宽的带子（z = -1、0、1）都就绪，
    # 第 2 秒时 x=4 那列中间缺一个 → 前方连续就绪到 x=3
    ev = []
    for cx in range(0, 8):
        for cz in (-1, 0, 1):
            ev.append(dict(t_ms=0, cx=cx, cz=cz, ev='enter'))
            if not (cx == 4 and cz == 0) and cx < 6:
                ev.append(dict(t_ms=0, cx=cx, cz=cz, ev='ready'))
    ev.append(dict(t_ms=2500, cx=4, cz=0, ev='ready'))   # 2.5 秒补上
    rows = [dict(t_s=1, x=8.0, z=8.0), dict(t_s=2, x=40.0, z=8.0), dict(t_s=3, x=72.0, z=8.0)]
    per = firework.forward_ready(ev, rows, view=10)
    # 第 2 秒：玩家在 chunk 2，往前 chunk 3 就绪、chunk 4 缺 → 前方连续就绪 1 个 chunk（16 格）
    assert per[2] == 16
    # 第 3 秒：玩家在 chunk 4，chunk 5 就绪、chunk 6 没就绪 → 16 格
    assert per[3] == 16


def test_front_sector_ready():
    # 调度 11-09：前方 90 度扇形（左右各 45 度、视距以内）里就绪的比例。沿 +x 飞，视距 2：
    # 按 chunk 中心相对玩家的角度和距离算，正好 45 度的算在内，玩家脚下那个不算
    rows = [dict(t_s=1, x=8.0, z=8.0), dict(t_s=2, x=24.0, z=8.0)]   # 第 2 秒在 chunk (1,0)，方向 +x
    ev = [dict(t_ms=0, cx=cx, cz=cz, ev='enter') for cx in range(-3, 6) for cz in range(-3, 4)]
    ev += [dict(t_ms=0, cx=cx, cz=cz, ev='ready') for cx in range(-3, 6) for cz in range(-3, 4) if cx <= 2]
    per = firework.front_sector_ready(ev, rows, view=2)
    # 第 2 秒，玩家 (24, 8)：前方扇形 = 中心在 x 方向 ±45 度、距离 ≤ 32 的 chunk：(2,0) (3,0) (2,1) (2,-1)；
    # (3,±1) 距离 √(32²+16²) > 32 不算。x ≤ 2 的就绪 → 3/4
    assert per[2] == pytest.approx(3 / 4)
    m = firework.sector_summary(per, rows, start=(0, 0), radius=0)
    assert m['front_sector_ready_mean'] == pytest.approx(per[2]) and m['front_sector_ready_min'] == pytest.approx(per[2])


def test_report_has_front_sector_column():
    rows = [dict(experiment='flight', scenario='firework', speed_bps=33.5, donors=0, stall_after_exit=.1,
                 front_sector_ready_mean=.5, front_sector_ready_min=.25)]
    assert '前方 90 度扇形就绪（平均 / 最低）' in firework.report(rows) and '50% / 25%' in firework.report(rows)


def test_report_compares_code_versions():
    # 调度 11-09 三臂：原版 / 637c2de + donor / 30a7c22 + donor，每套代码各带自己的原版。行带 code，配置名后面标上代码版本
    rows = []
    for code in ('637c2de', '30a7c22'):
        for prof in ('', '1x8'):
            for scen in ('firework', 'firework_turning'):
                rows.append(dict(experiment='flight', scenario=scen, speed_bps=33.5, donors=1 if prof else 0, donor_profile=prof or None,
                                 code=code, stall_after_exit=.1 if prof else .3))
    md = firework.report(rows)
    for code in ('637c2de', '30a7c22'):
        assert f'原版（0 个 donor） · {code}' in md and f'1 个 donor，8 核 · {code}' in md
    real = [l for l in md.splitlines() if l.startswith('| 直线')]
    assert len(real) == 4 and real[0].split('|')[2].strip().startswith('原版')   # 原版排前面


def test_cli_label_per_campaign():
    assert firework.parse_campaign_arg('runs/matrix-x=30a7c22') == ('runs/matrix-x', '30a7c22')
    assert firework.parse_campaign_arg('runs/matrix-x') == ('runs/matrix-x', None)


def test_autosave_seconds_from_cumulative_ticks():
    # 起飞时 tick 5990；每秒 20 tick，第 1 秒走到 6010 → 那一秒里有 autosave（6000）；之后 12000 在第 301 秒
    rows = [dict(t_s=i, ticks=20) for i in range(1, 400)]
    assert firework.autosave_seconds(rows, 5990) == [1, 301]
    assert firework.autosave_seconds(rows, None) == []


def test_report_has_demo_section():
    rows = []
    for v in (10, 15):
        for d in (0, 1):
            rows.append(dict(experiment='flight', scenario='firework', speed_bps=v, donors=d, donor_profile='1x8' if d else None,
                             stall_after_exit=0.0, forward_ready_median=80 * (1 + d), view_ready_mean=.5 + .3 * d,
                             front_sector_ready_mean=.4 + .4 * d))
    md = firework.report(rows)
    assert '## 演示用：10/15 格/秒，同样不卡、前方已生成区域翻倍' in md
    sec = md.split('## 演示用')[1]
    assert '| 10 | 原版（0 个 donor） | 0% | 80 | 50% | 40% |' in sec and '| 10 | 1 个 donor，8 核 | 0% | 160 | 80% | 80% |' in sec
