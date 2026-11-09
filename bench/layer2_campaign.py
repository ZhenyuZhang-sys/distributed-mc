"""第 4 步 4.7、4.8：冒烟测量的编排（docs/step4-plan.md 第 5 节，降级版）。

- 协议在 bench/layer2-protocol.json；部署机器上跑满只改配置（repeats、extra、rerun_on_interference）。
- 槽位：先两条指标自检（5.3），再按种子打乱的矩阵。每个槽位一个目录，里面 attempt-001、002……，
  manifest.json 记状态（running / passed / failed / invalid / interrupted）；已通过的槽位断点续跑时不再飞。
- 有效性（5.1a）：飞行期间别的容器有生命周期事件，记进结果的 concurrent。rerun_on_interference=false（降级）时只标记；
  true 时这一次判 invalid 并重跑，最多 max_attempts 次。
- 自检那轮有并发，结论只算"参考"（调度 11-08）。

纯逻辑和真实飞行分开：run_campaign 接受一个 fly_slot(slot, directory) -> 结果字典，测试里用假函数。
"""
from __future__ import annotations

import json
import math
import random
import time
from datetime import datetime, timezone
from pathlib import Path

CPUS = (1, 2, 4)
SELFCHECKS = ('pregen', 'one_core')


# ---- 协议 ----

def validate(p: dict) -> dict:
    speeds = p.get('speeds')
    if not isinstance(speeds, dict) or not speeds or not all(isinstance(v, (int, float)) and v > 0 for v in speeds.values()):
        raise ValueError('speeds 要是 {名字: 正数 格/秒}')
    if p.get('cpus') not in CPUS:
        raise ValueError('cpus 只能是 1、2、4')
    reps = p.get('repeats')
    if not isinstance(reps, dict) or set(reps) != set(speeds) or not all(type(v) is int and v >= 1 for v in reps.values()):
        raise ValueError('repeats 要给每种速度一个 ≥ 1 的整数')
    if p.get('mode') not in ('wall', 'tick'):
        raise ValueError('mode 只能是 wall 或 tick')
    if type(p.get('seconds')) is not int or p['seconds'] < 1:
        raise ValueError('seconds 要是正整数')
    if type(p.get('max_attempts')) is not int or p['max_attempts'] < 1:
        raise ValueError('max_attempts 至少 1')
    for e in p.get('extra', []):
        if e.get('speed') not in speeds or e.get('cpus') not in CPUS or type(e.get('repeats')) is not int or e['repeats'] < 1:
            raise ValueError(f'extra 不合法：{e}')
    checks = p.get('selfcheck', [])
    if sorted(c.get('name') for c in checks) != sorted(SELFCHECKS):
        raise ValueError(f'selfcheck 必须正好是 {SELFCHECKS}')
    for c in checks:
        if c.get('speed') not in speeds or c.get('cpus') not in CPUS or type(c.get('pregenerate')) is not bool:
            raise ValueError(f'selfcheck 不合法：{c}')
    crit = p.get('selfcheck_criteria', {})
    if set(crit) != {'pregen_void_mean_lt', 'pregen_latency_p95_ms_lt', 'one_core_void_mean_above_pregen'}:
        raise ValueError('selfcheck_criteria 缺项')
    used = {p['cpus']} | {e['cpus'] for e in p.get('extra', [])} | {c['cpus'] for c in checks}
    warm = p.get('warmup_by_cpu', {})
    for c in used:
        n = warm.get(str(c))
        if type(n) is not int or n <= 0 or n % 256:
            raise ValueError(f'warmup_by_cpu 缺 {c} 核或不是 256 的倍数')
    if type(p.get('rerun_on_interference')) is not bool:
        raise ValueError('rerun_on_interference 要是 true / false')
    return p


def load_protocol(path) -> dict:
    return validate(json.loads(Path(path).read_text()))


# ---- 槽位 ----

