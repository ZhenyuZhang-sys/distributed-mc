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
    # 沿 +x 飞，每秒 32 格（2 个 chunk）。前方 x=0..5 的 3 列宽的带子（z = -1、0、1）都就绪，x=4 那列中间缺一个，2.5 秒补上。
    # codex-audit K3：距离 = 从玩家位置沿中心线（和左右各 16 格的两条平行线）走，碰到第一个没就绪的 chunk 的边界有多远
    ev = []
    for cx in range(0, 8):
        for cz in (-1, 0, 1):
            ev.append(dict(t_ms=0, cx=cx, cz=cz, ev='enter'))
            if not (cx == 4 and cz == 0) and cx < 6:
                ev.append(dict(t_ms=0, cx=cx, cz=cz, ev='ready'))
    ev.append(dict(t_ms=2500, cx=4, cz=0, ev='ready'))
    rows = [dict(t_s=1, x=8.0, z=8.0), dict(t_s=2, x=40.0, z=8.0), dict(t_s=3, x=72.0, z=8.0)]
    per = firework.forward_ready(ev, rows, view=10)
    assert per[2] == pytest.approx(24)    # 玩家 x=40，chunk 4（x 从 64 起）没就绪
    assert per[3] == pytest.approx(24)    # 玩家 x=72，chunk 6（x 从 96 起）没就绪

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
    # codex-audit K9：标题不先下结论；结论句从同条件的重复样本算（中位数），只有 1 轮时注明
    rows = []
    for rep in (1, 2, 3):
        for v in (10, 15):
            for d in (0, 1):
                rows.append(dict(experiment='flight', scenario='firework', speed_bps=v, donors=d, donor_profile='1x8' if d else None,
                                 repeat=rep, stall_after_exit=0.0 if d or v == 10 else .08, forward_ready_median=80 * (1 + d) + rep,
                                 view_ready_mean=.5 + .3 * d, front_sector_ready_mean=.4 + .4 * d))
    md = firework.report(rows)
    assert '## 演示用：10/15 格每秒对照' in md and '翻倍' not in md.split('## 演示用')[1].splitlines()[0]
    sec = md.split('## 演示用')[1]
    assert '| 10 | 原版（0 个 donor） | 0%（0%～0%，3 轮） | 82（81～83，3 轮） |' in sec, sec
    assert '10 格/秒：两种配置都不卡（卡住 < 5%）；前方已就绪距离 1 个 donor，8 核 是原版的 2.0 倍（中位数，各 3 轮）' in sec, sec
    assert '15 格/秒：原版（0 个 donor） 卡住' in sec
    one = firework.report([r for r in rows if r['repeat'] == 1])
    assert '各 1 轮，只能当粗测' in one


def test_autosave_uses_flight_start_tick_from_run_json():
    # codex-audit K8：驱动取的 tick 在等初始视野之前；run.json 有 start_tick（起飞那一刻）时用它
    rows = [dict(t_s=i, ticks=20) for i in range(1, 10)]
    assert firework.autosave_from(rows, dict(start_tick=5990), dict(fly_start_tick=5000)) == [1]
    assert firework.autosave_from(rows, dict(start_tick=-1), dict(fly_start_tick=5990)) == [1]
    assert firework.autosave_from(rows, {}, {}) == []

def test_report_median_and_range_for_repeats():
    # 调度 11-09：关键对比每臂至少 3 轮，报中位数和范围
    rows = [dict(experiment='flight', scenario='firework_turning', speed_bps=33.5, donors=0, stall_after_exit=s, repeat=i)
            for i, s in enumerate((.41, .35, .48), 1)]
    md = firework.report(rows)
    line = next(l for l in md.splitlines() if l.startswith('| 转弯'))
    assert '41%（35%～48%，3 轮）' in line, line



def test_view_shape_matches_vanilla():
    # 原版 ChunkTrackingView.of(center, 10).contains：453 个（ViewTracker 的注释）
    assert sum(firework.in_view(0, 0, 10, x, z) for x in range(-12, 13) for z in range(-12, 13)) == 453


def test_initial_view_counts_as_ready_when_archive_says_so():
    # codex-audit K1：起飞前的初始视野不进事件流。run.json 的 initial.not_ready = 0 时，初始视野（起点 chunk 的原版视野）全算就绪，
    # 离开视野后不再算（初始视野离开也不发 leave）；有 initial.jsonl（逐 chunk 状态）时按它
    run = dict(start_x=8.0, start_z=8.0, view_distance=2, initial=dict(view=21, not_ready=0))
    rows = [dict(t_s=1, x=8.0, z=8.0), dict(t_s=2, x=8.0 + 16 * 8, z=8.0)]
    per = firework.front_sector_ready([], rows, view=2, run=run)
    assert per[1] == pytest.approx(1.0)       # 还在起点：前方扇形全是初始视野，都就绪
    assert per[2] == pytest.approx(0.0)       # 飞出 8 个 chunk：初始视野都离开了，新进来的没有就绪记录
    run_unknown = dict(run, initial=dict(view=21, not_ready=3))
    assert firework.front_sector_ready([], rows, view=2, run=run_unknown) is None   # 初始有没就绪的、又没有逐 chunk 记录：算不了
    init = [dict(cx=cx, cz=cz, ready=not (cx == 1 and cz == 0)) for cx in range(-3, 4) for cz in range(-3, 4)
            if firework.in_view(0, 0, 2, cx, cz)]
    per = firework.front_sector_ready([], rows[:1], view=2, run=run_unknown, initial=init)
    assert 0 < per[1] < 1


