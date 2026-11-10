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


import threading  # noqa: E402

LOCK = threading.Lock()


class RouteFly(FakeFly):
    def __call__(self, server, run, player, start, speed, seconds, out_dir, **kw):
        self.runner.status['import'] = dict(totalMs=self.runner.status.get('import', {}).get('totalMs', 0) + 250, count=5)
        for w in self.runner.status['workers']:
            w['done'] = w.get('done', 0) + 12
        self.runner.server_log += ["[12:00:01] [Server thread/WARN]: Can't keep up! Is the server overloaded?",
                                   "[12:00:02] [Server thread/WARN]: abl moved too quickly! 3,0,0"]
        with LOCK:   # 两个玩家是两个线程同时飞：记调用要原子
            out = super().__call__(server, run, player, start, speed, seconds, out_dir, **kw)
            self.calls[-1].update(route=kw.get('route'), player=player)
        return out


def real_rig(tmp_path):
    runner = FakeRunner()
    runner.netem = True
    runner.server_log = []
    runner.server_log_since = lambda t: list(runner.server_log)
    samples = iter([0, 1])

    def worker_cpu(services):   # 第一次在飞之前、第二次在飞之后：120 秒用了 180 个 CPU 秒 = 1.5 核
        k = next(samples, 1)
        return {s: (int(k * 120e9), int(k * 180e6)) for s in services}
    runner.worker_cpu = worker_cpu
    poller = Poller(runner, runs_dir=tmp_path / 'runs', clock=Clock())
    fly = RouteFly(runner)
    fly.cpus = 4   # 这些单元的服务器是 4 核（用户的 N100）
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


def test_profile_env_can_join_slot_core_sets():
    # 1 个强 donor 要 8 个核：拼这个槽位 .env.local 里 worker 和 worker2 的核（k2 是 24-27 + 28-31，k4 是 68-71 + 72-75）
    from analysis.flight_driver import resolve_env
    local = {'WORKER_CPUSET': '24-27', 'WORKER2_CPUSET': '28-31'}
    got = resolve_env({'WORKER_CPUS': '8', 'WORKER_CPUSET': '@WORKER_CPUSET+WORKER2_CPUSET'}, local.get)
    assert got == {'WORKER_CPUS': '8', 'WORKER_CPUSET': '24-27,28-31'}
    with pytest.raises(ValueError):
        resolve_env({'WORKER_CPUSET': '@NOPE'}, local.get)


def test_driver_keeps_spike_diagnostics(tmp_path):
    # 调度 11-09：查 MSPT 尖峰是导入还是 GC。驱动存下飞的时候带时间戳的服务器日志（含 GC 日志）、/donor imports 的全部条目、起飞时刻
    import json
    runner, ctl, flyer, fly, _ = real_rig(tmp_path)
    runner.slot_cpuset = lambda: '16-19'
    runner.server_log_ts = lambda t: ['2026-11-09T13:30:00.1Z [GC] Pause Young 12ms', '2026-11-09T13:30:01.2Z 导入任务 3']
    items = [dict(tile=[i, 0], imp=dict(parse=1.0, check=2.0, write=3.0)) for i in range(5)]
    orig = runner.rcon

    def rcon(cmd):
        if cmd.startswith('donor imports'):
            a = int(cmd.split()[2]) if len(cmd.split()) > 2 else 0
            return json.dumps(dict(total=len(items), items=items[a:a + 2]))   # 一页 2 条，要翻页
        return orig(cmd)
    runner.rcon = rcon
    envs = []
    orig_script = runner.script
    runner.script = lambda name, *a, env=None, **kw: (envs.append(env), orig_script(name, *a, env=env, **kw))[1]
    profiles = {'1x8': {'donors': 1, 'env': {'WORKER_CPUS': '8'}}}
    drive = make_flight_driver(runner, ctl, flyer, events=lambda a, b: [], donor_profiles=profiles,
                               server_env={'MCD_JVM_EXTRA': '-Xlog:gc:stdout:time'})
    cell = rcell('firework')
    cell['params']['lookahead'] = 8
    fly.lookahead = '8'
    out = drive(cell, tmp_path / 'c9')
    assert envs[0]['MCD_JVM_EXTRA'] == '-Xlog:gc:stdout:time -Dmcdonate.demandLookahead=8'   # 提前量不盖掉 server_env 里的
    assert (tmp_path / 'c9' / 'server-log.txt').read_text().count('\n') == 2
    got = [json.loads(l) for l in (tmp_path / 'c9' / 'imports.jsonl').read_text().splitlines()]
    assert [g['tile'] for g in got] == [[i, 0] for i in range(5)]
    assert isinstance(out['fly_start_unix'], float)


