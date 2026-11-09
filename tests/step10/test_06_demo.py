"""第 10 步 7.1：演示面板的后端（数据接口、控制接口、一键 A/B）。不起服务器：rcon、compose、脚本、cgroup 都是假的。"""
import json
import threading
import urllib.error
import urllib.request

import pytest

from bench.demo import ab as demo_ab
from bench.demo.control import Controller, Flyer
from bench.demo.poll import Poller
from bench.map.server import make_server


class FakeRunner:
    """记下每条命令；按命令改自己的状态，像真的服务器和 compose 一样回话。"""

    def __init__(self):
        self.calls = []
        self.tick = 0
        self.mspt = (5.0, 9.0)
        self.usage_usec = 0
        self.now_ns = 0
        self.status = dict(workers=[], accepted=0, discarded=dict(late=0, other_world=0, server=0), features=[], demand=False)
        self.broken = set()
        self.env = {'worker': {'MAX_TICK_TIME': '-1'}, 'worker2': {'MAX_TICK_TIME': '-1'}}
        self.netem = False
        self.next_worker_id = 1
        self.compose_services = ['minecraft', 'worker', 'worker2', 'worker3']

    def services(self):
        return list(self.compose_services)

    def slot_cpuset(self):
        return '16-19'   # 这个槽位 .env.local 里服务器的核

    def rcon(self, cmd):
        self.calls.append(('rcon', cmd))
        if cmd == 'bench tick':
            if 'tick' in self.broken:
                raise RuntimeError('rcon 连不上')
            return json.dumps(dict(tick=self.tick, recent_ticks=20, mspt_recent_mean=self.mspt[0], mspt_recent_max=self.mspt[1],
                                   mspt_100=6.0))
        if cmd == 'donor status':
            return 'Unknown or incomplete command' if 'donor' in self.broken else json.dumps(self.status)
        if cmd.startswith('bench record start '):
            return f"bench record started {cmd.split()[-1]}"
        if cmd.startswith('bench record stop'):
            return 'bench record stopped ' + cmd.split()[-1]
        if cmd.startswith('donor demand '):
            self.status['demand'] = cmd.endswith('on')
        elif cmd.startswith('donor feature '):
            _, _, name, state = cmd.split()
            f = set(self.status['features'])
            (f.add if state == 'on' else f.discard)(name)
            self.status['features'] = sorted(f)
        return 'ok'

    def compose(self, *args, timeout=120):
        self.calls.append(('compose', *args))
        if args[0] == 'up':
            for s in args:
                if s.startswith('worker') and s not in {w['name'] for w in self.status['workers']}:
                    self.status['workers'].append(dict(id=self.next_worker_id, name=s))
                    self.next_worker_id += 1
        elif args[0] == 'stop':
            self.status['workers'] = []
        return ''

    def script(self, name, *args, env=None, timeout=600):
        self.calls.append(('script', name, *args, *(sorted(env.items()) if env else ())))
        return ''

    def cpu(self):
        return self.now_ns, self.usage_usec

    def container_env(self, service):
        return self.env.get(service) if any(w['name'] == service for w in self.status['workers']) else None

    def netem_supported(self):
        return self.netem


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


@pytest.fixture
def rig(tmp_path):
    runner, clock = FakeRunner(), Clock()
    poller = Poller(runner, runs_dir=tmp_path / 'runs', clock=clock)
    return runner, clock, poller


def advance(runner, clock, seconds, ticks, cpu_usec):
    clock.t += seconds
    runner.now_ns += int(seconds * 1e9)
    runner.tick += ticks
    runner.usage_usec += cpu_usec


# ---- 数据接口 ----


def test_poll_computes_tps_cpu_and_donor_deltas(rig):
    runner, clock, poller = rig
    first = poller.sample()
    assert first['tps'] is None and first['cpu_cores'] is None  # 第一次没有差
    assert first['mspt_mean'] == 5.0 and first['mspt_max'] == 9.0 and first['mspt_100'] == 6.0
    runner.status['workers'] = [dict(id=1, name='worker'), dict(id=2, name='worker2')]
    runner.status['accepted'] = 7
    runner.status['discarded'] = dict(late=2, other_world=0, server=3)
    advance(runner, clock, 2.0, 40, 3_000_000)
    row = poller.sample()
    assert row['tps'] == pytest.approx(20.0) and row['cpu_cores'] == pytest.approx(1.5)
    assert row['donors_online'] == 2 and row['imports'] == 7
    assert row['discards'] == dict(late=2, other_world=0, server=3)
    assert row['t_s'] == pytest.approx(2.0)