def slots(p: dict) -> list[dict]:
    """自检在前（按协议顺序），矩阵按 matrix_seed 打乱。槽位名只由（速度、核数、第几次）决定，跑满时前面的能复用。"""
    out = []
    for c in p['selfcheck']:
        out.append(dict(slot=f"selfcheck-{c['name']}", kind='selfcheck', name=c['name'], speed_name=c['speed'],
                        speed=p['speeds'][c['speed']], cpus=c['cpus'], repeat=1, pregenerate=c['pregenerate']))
    matrix = []
    for name in p['speeds']:
        for r in range(1, p['repeats'][name] + 1):
            matrix.append((name, p['cpus'], r))
    for e in p.get('extra', []):
        for r in range(1, e['repeats'] + 1):
            matrix.append((e['speed'], e['cpus'], r))
    if len(set(matrix)) != len(matrix):
        raise ValueError('矩阵里有重复的（速度、核数、第几次）')
    matrix.sort()
    random.Random(p['matrix_seed']).shuffle(matrix)
    for name, cpus, r in matrix:
        out.append(dict(slot=f'{name}-c{cpus}-r{r}', kind='smoke', name=None, speed_name=name, speed=p['speeds'][name],
                        cpus=cpus, repeat=r, pregenerate=False))
    for i, s in enumerate(out):
        s['order'] = i
    return out


# ---- 并发干扰 ----

def interference(events, own, actions):
    """docker events（JSON 行解析后的字典）里，别的容器的生命周期事件。exec、网络等不算。"""
    found = []
    for e in events:
        if e.get('Type') != 'container' or e.get('Action') not in actions:
            continue
        actor = e.get('Actor', {})
        if actor.get('ID') in own:
            continue
        found.append(dict(id=actor.get('ID'), name=actor.get('Attributes', {}).get('name'), action=e['Action'],
                          time_ns=e.get('timeNano')))
    return found


# ---- 自检判定 ----

def _num(v):
    return math.inf if v == 'inf' or v is None else float(v)


def selfcheck_verdict(results: dict, crit: dict) -> dict:
    """计划 5.3 的两条判据（看数据之前定的）。有并发的那条只算参考；第 2 条要和第 1 条比，第 1 条是参考时它也只是参考。"""
    pre, one = results['pregen'], results['one_core']
    pre_ok = _num(pre['void_mean']) < crit['pregen_void_mean_lt'] and _num(pre['latency_p95_ms']) < crit['pregen_latency_p95_ms_lt']
    one_ok = _num(one['void_mean']) >= _num(pre['void_mean']) + crit['one_core_void_mean_above_pregen']
    pre_ref = bool(pre.get('concurrent'))
    one_ref = pre_ref or bool(one.get('concurrent'))

    def entry(r, ok, ref):
        return dict(status='reference' if ref else ('passed' if ok else 'failed'), criteria_met=ok,
                    void_mean=r['void_mean'], latency_p95_ms=r['latency_p95_ms'], concurrent=list(r.get('concurrent', [])))
    return dict(pregen=entry(pre, pre_ok, pre_ref), one_core=entry(one, one_ok, one_ref))


# ---- 每个配置的汇总 ----

def config_summary(rows, keys):
    """降级：1 次不报波动，2 次报两次的差，3 次以上报标准差。有 inf（删失）时均值写 inf，不报波动。"""
    groups = {}
    for r in rows:
        groups.setdefault((r['speed_name'], r['cpus']), []).append(r)
    out = []
    for (name, cpus), rs in groups.items():
        s = dict(speed_name=name, cpus=cpus, n=len(rs))
        for k in keys:
            values = [_num(r[k]) for r in rs]
            finite = all(math.isfinite(v) for v in values)
            mean = sum(values) / len(values) if finite else math.inf
            s[f'{k}_mean'] = mean if finite else 'inf'
            s[f'{k}_diff'] = abs(values[0] - values[1]) if finite and len(values) == 2 else None
            s[f'{k}_std'] = (sum((v - mean) ** 2 for v in values) / (len(values) - 1)) ** .5 if finite and len(values) >= 3 else None
        out.append(s)
    return out


# ---- 编排 ----

