"""第 10 步 10.6：flight 实验的真驱动（按用户 11-09 的"能演示"做，只支持这台机器上能跑的配置）。

一个单元：换一个全新世界（同 seed，服务器按 server_cpus 重建），按 donors 开关 donor（0 或 2 个），按 features 设第 7 步开关，
latency_ms > 0 时开模拟网络（fish 的 netem.sh：35ms / 20mbit / 1% 丢包，固定值），飞一轮（第 4 步 bench.layer2.fly），
按演示 A/B 的规则判有效。无效就抛异常，run_matrix 记 failed 并重试（最多 3 次）。
用的都是演示面板的部件（bench/demo），不另写一套控制。
"""
from __future__ import annotations

import csv
import json
import math
import time
from pathlib import Path

import threading

from bench import layer2_stats, scenarios
from bench.demo import ab
from bench.demo.control import ALL_WORKERS, FEATURES, SPEEDS, server_cpuset

# 写进 result.json 的指标。不能和参数同名（汇总会把它当成参数）：summary.json 里的 speed、seconds 这些不带。
METRICS = ('stall_s', 'stall_fraction', 'distance_ratio', 'void_mean', 'void_p95', 'latency_p50_ms', 'latency_p95_ms',
           'latency_p99_ms', 'censored_fraction', 'mspt_mean', 'mspt_max', 'cpu_cores_mean', 'full_generated',
           'full_resumed', 'full_loaded', 'sync_loads', 'sync_loads_world', 'available_processors', 'initial_load_s')


def _num(v):
    """封存的 JSON 不许有无穷大：删失照第 4 步写成 'inf'，matrix_summary.collect 读回来变成无穷大。"""
    return 'inf' if isinstance(v, float) and math.isinf(v) else v


def _p95(values):
    values = sorted(v for v in values if v is not None)
    return values[min(len(values) - 1, int(len(values) * 0.95))] if values else None


def scenario_speed(p) -> float:
    """这一轮的速度：给了 speed_bps 用它；烟花场景默认 33.5（路线就按它飞）；其余按 speed 档位。"""
    if p.get('speed_bps'):
        return float(p['speed_bps'])
    if str(p.get('scenario', '')).startswith('firework'):
        return scenarios.FIREWORK
    return SPEEDS[p['speed']]


def resolve_env(env, local):
    """donor 配置的环境变量里，'@A+B' 换成这个槽位 .env.local 里 A、B 的值用逗号连起来（1 个强 donor 拼 worker、worker2 的核）。"""
    out = {}
    for k, v in (env or {}).items():
        if isinstance(v, str) and v.startswith('@'):
            parts = []
            for name in v[1:].split('+'):
                val = local(name)
                if not val:
                    raise ValueError(f'槽位的 .env.local 里没有 {name}')
                parts.append(val)
            v = ','.join(parts)
        out[k] = v
    return out


def _features_on(features):
    """v0 = 全关，all = 全开，单项名，或者用 + 连起来的组合（比如 deadline+throughput）。"""
    if features in (None, 'v0'):
        return ()
    if features == 'all':
        return FEATURES
    names = tuple(features.split('+'))
    bad = [n for n in names if n not in FEATURES]
    if bad:
        raise ValueError(f'不认识的开关：{bad}')
    return names


# 矩阵维度 → coordinator 配置项（-Dmcdonate.<项>，/donor status 的 config 里核对）
SERVER_PROPS = {'scan_budget_us': 'demandScanBudgetMicros', 'corridor_chunks': 'corridorChunks', 'max_concurrency': 'maxConcurrency',
                'forward_first': 'demandForwardFirst', 'ticket_throttle': 'playerTicketThrottle'}
SERVER_PROP_NAMES = {'scan_budget_us': '扫描预算', 'corridor_chunks': 'corridorChunks', 'max_concurrency': 'maxConcurrency',
                     'forward_first': 'demandForwardFirst', 'ticket_throttle': 'playerTicketThrottle'}


def _prop_used(status, k):
    """服务器实际用的值：一般在 /donor status 的 config 里；玩家票节流（fish-throttle）读 playerTicketThrottle.actual 主世界的 maxTasks。"""
    if k == 'ticket_throttle':
        return (status.get('playerTicketThrottle') or {}).get('actual', {}).get('minecraft:overworld')
    return status.get('config', {}).get(SERVER_PROPS[k])


def _prop_matches(want, used):
    """/donor status 里开关是 true/false，数是数：0/1 对 false/true，其余按字符串比。"""
    if isinstance(used, bool):
        return str(want) in ('0', '1') and bool(int(want)) == used
    return str(used) == str(want)