def test_series_is_incremental_and_bounded(tmp_path):
    runner, clock = FakeRunner(), Clock()
    poller = Poller(runner, runs_dir=tmp_path, clock=clock, capacity=3)
    for _ in range(5):
        poller.sample()
        advance(runner, clock, 1, 20, 1000)
    got = poller.series(0)
    assert got['next'] == 5 and [r['i'] for r in got['rows']] == [2, 3, 4]  # 只留最近 3 条
    assert poller.series(5) == dict(next=5, rows=[])
    assert [r['i'] for r in poller.series(4)['rows']] == [4]


def test_one_broken_source_leaves_others(rig):
    runner, clock, poller = rig
    runner.broken = {'donor'}
    row = poller.sample()
    assert row['donors_online'] is None and 'donor' in row['errors']
    assert row['mspt_mean'] == 5.0
    runner.broken = {'tick'}
    row = poller.sample()
    assert row['mspt_mean'] is None and 'tick' in row['errors'] and row['errors']['tick']


def test_watchdog_warning_when_worker_lacks_max_tick_time(rig):
    runner, clock, poller = rig
    runner.status['workers'] = [dict(id=1, name='worker'), dict(id=2, name='worker2')]
    assert poller.sample()['warnings'] == []
    runner.env['worker2'] = {}
    row = poller.sample()
    assert any('看门狗' in w and 'worker2' in w for w in row['warnings'])


def test_flight_curves_from_run_files(rig):
    runner, clock, poller = rig
    run = poller.runs_dir / 'r1'
    run.mkdir(parents=True)
    (run / 'seconds.jsonl').write_text(''.join(json.dumps(r) + '\n' for r in [
        dict(t_s=1, x=105.0, z=0.0, void_rate=0.4, ticks=20, stalled_ticks=10),
        dict(t_s=2, x=120.0, z=0.0, void_rate=0.1, ticks=20, stalled_ticks=0)]))
    # 第 1 条 donor 写入时玩家在 105/16≈6.6 chunk，写在 10，前方；第 2 条玩家在 7.5，写在 5，身后
    (run / 'provenance.jsonl').write_text(''.join(json.dumps(e) + '\n' for e in [
        dict(t_ms=500, cx=10, cz=0, kind='donor', donor=1), dict(t_ms=900, cx=3, cz=0, kind='vanilla', donor=-1),
        dict(t_ms=1500, cx=5, cz=0, kind='donor', donor=2)]))
    poller.flight = dict(run='r1', start_x=100.0, speed=10.0)
    f = poller.sample()['flight']
    assert f['run'] == 'r1' and f['t_s'] == 2 and f['void_rate'] == 0.1
    assert f['stall_fraction'] == 0 and f['stall_fraction_total'] == pytest.approx(0.25)
    assert f['distance_ratio'] == pytest.approx(1.0)       # (120 - 100) / (10 × 2)
    assert f['donor_chunks'] == 2 and f['ahead_total'] == pytest.approx(0.5)
    poller.flight = None
    assert poller.sample()['flight'] is None


# ---- 控制接口 ----


class FakeFlyer:
    def __init__(self):
        self.started, self.stopped, self.run = [], 0, None

    def start(self, speed_name, seconds):
        self.started.append((speed_name, seconds))
        self.run = 'demo-fly-1'
        return self.run

    def stop(self):
        self.stopped += 1
        return self.run

    def state(self):
        return dict(run=self.run)


@pytest.fixture
def web(rig, tmp_path):
    runner, clock, poller = rig
    flyer = FakeFlyer()
    ctl = Controller(runner, poller, flyer, wait_timeout=1, wait_interval=0)
    server = make_server(tmp_path / 'runs', port=0, demo=dict(poller=poller, controller=ctl, ab=None))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield dict(base=f'http://127.0.0.1:{server.server_address[1]}', runner=runner, ctl=ctl, flyer=flyer, poller=poller)
    server.shutdown()
    server.server_close()


def post(base, path, body, headers=None):
    h = {'Content-Type': 'application/json', 'X-Demo': '1', **(headers or {})}
    req = urllib.request.Request(base + path, data=json.dumps(body).encode(), headers=h, method='POST')
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b'{}')