def test_driver_records_start_tick(tmp_path):
    # 调度 11-09：autosave 每 6000 tick 一次（sync-chunk-writes=true 时同步写在主线程），记下起飞时服务器的 tick 数好对尖峰
    runner, ctl, flyer, fly, drive = real_rig(tmp_path)
    runner.slot_cpuset = lambda: '16-19'
    runner.tick = 5990
    out = drive(rcell('firework'), tmp_path / 'c10')
    assert out['fly_start_tick'] == 5990


def test_driver_keeps_worker_logs_even_when_the_round_fails(tmp_path):
    # 调度 11-09：worker 崩了（PalettedContainer 多线程访问）时容器随下一轮重建，日志没了。每轮结束（包括判无效、飞失败）先把
    # worker 容器的日志存进归档
    runner, ctl, flyer, fly, drive = real_rig(tmp_path)
    runner.slot_cpuset = lambda: '16-19'
    asked = []
    runner.service_log = lambda svc, since: (asked.append(svc), [f'{svc} line 1', f'{svc} line 2'])[1]
    out = drive(rcell('firework'), tmp_path / 'ok')
    assert (tmp_path / 'ok' / 'worker-worker.log').read_text().splitlines() == ['worker line 1', 'worker line 2']
    assert asked == ['worker'] and out

    def boom(*a, **kw):
        raise RuntimeError('飞的时候断了')
    flyer.fly_sync = boom
    with pytest.raises(RuntimeError, match='飞的时候断了'):
        drive(rcell('firework'), tmp_path / 'bad')
    assert (tmp_path / 'bad' / 'worker-worker.log').exists()


def test_driver_refuses_stale_worker_mods(tmp_path):
    # 11-09 事故：跑批只跑了 collectMods，donor 容器挂的 build/mods-worker 一直是早上的旧 jar，一整上午的代码对比作废。
    # donor 轮开始前比一下 build/mods-worker 里的 jar 和刚构建的 worker jar，不一样就拒绝；结果里记下 jar 的指纹
    runner, ctl, flyer, fly, drive = real_rig(tmp_path)
    runner.slot_cpuset = lambda: '16-19'
    runner.worker_jar = lambda: dict(mounted='aaaa', built='bbbb')
    with pytest.raises(RuntimeError, match='collectWorkerMods'):
        drive(rcell('firework'), tmp_path / 'stale')
    runner.worker_jar = lambda: dict(mounted='cccc', built='cccc')
    out = drive(rcell('firework'), tmp_path / 'ok')
    assert out['worker_jar'] == 'cccc'
    out = drive(rcell('firework', donors=0, profile=None, features='v0'), tmp_path / 'vanilla')   # 原版不碰 worker
    assert 'worker_jar' not in out


def test_run_name_differs_between_attempts(tmp_path):
    # 11-09：中途打断的一轮在服务器的 bench-runs 里留下同名目录，同一格重试时 bench fly start 拒绝（"已存在，换个 run 名"）。
    # 每次尝试的 run 名都不一样
    runner, ctl, flyer, fly, drive = real_rig(tmp_path)
    runner.slot_cpuset = lambda: '16-19'
    names = []
    orig = flyer.fly_sync
    flyer.fly_sync = lambda run, *a, **kw: (names.append(run), orig(run, *a, **kw))[1]
    drive(rcell('firework'), tmp_path / 'a1')
    drive(rcell('firework'), tmp_path / 'a2')
    assert len(names) == 2 and names[0] != names[1] and all(n.startswith('mx-mx') for n in names)