def _write(path, data):
    tmp = Path(str(path) + '.tmp')
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1) + '\n')
    tmp.replace(path)


def _now():
    return datetime.now(timezone.utc).isoformat()


def run_campaign(p: dict, root, fly_slot) -> dict:
    """按槽位跑，返回 {campaign, passed, failed, concurrent, results}。root 已存在时按断点续跑处理，协议必须一样。"""
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True)
    saved = root / 'protocol.json'
    if saved.exists():
        if json.loads(saved.read_text()) != p:
            raise ValueError('这个 campaign 的协议和现在的不一样，不能续跑')
    else:
        _write(saved, p)
    passed, failed, concurrent, results = 0, 0, [], {}
    for slot in slots(p):
        d = root / slot['slot']
        d.mkdir(exist_ok=True)
        attempts = sorted(x for x in d.iterdir() if x.name.startswith('attempt-'))
        done = next((a for a in attempts if json.loads((a / 'manifest.json').read_text())['status'] == 'passed'), None)
        if done is None:
            for a in attempts:  # 上次中断时还在跑的
                m = json.loads((a / 'manifest.json').read_text())
                if m['status'] == 'running':
                    m.update(status='interrupted', error='续跑时发现没跑完')
                    _write(a / 'manifest.json', m)
            n = len(attempts)
            while n < p['max_attempts'] and done is None:
                n += 1
                a = d / f'attempt-{n:03}'
                a.mkdir()
                manifest = dict(status='running', slot=slot, started_utc=_now())
                _write(a / 'manifest.json', manifest)
                try:
                    result = fly_slot(slot, a)
                except (KeyboardInterrupt, SystemExit):
                    manifest.update(status='interrupted', finished_utc=_now())
                    _write(a / 'manifest.json', manifest)
                    raise
                except Exception as exc:
                    manifest.update(status='failed', error=f'{type(exc).__name__}: {exc}', finished_utc=_now())
                    _write(a / 'manifest.json', manifest)
                    print(f"{slot['slot']} 第 {n} 次失败：{exc}", flush=True)
                    continue
                result = {**result, **{k: slot[k] for k in ('slot', 'kind', 'name', 'speed_name', 'speed', 'cpus', 'repeat', 'order')},
                          'attempt': a.name}
                if result.get('invalid'):
                    # 无效（比如主线程同步加载不为 0，调度 11-09）：照样留档，重跑
                    manifest.update(status='invalid', invalid=result['invalid'], finished_utc=_now())
                    _write(a / 'manifest.json', manifest)
                    _write(a / 'result.json', result)
                    continue
                conc = list(result.get('concurrent', []))
                if conc and p['rerun_on_interference']:
                    manifest.update(status='invalid', concurrent=conc, finished_utc=_now())
                    _write(a / 'manifest.json', manifest)
                    continue
                _write(a / 'result.json', result)
                manifest.update(status='passed', concurrent=conc, finished_utc=_now())
                _write(a / 'manifest.json', manifest)
                done = a
        if done is None:
            failed += 1
            continue
        r = json.loads((done / 'result.json').read_text())
        results[slot['slot']] = r
        passed += 1
        if r.get('concurrent'):
            concurrent.append(slot['slot'])
    return dict(campaign=root.name, passed=passed, failed=failed, concurrent=concurrent, results=results)


# ---- 真实飞行（要锁和测试服务器；slow 测试里用） ----

def route_chunks(p, slot):
    """自检 1 要预生成的范围：整条航线加两侧视距再多 2 圈。"""
    x0, z0 = p['start']
    x1 = x0 + slot['speed'] * p['seconds']
    m = p['view_distance'] + 2
    cx0, cx1, cz = math.floor(x0 / 16), math.floor(x1 / 16), math.floor(z0 / 16)
    return [(x, z) for x in range(cx0 - m, cx1 + m + 1) for z in range(cz - m, cz + m + 1)]


def docker_events(since: float, until: float):
    from bench.server import run
    text = run('docker', 'events', '--since', f'{since:.3f}', '--until', f'{until:.3f}', '--format', '{{json .}}')
    return [json.loads(line) for line in text.splitlines() if line.strip()]