def rcons(runner):
    return [c[1] for c in runner.calls if c[0] == 'rcon' and c[1] not in ('bench tick', 'donor status')]


@pytest.mark.parametrize('headers, why', [
    ({'X-Demo': ''}, '没有 X-Demo 头'),
    ({'Content-Type': 'application/x-www-form-urlencoded'}, '不是 JSON（跨站表单）'),
    ({'Host': 'evil.example:8765'}, 'Host 不对（DNS 重绑定）'),
])
def test_control_rejects_unsafe_requests(web, headers, why):
    code, _ = post(web['base'], '/api/control', dict(action='feature', name='deadline', on=True), headers)
    assert code == 403, why
    assert rcons(web['runner']) == []


def test_control_get_not_allowed(web):
    with pytest.raises(urllib.error.HTTPError) as e:
        urllib.request.urlopen(web['base'] + '/api/control?action=donor_on', timeout=5)
    assert e.value.code in (404, 405)


@pytest.mark.parametrize('body', [dict(action='rm -rf'), dict(action='feature', name='nope', on=True),
                                  dict(action='feature', name='deadline', on='yes'), dict(action='fly_start', speed='rocket', seconds=60),
                                  dict(action='fly_start', speed='elytra', seconds=99999)])
def test_control_rejects_unknown_actions_and_values(web, body):
    code, _ = post(web['base'], '/api/control', body)
    assert code == 400
    assert rcons(web['runner']) == []


def test_feature_switch(web):
    code, r = post(web['base'], '/api/control', dict(action='feature', name='deadline', on=True))
    assert code == 200 and r['ok']
    post(web['base'], '/api/control', dict(action='feature', name='hedge', on=False))
    assert rcons(web['runner']) == ['donor feature deadline on', 'donor feature hedge off']


def test_donor_on_and_off(web):
    runner = web['runner']
    code, _ = post(web['base'], '/api/control', dict(action='donor_on'))
    assert code == 200
    assert ('compose', 'up', '-d', '--wait', '--wait-timeout', '600', 'worker', 'worker2') in runner.calls
    assert rcons(runner) == ['donor demand on']  # 两个 worker 连上以后才开找需求
    assert len(runner.status['workers']) == 2
    code, _ = post(web['base'], '/api/control', dict(action='donor_off'))
    assert code == 200
    assert rcons(runner)[-1] == 'donor demand off'
    assert ('compose', 'stop', 'worker', 'worker2', 'worker3') in runner.calls
    assert runner.status['workers'] == []


def test_one_action_at_a_time(web):
    with web['ctl'].busy('别的动作'):
        code, r = post(web['base'], '/api/control', dict(action='feature', name='deadline', on=True))
    assert code == 409 and '别的动作' in r['error']
    assert rcons(web['runner']) == []


def test_fly_start_and_stop(web):
    code, r = post(web['base'], '/api/control', dict(action='fly_start', speed='elytra', seconds=60))
    assert code == 200 and r['run'] == 'demo-fly-1' and web['flyer'].started == [('elytra', 60)]
    code, _ = post(web['base'], '/api/control', dict(action='fly_stop'))
    assert code == 200 and web['flyer'].stopped == 1


def test_netem_greyed_out_until_supported(web):
    code, r = post(web['base'], '/api/control', dict(action='netem', on=True))
    assert code == 400 and 'netem.sh' in r['error']
    web['runner'].netem = True
    code, _ = post(web['base'], '/api/control', dict(action='netem', on=True))
    assert code == 200
    scripts = [c for c in web['runner'].calls if c[0] == 'script']
    # 35ms 延迟、1% 丢包、20mbit 限速，两个 worker 各一次（fish 的 netem.sh 用 NETEM_SERVICE 选服务）
    assert scripts == [('script', 'netem.sh', 'on', '35ms', '1%', '20mbit', ('NETEM_SERVICE', s)) for s in ('worker', 'worker2')]
    assert web['poller'].netem is True and any('模拟网络' in w for w in web['poller'].sample()['warnings'])


def test_live_and_series_endpoints(web):
    web['poller'].sample()
    with urllib.request.urlopen(web['base'] + '/api/series?since=0', timeout=5) as r:
        s = json.loads(r.read())
    assert s['next'] == 1 and s['rows'][0]['mspt_mean'] == 5.0
    with urllib.request.urlopen(web['base'] + '/api/live', timeout=5) as r:
        live = json.loads(r.read())
    assert live['row']['i'] == 0 and 'busy' in live and live['flyer'] == dict(run=None)