def test_driver_sets_and_checks_scan_budget(tmp_path):
    # 调度 11-09：部署包里唯一的参数改动是扫描预算 -Dmcdonate.demandScanBudgetMicros（旧默认 500，部署 2000），要能按单元设、
    # 接在 server_env 后面（GC 日志不丢），并用 /donor status 的 config 核对服务器真的用上了，没用上判无效
    runner, ctl, flyer, fly, _ = real_rig(tmp_path)
    envs = []
    orig_script = runner.script
    runner.script = lambda name, *a, env=None, **kw: (envs.append(env), orig_script(name, *a, env=env, **kw))[1]
    profiles = {'1x8': {'donors': 1, 'env': {'WORKER_CPUS': '8'}}}
    drive = make_flight_driver(runner, ctl, flyer, events=lambda a, b: [], donor_profiles=profiles,
                               server_env={'MCD_JVM_EXTRA': '-Xlog:gc:stdout:time'})
    cell = rcell('firework')
    cell['params']['scan_budget_us'] = 2000
    runner.status['config'] = {'demandScanBudgetMicros': 2000}
    out = drive(cell, tmp_path / 'c11')
    assert envs[0]['MCD_JVM_EXTRA'] == '-Xlog:gc:stdout:time -Dmcdonate.demandScanBudgetMicros=2000'
    assert out['scan_budget_us_effective'] == 2000
    runner.status['config'] = {'demandScanBudgetMicros': 500}
    with pytest.raises(RuntimeError, match='扫描预算没生效'):
        drive(cell, tmp_path / 'c12')


def test_matrix_accepts_scan_budget_and_drops_it_for_vanilla():
    from analysis import run_matrix as rm
    base = dict(donors=[0, 1], speed=['elytra'], server_cpus=[2], latency_ms=[0], features=['deadline'], bots=[1], duration_s=120,
                scenario=['firework'], scan_budget_us=[500, 2000])
    cfg = dict(name='t', seed=1, repeats=1, experiments=dict(flight=[base]))
    rm.validate(cfg)
    cells = rm.expand(cfg)
    got = sorted((c['params']['donors'], c['params'].get('scan_budget_us')) for c in cells)
    assert got == [(0, None), (1, 500), (1, 2000)]   # 原版没有扫描（没 donor 不扫），只留一个
    bad = dict(cfg, experiments=dict(flight=[dict(base, scan_budget_us=[0])]))
    with pytest.raises(ValueError):
        rm.validate(bad)


def test_firework_config_name_shows_scan_budget():
    from analysis import firework as f
    r = dict(donors=1, donor_profile='1x8', scan_budget_us=2000)
    assert f._cfg(r).endswith('扫描预算 2000 µs')
    assert '扫描预算' not in f._cfg(dict(donors=0))


def test_driver_sets_corridor_and_concurrency_and_checks_them(tmp_path):
    # 调度 11-09：直线参数扫描 lookahead × corridorChunks、maxConcurrency，和扫描预算一样接在 JVM 参数后面并核对
    runner, ctl, flyer, fly, _ = real_rig(tmp_path)
    envs = []
    orig_script = runner.script
    runner.script = lambda name, *a, env=None, **kw: (envs.append(env), orig_script(name, *a, env=env, **kw))[1]
    drive = make_flight_driver(runner, ctl, flyer, events=lambda a, b: [], donor_profiles={'1x8': {'donors': 1, 'env': {}}},
                               server_env={'MCD_JVM_EXTRA': '-Xlog:gc:stdout:time'})
    cell = rcell('firework')
    cell['params'].update(scan_budget_us=2000, corridor_chunks=10, max_concurrency=6)
    runner.status['config'] = {'demandScanBudgetMicros': 2000, 'corridorChunks': 10, 'maxConcurrency': 6}
    out = drive(cell, tmp_path / 'c13')
    extra = envs[0]['MCD_JVM_EXTRA']
    assert extra.startswith('-Xlog:gc:stdout:time ')
    assert {'-Dmcdonate.demandScanBudgetMicros=2000', '-Dmcdonate.corridorChunks=10', '-Dmcdonate.maxConcurrency=6'} <= set(extra.split())
    assert out['corridor_chunks_effective'] == 10 and out['max_concurrency_effective'] == 6
    runner.status['config'] = {'demandScanBudgetMicros': 2000, 'corridorChunks': 6, 'maxConcurrency': 6}
    with pytest.raises(RuntimeError, match='corridorChunks没生效'):
        drive(cell, tmp_path / 'c14')