def test_project() -> str:
    """lib.sh 实际用的测试 compose 项目名（环境变量优先，否则 docker/.env.test）。"""
    from bench.server import SCRIPTS, run
    return run('bash', '-c', 'source "$1"; printf %s "$TEST_PROJECT"', 'bash', str(SCRIPTS / 'lib.sh')).strip()


def workers_running() -> list[str]:
    """本项目正在跑的 donor worker 容器名。"""
    from bench.server import run
    out = run('docker', 'ps', '--filter', f'label=com.docker.compose.project={test_project()}',
              '--format', '{{.Names}} {{.Label "com.docker.compose.service"}}')
    return sorted(line.split()[0] for line in out.splitlines() if line.strip() and line.split()[-1].startswith('worker'))


def without_workers():
    """原版基线不带 donor：先停掉本项目的 worker，再把 COMPOSE_PROFILES 清空，新世界就不会把它们带起来。结束后恢复。"""
    import os
    from contextlib import contextmanager

    from bench.server import compose

    @contextmanager
    def ctx():
        saved = os.environ.get('COMPOSE_PROFILES')
        try:
            os.environ['COMPOSE_PROFILES'] = 'worker,worker2'
            compose('stop', 'worker', 'worker2', timeout=300)
            os.environ['COMPOSE_PROFILES'] = ''
            yield
        finally:
            if saved is None:
                os.environ.pop('COMPOSE_PROFILES', None)
            else:
                os.environ['COMPOSE_PROFILES'] = saved
    return ctx()