def test_demo_page_smoke(web):
    with urllib.request.urlopen(web['base'] + '/demo', timeout=5) as r:
        page = r.read().decode()
    for chart in ('mspt', 'tps', 'cpu', 'void', 'stall', 'distance', 'donors', 'imports', 'discards', 'ahead'):
        assert f'id="chart-{chart}"' in page, chart
    for action in ('donor_on', 'donor_off', 'fly_start', 'fly_stop', 'netem'):
        assert f'data-action="{action}"' in page, action
    assert 'confirm(' in page and "'X-Demo'" in page and '演示，粗测' in page and '/api/ab' in page


# ---- 一键 A/B ----


class FakeFly:
    """代替 bench.layer2.fly：记下飞的时候服务器是什么状态，写出和真的一样的 summary.json 和 raw/done.json。"""

    def __init__(self, runner, donor_events=50, sync_a=0, restart_in_b=False, resumed_b=0):
        self.runner, self.donor_events, self.sync_a, self.restart_in_b = runner, donor_events, sync_a, restart_in_b
        self.resumed_b = resumed_b
        self.cpus = 2
        self.calls = []

    def __call__(self, server, run, player, start, speed, seconds, out_dir, **kw):
        st = self.runner.status
        b = bool(st['workers'])
        self.calls.append(dict(run=run, start=start, speed=speed, seconds=seconds, workers=len(st['workers']),
                               demand=st['demand'], features=list(st['features'])))
        if b and self.restart_in_b:
            st['workers'] = [dict(id=w['id'] + 100, name=w['name']) for w in st['workers']]  # 被看门狗杀掉又连上
        (out_dir / 'raw').mkdir(parents=True)
        (out_dir / 'raw' / 'done.json').write_text(json.dumps(dict(provenance=dict(vanilla=900, donor=self.donor_events if b else 0,
                                                                                   rejected=0, loaded=0, resumed=0))))
        (out_dir / 'raw' / 'provenance.jsonl').write_text(json.dumps(dict(t_ms=10, cx=0, cz=0, kind='vanilla', donor=-1)) + '\n')
        (out_dir / 'raw' / 'seconds.jsonl').write_text(json.dumps(dict(t_s=1, x=0.0, z=0.0)) + '\n')
        (out_dir / 'summary.json').write_text(json.dumps(dict(stall_s=20.0 if b else 40.0, stall_fraction=.17 if b else .33,
                                                              distance_ratio=.83 if b else .67, void_mean=.5, latency_p95_ms='inf',
                                                              mspt_mean=4.0, mspt_max=90.0, cpu_cores_mean=1.2,
                                                              sync_loads=0 if b else self.sync_a,
                                                              full_resumed=self.resumed_b if b else 0,
                                                              full_generated=600 if b else 900, full_loaded=0,
                                                              available_processors=self.cpus)))
        return out_dir


def ab_rig(tmp_path, **fly_kw):
    runner, clock = FakeRunner(), Clock()
    poller = Poller(runner, runs_dir=tmp_path / 'runs', clock=clock)
    fly = FakeFly(runner, **fly_kw)
    flyer = Flyer(runner, poller, tmp_path / 'artifacts', fly=fly)
    ctl = Controller(runner, poller, flyer, wait_timeout=1, wait_interval=0)
    return runner, ctl, flyer, fly