def _status_counts(controller) -> dict:
    """/donor status 里会涨的计数：导入、丢弃按原因、过时删掉的（7.2）、导入的主线程耗时、donor 做完的 tile。"""
    s = controller.status()
    d, stale = s.get('discarded', {}), s.get('stale', {})
    return dict(imports_accepted=s.get('accepted', 0), discard_late=d.get('late', 0), discard_other_world=d.get('other_world', 0),
                discard_server=d.get('server', 0), stale_dropped=stale.get('dropped', 0), stale_cancelled=stale.get('cancelled', 0),
                import_ms=s.get('import', {}).get('totalMs', 0), tiles_done=sum(w.get('done', 0) for w in s.get('workers', [])))


def _check_worker_jar(runner):
    """donor 用的 worker jar（build/mods-worker，donor 容器挂的）必须就是刚构建的那个（worker/build/libs）。
    11-09 事故：跑批只跑了 collectMods，mods-worker 一直是早上的旧 jar，一整上午的代码对比作废。返回 jar 的指纹（md5 前 8 位）。"""
    if not hasattr(runner, 'worker_jar'):
        return None
    j = runner.worker_jar()
    if j['mounted'] != j['built']:
        raise RuntimeError(f"donor 挂的 worker jar（{j['mounted']}）不是刚构建的（{j['built']}）：构建时要跑 collectWorkerMods")
    return j['mounted']


def _server_tick(runner):
    """服务器现在的 tick 数（/bench tick），拿不到是 None。"""
    import json
    try:
        return int(json.loads(runner.rcon('bench tick'))['tick'])
    except Exception:   # noqa: BLE001
        return None


def _keep_worker_logs(runner, controller, out, since):
    """这一轮起过的 donor 容器的日志（带时间戳）存成 worker-<服务>.log。拿不到就算了。"""
    if not hasattr(runner, 'service_log'):
        return
    for svc in sorted(getattr(controller, 'active', None) or []):
        try:
            lines = runner.service_log(svc, since)
            out.mkdir(parents=True, exist_ok=True)
            (out / f'worker-{svc}.log').write_text(''.join(l + '\n' for l in lines))
        except Exception as e:   # noqa: BLE001
            out.mkdir(parents=True, exist_ok=True)
            (out / f'worker-{svc}.log.error').write_text(repr(e))


def _keep_diagnostics(runner, out, t_fly, donors):
    """查 MSPT 尖峰用（调度 11-09）：飞的时候带时间戳的服务器日志（server_env 开了 GC 日志的话也在里面），和 /donor imports 的全部条目
    （每条有主线程导入的三段耗时：解析、查区域含读存档、写入）。拿不到就算了，不影响这一轮。"""
    import json
    out.mkdir(parents=True, exist_ok=True)
    try:
        if hasattr(runner, 'server_log_ts'):
            (out / 'server-log.txt').write_text(''.join(l + '\n' for l in runner.server_log_ts(t_fly)))
        if donors:
            items, total = [], None
            while total is None or len(items) < total:
                page = json.loads(runner.rcon(f'donor imports {len(items)}'))
                total = page.get('total', 0)
                if not page.get('items'):
                    break
                items += page['items']
            (out / 'imports.jsonl').write_text(''.join(json.dumps(i, ensure_ascii=False) + '\n' for i in items))
    except Exception as e:   # noqa: BLE001
        (out / 'diagnostics-error.txt').write_text(repr(e))