def real_fly_slot(p: dict):
    """返回真实的 fly_slot：新世界、预热、（自检 1：预生成航线并重启）、等 CPU 稳定、飞一轮、记 docker 事件。"""
    from bench import layer2
    from bench.environment import configure
    from bench.layer1 import settle
    from bench.metrics import Cgroup
    from bench.server import compose, run
    from bench.session import WorldSession, resource_environment

    def fly_slot(slot, directory):
        t0 = time.time()
        own = set()
        pregenerated = None
        with without_workers(), resource_environment(slot['cpus']) as cpuset:
            world = WorldSession(directory / 'server', prefix=f"l2{slot['speed_name'][0]}{slot['cpus']}", seed=p['seed'])
            server = world.start()
            try:
                configure(server)
                cg = Cgroup(compose('ps', '-q', 'minecraft').strip(), run)
                cg.verify(slot['cpus'], cpuset)
                own.add(cg.cid)
                running = workers_running()
                if running:
                    raise RuntimeError(f'原版基线旁边有 donor worker 在跑：{running}')
                wx, wz = p['warm_origin_chunk']
                for i in range(p['warmup_by_cpu'][str(slot['cpus'])] // 256):
                    server.chunky_square(wx + i * 32, wz, 16)
                if slot['pregenerate']:
                    chunks = route_chunks(p, slot)
                    got = server.chunky_list(chunks, timeout=3600)
                    # Chunky 的 Processed 还算上选区边缘取整多出的几行（见 Server.chunky_square 的注释），只会多不会少。
                    # 航线是不是真的都生成了，由自检结果本身检验：预生成后虚空率应接近 0。
                    if got < len(chunks):
                        raise RuntimeError(f'预生成航线：期望至少 {len(chunks)}，Chunky 报 {got}')
                    pregenerated = dict(route_chunks=len(chunks), chunky_processed=got)
                    server.restart()
                    cg = Cgroup(compose('ps', '-q', 'minecraft').strip(), run)
                    cg.verify(slot['cpus'], cpuset)
                    own.add(cg.cid)
                settle(cg, dict(idle_cores=p['settle_idle_cores'], settle_seconds=5))
                out = layer2.fly(server, run=f"{slot['slot']}-{directory.name}", player='l2bot', start=tuple(p['start']),
                                 speed=slot['speed'], seconds=p['seconds'], out_dir=directory / 'flight', mode=p['mode'],
                                 y=p['y'], tail_exclude_ms=p['tail_exclude_ms'])
                summary = json.loads((out / 'summary.json').read_text())
                if summary['view_distance'] != p['view_distance']:
                    raise RuntimeError(f"服务器视距 {summary['view_distance']}，协议是 {p['view_distance']}")
            finally:
                world.finish()
        t1 = time.time()
        events = docker_events(t0, t1)
        with (directory / 'docker-events.jsonl').open('w') as f:
            for e in events:
                f.write(json.dumps(e) + '\n')
        foreign = interference(events, own, tuple(p['interference_actions']))
        world.cleanup()
        result = {**summary, 'cpuset': cpuset, 'world': world.level, 'flight_dir': str(out), 'pregenerated': pregenerated,
                  'workers_running': workers_running(), 'concurrent': [f"{e['name']} {e['action']}" for e in foreign]}
        if summary['sync_loads']:
            result['invalid'] = f"主线程同步加载 {summary['sync_loads']} 次（最长 {summary['sync_load_max_ms']:.0f} 毫秒）"
        return result

    return fly_slot


# ---- 发布（4.8） ----

SUMMARY_KEYS = ('void_mean', 'void_p95', 'latency_p50_ms', 'latency_p95_ms', 'latency_p99_ms', 'censored_fraction',
                'stall_s', 'stall_fraction', 'distance_ratio', 'mspt_mean', 'mspt_max', 'cpu_cores_mean', 'initial_load_s')
RUN_COLUMNS = ('slot', 'kind', 'name', 'speed_name', 'speed', 'cpus', 'repeat', 'order', 'attempt', *SUMMARY_KEYS,
               'distance', 'sync_loads', 'available_processors', 'scan_ms_total', 'scan_every_ticks', 'concurrent')
ARTIFACTS = ('layer2-runs.csv', 'layer2-summary.csv', 'layer2.csv', 'layer2-chunks.csv', 'selfcheck.json', 'baseline-layer2.md')
STATUS = {'passed': '通过', 'failed': '没过', 'reference': '参考（有并发，不算通过）'}


def _csv(path, columns, rows):
    import csv
    tmp = Path(str(path) + '.tmp')
    with tmp.open('w', newline='') as f:
        w = csv.DictWriter(f, columns)
        w.writeheader()
        for r in rows:
            w.writerow({k: r.get(k) for k in columns})
    tmp.replace(path)


def _merge_csv(results, name, out):
    """每轮 flight 目录里的同名 CSV 合成一张，最前面加一列 slot。"""
    import csv
    columns, merged = None, []
    for r in results:
        with (Path(r['flight_dir']) / name).open() as f:
            reader = csv.DictReader(f)
            if columns is None:
                columns = ['slot', *reader.fieldnames]
            elif ['slot', *reader.fieldnames] != columns:
                raise ValueError(f"{r['slot']} 的 {name} 列不一样")
            merged.extend({'slot': r['slot'], **row} for row in reader)
    _csv(out / name, columns, merged)


def _fmt(v, digits=3):
    if v is None or v == '':
        return '—'
    if v == 'inf' or (isinstance(v, float) and math.isinf(v)):
        return '∞（有删失）'
    if isinstance(v, float):
        return f'{v:.{digits}g}'
    return str(v)


def report(p, result, ordered, summary, verdict) -> str:
    smoke = [r for r in ordered if r['kind'] == 'smoke']
    conc = [r for r in ordered if r.get('concurrent')]
    lines = [
        '# 第 2 层基线：飞行时的卡住、虚空率和就绪延迟（冒烟）',
        '',
        '**冒烟，在这台 EPYC 上限核测得，部署机器 N100 上要重测。** 降级（调度 11-08）：每种速度 1～2 次，只能看量级；'
        '部署机器上把 `bench/layer2-protocol.json` 的 repeats 改成 5、rerun_on_interference 改成 true 跑满。',
        '',
        f"campaign `{result['campaign']}`。速度 {', '.join(f'{k} {v} 格/秒' for k, v in p['speeds'].items())}；"
        f"视距 {p['view_distance']}，模拟距离 {p['simulation_distance']}；每轮 {p['seconds']} 秒，{p['mode']} 模式，"
        f"高度 {p['y']}，沿 +x；seed {p['seed']}，每轮全新世界。定义见 docs/step4-plan.md 第 2 节。",
        '',
        '## 指标自检（计划 5.3，判据在看数据之前定）',
        '',
        '| 自检 | 判据 | 虚空率均值 | 就绪 p95（毫秒） | 结论 |',
        '| --- | --- | --- | --- | --- |',
        f"| 预生成后再飞（2 核鞘翅） | 虚空率 < {p['selfcheck_criteria']['pregen_void_mean_lt']:.0%}，p95 < {p['selfcheck_criteria']['pregen_latency_p95_ms_lt']} 毫秒 "
        f"| {_fmt(verdict['pregen']['void_mean'])} | {_fmt(verdict['pregen']['latency_p95_ms'])} | {STATUS[verdict['pregen']['status']]} |",
        f"| 1 核鞘翅、不预生成 | 虚空率比上一条高至少 {p['selfcheck_criteria']['one_core_void_mean_above_pregen'] * 100:.0f} 个百分点 "
        f"| {_fmt(verdict['one_core']['void_mean'])} | {_fmt(verdict['one_core']['latency_p95_ms'])} | {STATUS[verdict['one_core']['status']]} |",
        '',
    ]
    if any(v['status'] == 'reference' for v in verdict.values()):
        lines += ['自检那轮有并发，结论只算参考，要在安静时补一轮（调度 11-08）。', '']
    lines += ['## 每轮', '',
              '| 槽位 | 核数 | availableProcessors | 卡住（秒，占比） | 实际 / 理想距离 | 虚空率均值 | 虚空率 p95 | 就绪 p50 / p95 / p99（毫秒） | 删失比例 | MSPT 均值 / 最大 | 服务器 CPU（核） | 同步加载 | 并发 |',
              '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |']
    for r in ordered:
        lines.append(f"| {r['slot']} | {r['cpus']} | {r.get('available_processors')} | {_fmt(r.get('stall_s'))}（{_fmt(r.get('stall_fraction'))}） "
                     f"| {_fmt(r.get('distance_ratio'))} | {_fmt(r['void_mean'])} | {_fmt(r['void_p95'])} "
                     f"| {_fmt(r['latency_p50_ms'])} / {_fmt(r['latency_p95_ms'])} / {_fmt(r['latency_p99_ms'])} "
                     f"| {_fmt(r['censored_fraction'])} | {_fmt(r['mspt_mean'])} / {_fmt(r['mspt_max'])} | {_fmt(r['cpu_cores_mean'])} | {r.get('sync_loads')} "
                     f"| {'; '.join(r.get('concurrent') or []) or '无'} |")
    lines += ['', '## 每个配置（只算冒烟矩阵，不含自检）', '',
              '1 次的配置不报波动；2 次的报两次之差。差值能不能算显著（> 5 次标准差的 2 倍，计划 5.2）要等部署机器上的完整矩阵。', '',
              '| 速度 | 核数 | 次数 | 卡住时长均值（秒） | 两次之差 | 实际 / 理想距离 | 虚空率均值 | 两次之差 | 就绪 p95 均值（毫秒） | 两次之差 |',
              '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |']
    for s in summary:
        lines.append(f"| {s['speed_name']} | {s['cpus']} | {s['n']} | {_fmt(s['stall_s_mean'])} | {_fmt(s['stall_s_diff'])} "
                     f"| {_fmt(s['distance_ratio_mean'])} | {_fmt(s['void_mean_mean'])} | {_fmt(s['void_mean_diff'])} "
                     f"| {_fmt(s['latency_p95_ms_mean'])} | {_fmt(s['latency_p95_ms_diff'])} |")
    every = ', '.join(sorted({str(r.get('scan_every_ticks')) for r in ordered}))
    # 每 tick 扫视野的平均耗时占 MSPT 均值的比例（扫描本身不计进 MSPT，但占服务器时间）
    share = [r['scan_ms_total'] / r['ticks_total'] / r['mspt_mean'] for r in ordered
             if r.get('ticks_total') and r.get('mspt_mean')]
    lines += ['', '## 背景和局限', '',
              '- 1.21.1 的后台生成线程数是 `clamp(可用核数 − 1, 1, 上限)`：1 核和 2 核都只有 1 个生成线程，4 核有 3 个（第 1 步发现，计划 5.5）。'
              '2 核基线上原版生成只用到大约一个核；第 6 步解读 donor 的效果时要记住这一点。每轮容器里实际的 availableProcessors 见上表。',
              '- 就绪是服务器那一侧的：`ChunkHolder.getChunkToSend()` 不为空（sendSync 完成且到 BLOCK_TICKING）。假玩家没有真客户端，不算网络和渲染。',
              '- **假玩家像真实客户端一样飞**（11-09）：客户端不会走进自己还没收到的 chunk（`LocalPlayer.tick` 先查 `hasChunkAt`），'
              '所以飞行按速度积分前进，前面的 chunk 没就绪就停在边缘等。**卡住**的时长（玩家停在边缘的时间）是真实玩家最直接的感受，'
              '和实际距离对比理想距离一起列出；第 10 步的 X 仍按就绪 p95 算。原来按"起点 + 速度 × 时间"直接放玩家，假玩家会跑到生成前面，'
              '实体 tick 查脚下流体（`Entity.updateFluidHeightAndDoFluidPushing` → `Level.getFluidState`）时在主线程上同步生成，2 核鞘翅一个 tick 卡满 60 秒被看门狗杀掉（fish 6.6）。',
              '- **同步加载**是守护计数：飞行期间主线程上 `ServerChunkCache.getChunk(load=true)` 耗时 ≥ 1 毫秒的次数。真实玩家不会造成它，不为 0 的一轮判无效重跑。',
              f"- 扫视野的间隔 scan_every_ticks = {every}。扫描耗时不计进 MSPT，但占服务器时间："
              + (f"每 tick 平均扫描耗时是 MSPT 均值的 {min(share):.1%}～{max(share):.1%}。" if share else "这批结果没有 tick 数，算不出占比。")
              + "计划 5.1 要求 < 2%，超过就改成每 2 tick 扫一次再测。",
              f"- 就绪延迟只算起飞后进入视野、离结束超过 {p['tail_exclude_ms'] // 1000} 秒的 chunk；删失（出视野时还没就绪）按无穷大算，分位数是保守的上界。",
              '- 冒烟测量不要求机器安静（降级）。']
    if conc:
        lines.append('- **有并发**的轮次（飞行期间别的容器有生命周期事件），照样保留：' +
                     '；'.join(f"{r['slot']}：{', '.join(r['concurrent'])}" for r in conc) + '。是否重跑由读者决定。')
    else:
        lines.append('- 所有轮次飞行期间都没有别的容器的生命周期事件。')
    return '\n'.join(lines) + '\n'


def publish(root, p, result, out) -> dict:
    """全部槽位通过、自检没有"没过"时，把结果写进 out（analysis/results），返回并写出 layer2-smoke.json。"""
    from bench.models import digest
    root, out = Path(root), Path(out)
    if result['failed']:
        raise ValueError(f"有 {result['failed']} 个槽位失败，不发布")
    res = result['results']
    verdict = selfcheck_verdict({s: res[f'selfcheck-{s}'] for s in SELFCHECKS}, p['selfcheck_criteria'])
    if any(v['status'] == 'failed' for v in verdict.values()):
        raise ValueError('自检没过：先重跑那一条再下结论（调度 11-08），不发布')
    ordered = [res[s['slot']] for s in slots(p)]
    summary = config_summary([r for r in ordered if r['kind'] == 'smoke'], SUMMARY_KEYS)
    out.mkdir(parents=True, exist_ok=True)
    _csv(out / 'layer2-runs.csv', RUN_COLUMNS, [{**r, 'concurrent': '; '.join(r.get('concurrent') or [])} for r in ordered])
    _csv(out / 'layer2-summary.csv', list(summary[0]), summary)
    _merge_csv(ordered, 'layer2.csv', out)
    _merge_csv(ordered, 'layer2-chunks.csv', out)
    _write(out / 'selfcheck.json', verdict)
    (out / 'baseline-layer2.md').write_text(report(p, result, ordered, summary, verdict))
    state = dict(smoke=True, campaign=root.name, protocol_digest=digest(root / 'protocol.json'),
                 published_utc=_now(), artifacts={name: digest(out / name) for name in ARTIFACTS})
    _write(out / 'layer2-smoke.json', state)
    return state


# ---- README 第 2 层（4.9） ----

README_BEGIN = '<!-- layer2-smoke:begin 由 bench/layer2_campaign.py 从 analysis/results 生成，不要手改 -->'
README_END = '<!-- layer2-smoke:end -->'
SPEED_NAMES = {'walk': '走路', 'horse': '骑马', 'elytra': '鞘翅'}


def readme_block(out) -> str:
    """README 里第 2 层冒烟初值那一段，只读已发布的结果。"""
    import csv
    out = Path(out)
    with (out / 'layer2-summary.csv').open() as f:
        summary = list(csv.DictReader(f))
    check = json.loads((out / 'selfcheck.json').read_text())
    state = json.loads((out / 'layer2-smoke.json').read_text())

    def num(v, digits=3):
        if v in ('', None):
            return '—'
        if v == 'inf':
            return '∞（有删失）'
        return f'{float(v):.{digits}g}'
    lines = [README_BEGIN, '### 第 4 步冒烟给的第 2 层初值', '',
             f"> **冒烟**，在这台 EPYC 7542 上限核测得，部署机器 N100 上要重测。每种速度 1～2 次，只能看量级。"
             f"campaign `{state['campaign']}`，详见 [baseline-layer2.md](analysis/results/baseline-layer2.md)。", '',
             '| 速度 | 核数 | 次数 | 卡住时长（秒） | 实际 / 理想距离 | 虚空率均值 | 就绪 p95（毫秒） | MSPT 均值（毫秒） | 服务器 CPU（核） |',
             '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |']
    for s in summary:
        lines.append(f"| {SPEED_NAMES.get(s['speed_name'], s['speed_name'])} | {s['cpus']} | {s['n']} | {num(s['stall_s_mean'])} "
                     f"| {num(s['distance_ratio_mean'])} | {num(s['void_mean_mean'])} "
                     f"| {num(s['latency_p95_ms_mean'])} | {num(s['mspt_mean_mean'])} | {num(s['cpu_cores_mean_mean'])} |")
    status = {'passed': '通过', 'failed': '没过', 'reference': '只算参考（那轮有并发）'}
    lines += ['', f"- 指标自检：预生成后再飞{status[check['pregen']['status']]}（虚空率均值 {num(check['pregen']['void_mean'])}）；"
                  f"1 核鞘翅{status[check['one_core']['status']]}（虚空率均值 {num(check['one_core']['void_mean'])}）。",
              '- 卡住 = 玩家停在还没就绪的 chunk 前面等（假玩家像真实客户端一样不走进没收到的 chunk），是玩家最直接的感受。',
              '- 2 核时原版只有 1 个生成线程（和第 1 步一样），第 6 步 donor 的提升里有一部分来自原版本来就没用上的那个核。',
              README_END]
    return '\n'.join(lines) + '\n'


def update_readme(readme, out) -> None:
    """把 README 里两个标记之间（含标记）换成 readme_block(out)。"""
    readme = Path(readme)
    text = readme.read_text()
    a, b = text.find(README_BEGIN), text.find(README_END)
    if a < 0 or b < a:
        raise ValueError('README 里没有第 2 层的标记')
    b = text.index('\n', b) + 1 if '\n' in text[b:] else len(text)
    readme.write_text(text[:a] + readme_block(out) + text[b:])