def test_ab_runs_a_without_donors_then_b_with_deadline_on_fresh_worlds(tmp_path):
    runner, ctl, flyer, fly = ab_rig(tmp_path)
    res = demo_ab.run_ab(runner, ctl, flyer, tmp_path / 'artifacts', speed='elytra', seconds=120, events=lambda a, b: [])
    a, b = fly.calls
    assert a['workers'] == 0 and not a['demand'] and a['features'] == []
    assert b['workers'] == 2 and b['demand'] and b['features'] == ['deadline']
    assert a['start'] == b['start'] and a['speed'] == b['speed'] == 30.0 and a['seconds'] == b['seconds'] == 120
    seq = [c for c in runner.calls if c[0] == 'script' or c[:2] == ('rcon', 'donor reset') or c[:2] == ('compose', 'up')]
    kinds = [c[1] if c[0] != 'compose' else 'up' for c in seq]
    assert kinds == ['new_world.sh', 'new_world.sh', 'donor reset', 'up']   # 两轮各一个全新世界，B 开 donor 前 reset
    assert seq[0][3] == seq[1][3] == '424242'                              # 同一个 seed
    # 默认 2 核（调度 11-09）：换世界时带上 MC_CPUS=2，核取这个槽位服务器核的前两个
    assert all(('MC_CPUS', '2') in c and ('MC_CPUSET', '16-17') in c for c in seq[:2]), seq[:2]
    import re
    # lib.sh 的 validate_world_name：只认小写字母、数字、_ -，最长 32（11-09 k2 冒烟时大写 T 被拒）
    assert all(re.fullmatch(r'[a-z0-9_-]{1,32}', c[2]) for c in seq[:2]), seq[:2]
    root = tmp_path / 'artifacts' / res['name']
    saved = json.loads((root / 'ab.json').read_text())
    assert saved['A']['valid'] and saved['B']['valid'] and saved['B']['donor_events'] == 50
    assert saved['note'].startswith('演示，粗测') and '演示，粗测' in (root / 'README.md').read_text()
    assert saved['A']['summary']['stall_fraction'] == .33 and saved['B']['summary']['stall_fraction'] == .17
    assert saved['server_cpus'] == 2 and saved['server_cpuset'] == '16-17' and saved['seconds'] == 120 and saved['speed'] == 'elytra'


def test_ab_marks_b_invalid_without_donor_events(tmp_path):
    runner, ctl, flyer, fly = ab_rig(tmp_path, donor_events=0)
    res = demo_ab.run_ab(runner, ctl, flyer, tmp_path / 'artifacts', events=lambda a, b: [])
    assert not res['B']['valid'] and any('donor' in r for r in res['B']['invalid'])
    assert res['A']['valid']


def test_ab_marks_b_invalid_when_worker_restarted(tmp_path):
    runner, ctl, flyer, fly = ab_rig(tmp_path, restart_in_b=True)
    res = demo_ab.run_ab(runner, ctl, flyer, tmp_path / 'artifacts', events=lambda a, b: [])
    assert not res['B']['valid'] and any('重启' in r for r in res['B']['invalid'])


def test_ab_marks_sync_loads_invalid_and_records_concurrency(tmp_path):
    runner, ctl, flyer, fly = ab_rig(tmp_path, sync_a=3)
    ev = [dict(Type='container', Action='start', Actor=dict(ID='x', Attributes=dict(name='mc-test-f3-minecraft-1')))]
    res = demo_ab.run_ab(runner, ctl, flyer, tmp_path / 'artifacts', events=lambda a, b: ev)
    assert not res['A']['valid'] and any('同步加载' in r for r in res['A']['invalid'])
    assert res['concurrent'] == ['mc-test-f3-minecraft-1 start']


def test_ab_endpoint_runs_in_background_and_reports(tmp_path):
    runner, ctl, flyer, fly = ab_rig(tmp_path)
    job = demo_ab.Job(lambda **kw: demo_ab.run_ab(runner, ctl, flyer, tmp_path / 'artifacts', events=lambda a, b: [], **kw))
    poller = Poller(runner, runs_dir=tmp_path / 'runs', clock=Clock())
    server = make_server(tmp_path / 'runs', port=0, demo=dict(poller=poller, controller=ctl, ab=job))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f'http://127.0.0.1:{server.server_address[1]}'
    try:
        code, r = post(base, '/api/ab', dict(speed='elytra', seconds=60))
        assert code == 202
        job.thread.join(10)
        with urllib.request.urlopen(base + '/api/ab', timeout=5) as resp:
            st = json.loads(resp.read())
        assert st['state'] == 'done' and st['result']['B']['valid']
        # 地图回放：A、B 两轮的归档各一个回放接口
        with urllib.request.urlopen(base + '/api/ab/replay?which=B', timeout=5) as resp:
            rep = json.loads(resp.read())
        assert 'events' in rep and 'counts' in rep
        # 并排曲线：两轮的逐秒行
        with urllib.request.urlopen(base + '/api/ab/seconds?which=A', timeout=5) as resp:
            assert json.loads(resp.read())['rows'] == [dict(t_s=1, x=0.0, z=0.0)]
        code, _ = post(base, '/api/ab', dict(speed='rocket', seconds=60))
        assert code == 400
        code, _ = post(base, '/api/ab', dict(speed='elytra', seconds=60, server_cpus=3))
        assert code == 400
    finally:
        server.shutdown()
        server.server_close()