def test_driver_forward_first_both_arms_are_checked(tmp_path):
    # 调度 11-09：fish-fwd 的 -Dmcdonate.demandForwardFirst（0/1），/donor status 的 config 里是 true/false。关的那一臂（0）也要
    # 明确传、明确核对，不能因为是 0 就当没设
    runner, ctl, flyer, fly, _ = real_rig(tmp_path)
    envs = []
    orig_script = runner.script
    runner.script = lambda name, *a, env=None, **kw: (name == 'new_world.sh' and envs.append(env), orig_script(name, *a, env=env, **kw))[1]
    drive = make_flight_driver(runner, ctl, flyer, events=lambda a, b: [], donor_profiles={'1x8': {'donors': 1, 'env': {}}})
    for i, (v, shown) in enumerate(((1, True), (0, False))):
        cell = rcell('firework')
        cell['params']['forward_first'] = v
        runner.status['config'] = {'demandForwardFirst': shown}
        out = drive(cell, tmp_path / f'f{i}')
        assert f'-Dmcdonate.demandForwardFirst={v}' in envs[-1]['MCD_JVM_EXTRA'].split()
        assert out['forward_first_effective'] is shown
    runner.status['config'] = {'demandForwardFirst': True}
    with pytest.raises(RuntimeError, match='demandForwardFirst没生效'):
        drive(cell, tmp_path / 'f9')


def test_driver_ticket_throttle_applies_to_vanilla_too_and_checks_actual(tmp_path):
    # 调度 11-09：fish-throttle 的 -Dmcdonate.playerTicketThrottle=N 改的是原版玩家票节流，原版（0 个 donor）也要能设；
    # 用 /donor status 的 playerTicketThrottle.actual（各维度实际的 maxTasks）核对，主世界不对就判无效
    runner, ctl, flyer, fly, _ = real_rig(tmp_path)
    envs = []
    orig_script = runner.script
    runner.script = lambda name, *a, env=None, **kw: (name == 'new_world.sh' and envs.append(env), orig_script(name, *a, env=env, **kw))[1]
    drive = make_flight_driver(runner, ctl, flyer, events=lambda a, b: [], donor_profiles={'1x8': {'donors': 1, 'env': {}}})
    for i, donors in enumerate((0, 1)):
        cell = rcell('firework', donors=donors)
        cell['params']['ticket_throttle'] = 16
        runner.status['playerTicketThrottle'] = {'configured': 16, 'actual': {'minecraft:overworld': 16, 'minecraft:the_nether': 16}}
        out = drive(cell, tmp_path / f't{i}')
        assert '-Dmcdonate.playerTicketThrottle=16' in envs[-1]['MCD_JVM_EXTRA'].split()
        assert out['ticket_throttle_effective'] == 16
    runner.status['playerTicketThrottle'] = {'configured': 16, 'actual': {'minecraft:overworld': 4}}
    with pytest.raises(RuntimeError, match='playerTicketThrottle没生效'):
        drive(cell, tmp_path / 't9')


