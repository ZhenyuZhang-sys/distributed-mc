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
        with resource_environment(slot['cpus']) as cpuset:
            world = WorldSession(directory / 'server', prefix=f"l2{slot['speed_name'][0]}{slot['cpus']}", seed=p['seed'])
            server = world.start()
            try:
                configure(server)
                cg = Cgroup(compose('ps', '-q', 'minecraft').strip(), run)
                cg.verify(slot['cpus'], cpuset)
                own.add(cg.cid)
                wx, wz = p['warm_origin_chunk']
                for i in range(p['warmup_by_cpu'][str(slot['cpus'])] // 256):
                    server.chunky_square(wx + i * 32, wz, 16)
                if slot['pregenerate']:
                    chunks = route_chunks(p, slot)
                    got = server.chunky_list(chunks, timeout=3600)
                    if got != len(chunks):
                        raise RuntimeError(f'预生成航线：期望 {len(chunks)}，Chunky 报 {got}')
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
        return {**summary, 'cpuset': cpuset, 'world': world.level, 'flight_dir': str(out),
                'concurrent': [f"{e['name']} {e['action']}" for e in foreign]}

    return fly_slot