def test_heading_follows_route_when_blocked():
    # codex-audit K2：route 模式下被挡住不动时，方向按 run.json 的航段和时刻取，不沿用旧的运动方向
    run = dict(mode='route', route='-90,30,2;0,30,2', start_x=8.0, start_z=8.0, view_distance=2, initial=dict(view=0, not_ready=0))
    rows = [dict(t_s=1, x=38.0, z=8.0), dict(t_s=2, x=68.0, z=8.0), dict(t_s=3, x=68.0, z=8.0)]   # 第 3 秒该往南（+z）了，但被挡住
    ev = [dict(t_ms=0, cx=cx, cz=cz, ev='enter') for cx in range(0, 8) for cz in range(-3, 4)]
    ev += [dict(t_ms=0, cx=cx, cz=cz, ev='ready') for cx in range(0, 8) for cz in range(1, 4)]   # 南边就绪、东边没有
    per = firework.front_sector_ready(ev, rows, view=2, run=run)
    assert per[3] == pytest.approx(1.0)
    assert firework.forward_ready(ev, rows, view=2, run=run)[3] > 0


def test_forward_ready_does_not_skip_chunks_on_diagonal():
    # codex-audit K3：每 16 格采一个点，斜飞会跳过航线擦过的 chunk。DDA 逐 chunk 走，和细采样（0.01 格）对得上
    import random
    rnd = random.Random(5)
    for _ in range(200):
        a = rnd.uniform(0, 2 * math.pi)
        d = (math.cos(a), math.sin(a))
        x0, z0 = rnd.uniform(0, 16), rnd.uniform(0, 16)
        holes = {(rnd.randint(-10, 10), rnd.randint(-10, 10)) for _ in range(6)} - {(0, 0)}
        ready = lambda c: c not in holes   # noqa: E731
        got = firework.first_unready(ready, x0, z0, d, 160)
        want = 160
        t = 0.0
        while t <= 160:
            c = (math.floor((x0 + d[0] * t) / 16), math.floor((z0 + d[1] * t) / 16))
            if not ready(c):
                want = t
                break
            t += 0.01
        assert got == pytest.approx(want, abs=0.02), (a, x0, z0, holes)
    # 正好穿过 chunk 角：两边相邻的都算擦过（supercover）
    assert firework.first_unready(lambda c: c != (1, 0), 8.0, 8.0, (math.sqrt(.5), math.sqrt(.5)), 160) == pytest.approx(math.hypot(8, 8))


def test_pregen_replaces_a_stale_chunky_task():
    # 11-09：中途打断的一轮把 Chunky 任务留在 config/chunky/tasks 里（跨世界不清），下一轮全新世界 chunky start 就拒绝。
    # 驱动给全新世界预生成时，用 chunky confirm 换成这次的任务
    from bench.server import chunky
    class Srv:
        def __init__(self):
            self.sent, self.log = [], []
        def rcon(self, cmd):
            self.sent.append(cmd)
            if cmd == 'chunky start':
                return "[Chunky] A task was already started for this world. To continue running it, type '/chunky continue'."
            if cmd == 'chunky confirm':
                self.log.append('[Chunky] Task finished for minecraft:overworld. Processed: 1089 chunks (100.00%)')
            return 'ok'
        def logs(self):
            return '\n'.join(self.log)
    s = Srv()
    with pytest.raises(RuntimeError, match='残留'):
        chunky(s, 0, 0, 256, timeout=1)
    s = Srv()
    assert chunky(s, 0, 0, 256, timeout=1, replace_stale=True) == 1089
    assert s.sent[-1] == 'chunky confirm'


def test_report_lists_each_round_with_its_slot():
    # 调度 11-09：同一个代码 1×8 在 k3 用 1.42 核、在 k2 只用 0.64 核，差别来自槽位。每轮记上槽位，看同配置在不同槽位差多少
    rows = [dict(experiment='flight', scenario='firework_turning', speed_bps=33.5, donors=1, donor_profile='1x8', code='10cadc8',
                 slot=s, repeat=i, stall_after_exit=st, worker_cores=c, tile_s=t)
            for i, (s, st, c, t) in enumerate((('k2', .49, .64, .21), ('k3', .33, 1.42, .40)), 1)]
    md = firework.report(rows)
    assert '## 每轮明细' in md
    sec = md.split('## 每轮明细')[1]
    assert '| 转弯 | 1 个 donor，8 核 · 10cadc8 | 1 | k2 | 49% |' in sec and '| k3 | 33% | 1.4 | 0.40 |' in sec, sec