def test_matrix_keeps_ticket_throttle_for_vanilla():
    from analysis import run_matrix as rm
    base = dict(donors=[0, 1], speed=['elytra'], server_cpus=[2], latency_ms=[0], features=['deadline'], bots=[1], duration_s=120,
                scenario=['firework'], ticket_throttle=[4, 16], scan_budget_us=[2000])
    cfg = dict(name='t', seed=1, repeats=1, experiments=dict(flight=[base]))
    rm.validate(cfg)
    got = sorted((c['params']['donors'], c['params'].get('ticket_throttle'), c['params'].get('scan_budget_us')) for c in rm.expand(cfg))
    assert got == [(0, 4, None), (0, 16, None), (1, 4, 2000), (1, 16, 2000)]


def test_firework_vanilla_label_shows_ticket_throttle():
    from analysis import firework as f
    assert '票节流 16' in f._cfg(dict(donors=0, ticket_throttle=16))
    assert '票节流 4' in f._cfg(dict(donors=1, donor_profile='1x8', ticket_throttle=4))


def test_driver_keeps_donor_status_after_flight(tmp_path):
    # 调度 11-09：fish-rate 的 status 每个 worker 有 lanes / parallelism / ratePerSec / laneLatencyMs，飞完那一刻的整份 /donor status
    # 存进归档（donor-status.json），结果里摘出每个 worker 的这几项
    runner, ctl, flyer, fly, drive = real_rig(tmp_path)
    runner.status['workers'] = [dict(id=7, name='w', done=3, lanes=4, parallelism=3.5, ratePerSec=4.8, laneLatencyMs=700)]
    out = drive(rcell('firework'), tmp_path / 'c15')
    import json
    saved = json.loads((tmp_path / "c15" / "donor-status.json").read_text())
    assert saved['workers'][0]['lanes'] == 4
    assert dict(id=7, lanes=4, parallelism=3.5, ratePerSec=4.8, laneLatencyMs=700) in out['worker_status']


def test_driver_stands_before_flying_and_snapshots_status(tmp_path):
    # 调度 11-09 加速模式：起飞前在原地站 stand_s 秒（路线前面加一段速度 0），站完那一刻记一份 /donor status，
    # 结果里有站着时接受了多少、后台派了 / 接受了多少
    import json
    runner, ctl, flyer, fly, drive = real_rig(tmp_path)
    runner.status['workers'] = [dict(id=7, name='w', done=0, boost=True, backgroundLeased=5, backgroundAccepted=4)]
    runner.status['importQueue'] = dict(foreground=0, background=2, backgroundImported=3)   # f9d27c0：后台结果单独排队
    cell = rcell('firework')
    cell['params']['stand_s'] = 1
    out = drive(cell, tmp_path / 'c16')
    route = fly.calls[-1]['route']
    first = route.split(';')[0].split(',')
    assert first[1] == '0' and first[2] == '1'                     # 先站 1 秒
    assert route.split(';')[1] == scenarios.routes('firework', seed=424242, seconds=120, cache_radius_blocks=0)[0].split(';')[0]
    assert fly.calls[-1]['seconds'] == 121
    snap = json.loads((tmp_path / 'c16' / 'donor-status-stand.json').read_text())
    assert snap['workers'][0]['backgroundLeased'] == 5
    assert out['stand_bg_leased'] == 5 and out['stand_bg_accepted'] == 4
    assert out['stand_bg_imported'] == 3 and out['bg_imported'] == 3 and out['bg_pending'] == 2
    assert out['stand_accepted'] == snap['accepted']   # 这一轮开始前是 0（假飞行一下就飞完，所以快照里已经有飞行时的）


def test_driver_sets_boost_on_workers_and_checks_it(tmp_path):
    # 测试环境用 worker 的 /donor boost on|off 代替客户端开关；coordinator status 里每个 worker 的 boost 要对上，否则判无效
    runner, ctl, flyer, fly, drive = real_rig(tmp_path)
    sent = []

    def rcon_worker(service, cmd):
        sent.append((service, cmd))
        for w in runner.status['workers']:
            w['boost'] = cmd.endswith(' on')
        return 'boost on' if cmd.endswith(' on') else 'boost off'
    runner.rcon_worker = rcon_worker
    cell = rcell('firework')
    cell['params']['boost'] = 1
    out = drive(cell, tmp_path / 'c17')
    assert sent and all(c == 'donor boost on' for _, c in sent)
    assert out['boost_effective'] is True
    runner.rcon_worker = lambda s, c: 'boost on'   # 发了但 coordinator 那边没变
    for w in runner.status['workers']:
        w['boost'] = False
    with pytest.raises(RuntimeError, match='boost 没生效'):
        drive(cell, tmp_path / 'c18')