def make_flight_driver(runner, controller, flyer, events=None, start=ab.DEFAULT_START, seed=424242, donor_env=None,
                       donor_profiles=None, server_env=None):
    """donor_env：起 donor 时给 compose 的环境变量（演示矩阵里 worker3 提到 4 核）。
    donor_profiles：真实场景矩阵（调度 11-09）的 donor 配置，名字 → {donors: 个数, env: compose 环境变量}，比如 1x8（1 个 8 核的强 donor）、3x4。"""
    events = events or ab._docker_events

    def drive(cell, out) -> dict:
        t_cell = time.time()
        try:
            return _drive(cell, out)
        finally:   # 调度 11-09：不管这一轮成不成，worker 容器的日志先存进归档（下一轮会重建容器）
            _keep_worker_logs(runner, controller, Path(out), t_cell)

    def _drive(cell, out) -> dict:
        p = cell['params']
        donors = p['donors']
        env = donor_env
        profile = p.get('donor_profile')
        if donors and profile:
            if profile not in (donor_profiles or {}):
                raise ValueError(f'不认识的 donor_profile：{profile}（配置里的 donor_profiles 没有）')
            donors = donor_profiles[profile]['donors']
            env = resolve_env({**(donor_env or {}), **donor_profiles[profile].get('env', {})},
                              getattr(runner, 'local_env', lambda k: None))
        if not 0 <= donors <= len(ALL_WORKERS):
            raise ValueError(f'这台机器上最多 {len(ALL_WORKERS)} 个 donor（compose 里只有 {", ".join(ALL_WORKERS)}）：{donors}')
        if p.get('bots', 1) != 1:
            raise ValueError('只支持 bots = 1（两个玩家用 scenario = two_players）')
        out = Path(out)
        cpus = p['server_cpus']
        cpuset = server_cpuset(cpus, runner.slot_cpuset())
        net = p.get('latency_ms', 0) > 0
        t0 = time.time()
        name = f"mx{run_cell_id(cell)}"
        lookahead = p.get('lookahead')
        # server_env：服务器容器的其他设置（调度 11-09"用户同款"：容器 3G、-Xms512M -Xmx2G）
        world_env = {**(server_env or {}), 'MC_CPUS': str(cpus), 'MC_CPUSET': cpuset}
        if lookahead:   # 提前量是服务器的 JVM 参数，换世界重建容器时带上（docs/config.md）；接在 server_env 的后面（比如 GC 日志）
            world_env['MCD_JVM_EXTRA'] = ' '.join(x for x in (world_env.get('MCD_JVM_EXTRA', ''), f'-Dmcdonate.demandLookahead={lookahead}') if x)
        props = {k: p[k] for k in SERVER_PROPS if p.get(k) is not None}   # 0 也传（比如开关关着的那一臂），好核对
        for k, v in props.items():   # 其他 coordinator 配置（扫描预算、corridor、并发），同样是服务器的 JVM 参数
            world_env['MCD_JVM_EXTRA'] = ' '.join(x for x in (world_env.get('MCD_JVM_EXTRA', ''), f'-Dmcdonate.{SERVER_PROPS[k]}={v}') if x)
        runner.script('new_world.sh', name, str(seed), env=world_env, timeout=900)
        if p.get('view_distance'):   # 按用户正式服的视距测（默认是 compose 里的 10）
            runner.rcon(f"bench viewdistance {p['view_distance']}")
        cache = p.get('cache_radius') or 0
        cache_chunks = None
        if cache:   # 缓存区：以起点为中心、半径 cache 个 chunk 的方形先用 Chunky 生成好（用户 11-09：从缓存区飞出去）
            from bench.server import chunky
            cache_chunks = chunky(_LogShim(runner), int(start[0]), int(start[1]), cache * 16, timeout=900, replace_stale=True)
        ids_before = []
        jar = None
        if donors == 0:
            controller.donor_off()
            for f in FEATURES:
                controller.feature(f, False)
        else:
            jar = _check_worker_jar(runner)
            runner.rcon('donor reset')
            controller.donor_on(donors, env=env)
            on = _features_on(p.get('features'))
            for f in FEATURES:
                controller.feature(f, f in on)
            if runner.netem_supported():
                controller.netem(net)
            elif net:
                raise RuntimeError('要模拟网络，但 scripts/netem.sh 还不支持 NETEM_SERVICE 和限速')
            ids_before = controller.worker_ids()
        counts_before = _status_counts(controller) if donors else None
        services = list(controller.active) if donors else []
        cpu_before = runner.worker_cpu(services) if services and hasattr(runner, 'worker_cpu') else None
        scenario = p.get('scenario')
        speed = scenario_speed(p)
        routes = (scenarios.routes(scenario, seed=seed, seconds=p['duration_s'], speed=p.get('speed_bps'), cache_radius_blocks=cache * 16)
                  if scenario else [None])
        start_tick = _server_tick(runner)   # 对 autosave（每 6000 tick）用
        t_fly = time.time()
        # 每次尝试的 run 名不同：中途打断的一轮会在服务器的 bench-runs 里留下同名目录，重试时 bench fly start 会拒绝
        run_tag = f'{time.time_ns() // 1_000_000 % 10**8:08d}'
        dirs = [out / ('flight' if i == 0 else f'flight{i + 1}') for i in range(len(routes))]
        errors = []

        def one(i, route):
            try:
                flyer.fly_sync(f'mx-{name}-{run_tag}-p{i + 1}' if len(routes) > 1 else f'mx-{name}-{run_tag}', start, speed,
                               p['duration_s'], dirs[i], route=route,
                               player=None if i == 0 else f'{flyer.PLAYER}{i + 1}')
            except Exception as e:   # 一个玩家飞失败，这一轮就无效
                errors.append(e)

        threads = [threading.Thread(target=one, args=(i, r), daemon=True) for i, r in enumerate(routes)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        if errors:
            raise errors[0]
        ids_after = controller.worker_ids() if donors else []
        st = controller.status() if props else {}
        used = {k: _prop_used(st, k) for k in props}
        counts_after = _status_counts(controller) if donors else None
        cpu_after = runner.worker_cpu(services) if cpu_before is not None else None
        log_lines = runner.server_log_since(t_fly) if hasattr(runner, 'server_log_since') else []
        _keep_diagnostics(runner, out, t_fly, donors)
        all_judged = [ab._judge(d, donors > 0, cpus, ids_before, ids_after) for d in dirs]
        judged = all_judged[0]
        for j in all_judged[1:]:
            judged['valid'] = judged['valid'] and j['valid']
            judged['invalid'] += [x for x in j['invalid'] if x not in judged['invalid']]
        s = judged['summary']
        if lookahead and str(s.get('demand_lookahead')) != str(lookahead):
            judged['valid'] = False
            judged['invalid'].append(f"提前量没生效：要 {lookahead}，服务器用的是 {s.get('demand_lookahead')}")
        for k, v in props.items():
            if not _prop_matches(v, used[k]):
                judged['valid'] = False
                judged['invalid'].append(f"{SERVER_PROP_NAMES[k]}没生效：要 {v}，服务器用的是 {used[k]}")
        if not judged['valid']:
            raise RuntimeError('这一轮无效：' + '；'.join(judged['invalid']))
        result = {k: _num(s[k]) for k in METRICS if k in s}
        for k in props:
            result[f'{k}_effective'] = used[k]
        if len(all_judged) > 1:   # 两个玩家：卡住、距离取两人的均值，另记最差的那个
            stalls = [j['summary'].get('stall_fraction') for j in all_judged if j['summary'].get('stall_fraction') is not None]
            dists = [j['summary'].get('distance_ratio') for j in all_judged if j['summary'].get('distance_ratio') is not None]
            if stalls:
                result.update(stall_fraction=sum(stalls) / len(stalls), stall_fraction_max=max(stalls))
            if dists:
                result['distance_ratio'] = sum(dists) / len(dists)
        if jar:
            result['worker_jar'] = jar
        result.update(players=len(routes), scenario_route=routes[0], fly_start_unix=t_fly, fly_start_tick=start_tick,
                      cant_keep_up=sum("Can't keep up" in l for l in log_lines),
                      moved_too_quickly=sum('moved too quickly' in l for l in log_lines))
        if cpu_before is not None:
            cores = 0.0
            for svc in services:
                if svc not in cpu_before or svc not in cpu_after:
                    continue   # 这个 donor 的容器飞前或飞后不在
                (t0n, u0), (t1n, u1) = cpu_before[svc], cpu_after[svc]
                cores += (u1 - u0) / 1e6 / ((t1n - t0n) / 1e9) if t1n > t0n else 0
            result['worker_cores'] = cores
        raw = out / 'flight' / 'raw'
        seconds = layer2_stats.load_jsonl(raw / 'seconds.jsonl') if (raw / 'seconds.jsonl').exists() else []
        csv_path = out / 'flight' / 'layer2.csv'
        if csv_path.exists():
            with csv_path.open() as f:
                result['mspt_p95'] = _p95([float(r['mspt_mean']) for r in csv.DictReader(f) if r.get('mspt_mean')])
        else:
            result['mspt_p95'] = _p95([r.get('mspt_mean') for r in seconds])
        prov = layer2_stats.load_jsonl(raw / 'provenance.jsonl') if (raw / 'provenance.jsonl').exists() else []
        leads = layer2_stats.donor_leads(prov, seconds)
        if cache:   # 只看出了缓存区以后
            from analysis.firework import after_exit
            result.update(after_exit(seconds, start, cache * 16, speed), cache_chunks=cache_chunks)
        if counts_before is not None:
            result.update({k: counts_after[k] - counts_before[k] for k in counts_before})
            result['tile_s'] = result.pop('tiles_done') / p['duration_s']
        total = sum(result.get(k) or 0 for k in ('full_generated', 'full_resumed', 'full_loaded'))
        result['resumed_share'] = (result.get('full_resumed') or 0) / total if total else None
        result.update(donor_events=judged['donor_events'], netem=net, valid=True, server_cpuset=cpuset,
                      ahead_total=sum(x >= 0 for x in leads) / len(leads) if leads else None,
                      interference=ab._concurrent(events(t0, time.time()), getattr(runner, 'project', None)))
        return result

    return drive


class _LogShim:
    """bench.server.chunky 要 server.rcon 和 server.logs。"""

    def __init__(self, runner):
        self.rcon = runner.rcon
        self.logs = lambda: '\n'.join(runner.server_log_since(0))


def run_cell_id(cell) -> str:
    """世界名用的短编号：小写十六进制，符合 lib.sh 的世界名规则。"""
    from analysis.run_matrix import cell_id
    return cell_id(cell)[:16]