# ---- 跟踪真人玩家（7.2） ----


def test_track_real_player(web):
    runner = web['runner']
    real = runner.rcon

    def rcon(cmd):
        if cmd == 'bench view':
            runner.calls.append(('rcon', cmd))
            return json.dumps(dict(view=10, players=[dict(name='Alice_1', total=441, ready=441)]))
        return real(cmd)
    runner.rcon = rcon
    with urllib.request.urlopen(web['base'] + '/api/players', timeout=5) as r:
        assert json.loads(r.read())['players'] == ['Alice_1']
    code, r = post(web['base'], '/api/control', dict(action='record_start', player='Alice_1'))
    assert code == 200 and r['run'].startswith('rec-Alice_1-')
    assert f"bench record start Alice_1 {r['run']}" in rcons(runner)
    assert web['poller'].flight == dict(run=r['run'], start_x=None, speed=None)   # 面板曲线跟着这一轮的文件走
    code, _ = post(web['base'], '/api/control', dict(action='record_stop'))
    assert code == 200 and f"bench record stop {r['run']}" in rcons(runner) and web['poller'].flight is None


@pytest.mark.parametrize('name', ['', 'a b', 'x' * 17, 'Alice;stop', '@a'])
def test_track_rejects_bad_player_names(web, name):
    code, _ = post(web['base'], '/api/control', dict(action='record_start', player=name))
    assert code == 400 and not any(c.startswith('bench record') for c in rcons(web['runner']))


def test_page_has_track_entry(web):
    with urllib.request.urlopen(web['base'] + '/demo', timeout=5) as r:
        page = r.read().decode()
    assert 'data-action="record_start"' in page and 'data-action="record_stop"' in page and '/api/players' in page


def test_donor_off_only_stops_services_that_exist(web):
    # main 的 compose 里还没有 worker3（fish 第 7 步才加）：compose stop 不认识的服务直接报错（11-09 k2 冒烟）
    runner = web['runner']
    runner.compose_services = ['minecraft', 'worker', 'worker2']
    code, r = post(web['base'], '/api/control', dict(action='donor_off'))
    assert code == 200, r
    assert ('compose', 'stop', 'worker', 'worker2') in runner.calls


def test_ab_says_when_coordinator_does_not_report_donor_events(tmp_path):
    # 11-09 k2 冒烟：main 里的 coordinator 还不报 DONOR 事件（fish 分支才有），B 有 107 个导入后接着做完的 chunk 却 0 条 donor 事件。
    # 照样判无效（判据不变），但原因要写清楚是没上报、不是 donor 没干活
    runner, ctl, flyer, fly = ab_rig(tmp_path, donor_events=0, resumed_b=107)
    res = demo_ab.run_ab(runner, ctl, flyer, tmp_path / 'artifacts', events=lambda a, b: [])
    assert not res['B']['valid']
    assert any('107' in r and 'coordinator' in r and '没报' in r for r in res['B']['invalid']), res['B']['invalid']


def test_ab_defaults_and_core_count_check(tmp_path):
    # 默认鞘翅、120 秒、2 核、deadline；容器里实际的核数和要的不一样（换世界没生效）就判无效
    runner, ctl, flyer, fly = ab_rig(tmp_path)
    res = demo_ab.run_ab(runner, ctl, flyer, tmp_path / 'artifacts', events=lambda a, b: [])
    assert (res['speed'], res['seconds'], res['server_cpus'], res['features']) == ('elytra', 120, 2, ['deadline'])
    runner, ctl, flyer, fly = ab_rig(tmp_path / 'one')
    res = demo_ab.run_ab(runner, ctl, flyer, tmp_path / 'one' / 'artifacts', server_cpus=1, events=lambda a, b: [])
    assert not res['A']['valid'] and any('核' in r for r in res['A']['invalid'])
    with pytest.raises(ValueError):
        demo_ab.run_ab(runner, ctl, flyer, tmp_path / 'x', server_cpus=8, events=lambda a, b: [])


def test_page_defaults(web):
    with urllib.request.urlopen(web['base'] + '/demo', timeout=5) as r:
        page = r.read().decode()
    assert '<option value="elytra" selected>' in page and 'id="seconds" type="number" min="10" max="600" value="120"' in page
    assert 'id="server-cpus"' in page and '<option value="2" selected>' in page