def test_spike_section_and_cross_slot_note(tmp_path):
    # 调度 11-09：MSPT ≥ 200 ms 的尖峰单列一类；GC 日志里最长停顿不够长的归"原版生怪同步取 chunk"；跨槽位的 donor 用核不直接比
    rows = [dict(experiment='flight', scenario='firework_turning', speed_bps=33.5, donors=0, code='10cadc8', slot='k2', repeat=2,
                 stall_after_exit=.5, mspt_max=2248.0, gc_max_ms=93.0),
            dict(experiment='flight', scenario='firework', speed_bps=33.5, donors=1, donor_profile='1x8', code='30a7c22', slot='k3', repeat=1,
                 stall_after_exit=.23, mspt_max=305.0),
            dict(experiment='flight', scenario='firework', speed_bps=33.5, donors=1, donor_profile='1x8', code='2359f7d', slot='k3', repeat=1,
                 stall_after_exit=.24, mspt_max=56.0, gc_max_ms=76.0)]
    md = firework.report(rows)
    sec = md.split('## 尖峰')[1]
    assert '| 转弯 | 原版（0 个 donor） · 10cadc8 | 2 | k2 | 2248 | 93 | 原版生怪同步取 chunk（GC 停顿不够长） |' in sec, sec
    assert '| 305 | — | 原版生怪同步取 chunk（推定，没有 GC 日志） |' in sec
    assert '56 |' not in sec
    assert '不同槽位' in md.split('## 每轮明细')[1]
    # 归档里的 GC 日志：最长停顿
    log = tmp_path / 'server-log.txt'
    log.write_text('2026-11-09T14:18:22Z [2026-11-09T09:18:22.115-0500][483.022s] GC(224) Pause Young (Mixed) 835M->381M(882M) 27.388ms\n'
                   '2026-11-09T14:18:29Z [2026-11-09T09:18:29.423-0500][490.330s] GC(227) Pause Remark 389M->389M(883M) 93.5ms\n')
    assert firework.gc_max_ms(log) == pytest.approx(93.5)
    assert firework.gc_max_ms(tmp_path / 'nope.txt') is None


def test_blank_time_counts_front_chunks_outside_cache():
    # 调度 11-09 用户定的主指标：前方 90 度扇形里、进视野时还没就绪的 chunk，从进视野到服务器可发送（ready 事件）的秒数
    from analysis.firework import blank_times, blank_summary
    rows = [dict(t_s=t, x=16.0 * t + 8, z=8.0, ticks=20, stalled_ticks=0) for t in range(0, 11)]   # 往 +x 飞，每秒 1 个 chunk
    run = dict(start_x=8.0, start_z=8.0, view_distance=2, initial=dict(not_ready=0))
    ev = [dict(t_ms=1000, cx=4, cz=0, ev='enter'), dict(t_ms=3000, cx=4, cz=0, ev='ready'),      # 前方、缓存区外：2 秒
          dict(t_ms=2000, cx=5, cz=0, ev='enter'), dict(t_ms=2000, cx=5, cz=0, ev='ready'),      # 进来就就绪：0 秒
          dict(t_ms=3000, cx=6, cz=1, ev='enter'), dict(t_ms=7000, cx=6, cz=1, ev='ready'),      # 4 秒
          dict(t_ms=3000, cx=2, cz=3, ev='enter'), dict(t_ms=9000, cx=2, cz=3, ev='ready'),      # 正侧面（90 度）：不算
          dict(t_ms=4000, cx=1, cz=0, ev='enter'), dict(t_ms=9000, cx=1, cz=0, ev='ready'),      # 缓存区里（预生成）：不算
          dict(t_ms=5000, cx=8, cz=0, ev='enter'), dict(t_ms=6500, cx=8, cz=0, ev='leave'),      # 没就绪就离开：1.5 秒，截尾
          dict(t_ms=9000, cx=13, cz=0, ev='enter')]                                              # 到结束（10 秒）也没就绪：1 秒，截尾
    got = blank_times(ev, rows, run=run, view=2, cache_radius_blocks=24)
    assert sorted((round(d, 3), c) for d, c in got) == [(0.0, False), (1.0, True), (1.5, True), (2.0, False), (4.0, False)]
    s = blank_summary(got)
    assert s['blank_median_s'] == 1.5 and s['blank_n'] == 5 and s['blank_censored'] == 2
    assert s['blank_p90_s'] == pytest.approx(4.0)
    assert s['blank_ready_share'] == pytest.approx(3 / 5)
    assert blank_summary([]) == dict(blank_median_s=None, blank_p90_s=None, blank_n=0, blank_censored=0, blank_ready_share=None)