def test_driver_checks_worker_gen_pool(tmp_path):
    # 调度 11-10（fish-pool / envfix）：worker 报 cpus 和 genPool（ActiveProcessorCount = 线程数 + 1，所以 genPool 应 = cpus − 1）。
    # 驱动先做自洽检查（不解析任何 .env）；profile 里显式写了 WORKER_CPUS / WORKER_GEN_THREADS 时再核对具体数值；
    # 只有 .env.local 的值不拿来核对（引号、空值等 compose 语法驱动不重新实现）。老 worker 报 null 不核对
    runner, ctl, flyer, fly, drive = real_rig(tmp_path)   # 1x8：profile 里 WORKER_CPUS=8
    runner.status['workers'] = [dict(id=7, name='w', done=0, genPool=8, cpus=9)]
    out = drive(rcell('firework'), tmp_path / 'g1')
    assert 8 in out['gen_pool']   # 假 runner 起 donor 时自己也加一个 worker（没有 genPool，不核对）
    runner.status['workers'] = [dict(id=7, name='w', done=0, genPool=7, cpus=9)]          # 不自洽
    with pytest.raises(RuntimeError, match='生成池不对'):
        drive(rcell('firework'), tmp_path / 'g2')
    runner.status['workers'] = [dict(id=7, name='w', done=0, genPool=6, cpus=7)]          # 自洽，但 profile 说 8
    with pytest.raises(RuntimeError, match='生成池不对'):
        drive(rcell('firework'), tmp_path / 'g3')
    runner.status['workers'] = [dict(id=7, name='w', done=0, genPool=None, cpus=None)]
    assert set(drive(rcell('firework'), tmp_path / 'g4')['gen_pool']) == {None}


def test_driver_gen_pool_ignores_local_env(tmp_path):
    runner, ctl, flyer, fly, _ = real_rig(tmp_path)
    runner.local_env = lambda k: '"3"' if k == 'WORKER_CPUS' else None   # .env.local 里带引号：驱动不拿它核对
    drive = make_flight_driver(runner, ctl, flyer, events=lambda a, b: [], donor_profiles={'plain': {'donors': 1, 'env': {}}})
    runner.status['workers'] = [dict(id=7, name='w', done=0, genPool=4, cpus=5)]
    out = drive(rcell('firework', profile='plain'), tmp_path / 'g5')
    assert 4 in out['gen_pool']
    runner.status['workers'] = [dict(id=7, name='w', done=0, genPool=4, cpus=9)]   # profile 没写核数，但自己不自洽
    with pytest.raises(RuntimeError, match='生成池不对'):
        drive(rcell('firework', profile='plain'), tmp_path / 'g6')


def test_expected_gen_pool_rules():
    from analysis.flight_driver import expected_gen_pools
    env = {'WORKER_CPUS': '8'}
    assert expected_gen_pools(['worker'], env.get) == [8]
    assert expected_gen_pools(['worker', 'worker2'], {'WORKER_CPUS': '4'}.get) == [4, 4]      # worker2 继承
    assert expected_gen_pools(['worker'], {'WORKER_CPUS': '2.5'}.get) == [3]                  # 向上取整
    assert expected_gen_pools(['worker'], {'WORKER_CPUS': '8', 'WORKER_GEN_THREADS': '6'}.get) == [6]
    assert expected_gen_pools(['worker3'], {'WORKER_CPUS': '8', 'WORKER3_CPUS': '1'}.get) == [1]
    assert expected_gen_pools(['worker'], {}.get) == [None]                                   # 不知道就不核对
