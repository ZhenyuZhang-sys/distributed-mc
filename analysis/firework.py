"""最狠的场景（用户 11-09）：烟花鞘翅从缓存区（预先生成好的区域）中心飞出去，只看出了缓存区以后这一段。纯函数。

- after_exit：从逐秒行（seconds.jsonl：t_s、x、z、ticks、stalled_ticks）算出缓存区的时刻、之后的卡住占比、实际速度 / 理想速度
  （按走过的路程，转弯也对）、出去以后第几秒第一次卡住。缓存区是以起点为中心、边长 2 × radius 格的方形（Chunky 的 square）。
- max_sustainable：能扛住的最高速度。每种配置按速度从低到高看，卡住占比 < limit 的连续通过的最高那个；最低速度就不过是 None。
"""
from __future__ import annotations

import math


def after_exit(rows, start, radius, speed):
    sx, sz = start
    exit_i = next((i for i, r in enumerate(rows) if max(abs(r['x'] - sx), abs(r['z'] - sz)) > radius), None)
    out = dict(exit_s=None, stall_after_exit=None, speed_ratio_after_exit=None, first_stall_after_exit_s=None, seconds_after_exit=0)
    if exit_i is None:
        return out
    exit_s = rows[exit_i]['t_s']
    after = rows[exit_i + 1:]
    ticks = sum(r.get('ticks', 0) for r in after)
    stalled = sum(r.get('stalled_ticks', 0) for r in after)
    path = sum(math.hypot(b['x'] - a['x'], b['z'] - a['z']) for a, b in zip(rows[exit_i:], rows[exit_i + 1:]))
    span = (rows[-1]['t_s'] - exit_s) if after else 0
    first = next((r['t_s'] - exit_s for r in after if r.get('stalled_ticks', 0) > 0), None)
    out.update(exit_s=exit_s, stall_after_exit=stalled / ticks if ticks else None,
               speed_ratio_after_exit=path / (speed * span) if span and speed else None,
               first_stall_after_exit_s=first, seconds_after_exit=span)
    return out


def _exit_index(rows, start, radius):
    sx, sz = start
    return next((i for i, r in enumerate(rows) if max(abs(r['x'] - sx), abs(r['z'] - sz)) > radius), None)


def view_ready(rows, start, radius):
    """视距圈就绪率（调度 11-09）：出缓存区以后每秒视距内就绪的比例（1 − void_rate），平均和最低。"""
    i = _exit_index(rows, start, radius)
    vals = [1 - r['void_rate'] for r in (rows[i + 1:] if i is not None else []) if r.get('void_rate') is not None]
    return dict(view_ready_mean=sum(vals) / len(vals) if vals else None, view_ready_min=min(vals) if vals else None)


def in_view(cx, cz, r, x, z):
    """原版 ChunkTrackingView.of((cx, cz), r).contains(x, z)（含视野边上那一圈，视距 10 时 453 个）。"""
    i, j = max(0, abs(x - cx) - 1), max(0, abs(z - cz) - 1)
    l, m = max(0, max(i, j) - 1), min(i, j)
    return m * m + l * l < r * r


def _initial_state(run, initial, view):
    """起飞时视野里每个 chunk 的就绪状态（codex-audit K1：初始视野不进事件流）。有 initial.jsonl 按它；
    没有时 run.json 的 initial.not_ready = 0 就是起点 chunk 的原版视野全就绪；有没就绪的又没有逐 chunk 记录：None（算不了）。
    run 也没有（老测试）：空表。"""
    if initial is not None:
        return {(c['cx'], c['cz']): bool(c['ready']) for c in initial}
    if run is None:
        return {}
    init = run.get('initial') or {}
    if init.get('not_ready', 0) != 0:
        return None
    cx0, cz0 = math.floor(run['start_x'] / 16), math.floor(run['start_z'] / 16)
    r = run.get('view_distance') or view
    return {(x, z): True for x in range(cx0 - r - 1, cx0 + r + 2) for z in range(cz0 - r - 1, cz0 + r + 2) if in_view(cx0, cz0, r, x, z)}


def _route_heading(run):
    """route 模式（codex-audit K2）：起飞后 t 秒该朝哪（按航段和时刻），被挡住不动时也对。不是 route：None。"""
    if not run or run.get('mode') != 'route' or not run.get('route'):
        return None
    segs = []
    for part in run['route'].split(';'):
        yaw, _, sec = (float(v) for v in part.split(','))
        rad = math.radians(yaw)
        segs.append((float(sec), (-math.sin(rad), math.cos(rad))))

    def at(t):
        end = 0.0
        for sec, d in segs:
            end += sec
            if t < end:
                return d
        return segs[-1][1]
    return at


def _replay(events, rows, run=None, initial=None, view=10):
    """按 chunks.jsonl 的 enter / ready / leave 重放：每秒末给出 (行, 飞行方向, 就绪表)。就绪表只留当时视野里的 chunk
    （初始视野离开时不发 leave，按原版视野形状剪掉）。初始状态见 _initial_state；算不了时什么都不给。
    方向：route 模式按航段，否则按这一秒的位移（不动时沿用上一个）。"""
    import bisect
    state = _initial_state(run, initial, view)
    if state is None:
        return
    heading = _route_heading(run)
    r = (run or {}).get('view_distance') or view
    evs = sorted(events, key=lambda e: e['t_ms'])
    times = [e['t_ms'] for e in evs]
    j, prev, d = 0, None, (1.0, 0.0)
    for row in rows:
        k = bisect.bisect_right(times, row['t_s'] * 1000)
        for e in evs[j:k]:
            key = (e['cx'], e['cz'])
            if e['ev'] == 'enter':
                state[key] = state.get(key, False)
            elif e['ev'] == 'ready':
                if key in state:
                    state[key] = True
            else:
                state.pop(key, None)
        j = k
        cx, cz = math.floor(row['x'] / 16), math.floor(row['z'] / 16)
        for key in [key for key in state if not in_view(cx, cz, r, *key)]:
            del state[key]
        if heading is not None:
            d = heading(row['t_s'])
        elif prev is not None:
            dx, dz = row['x'] - prev['x'], row['z'] - prev['z']
            n = math.hypot(dx, dz)
            if n > 1e-6:
                d = (dx / n, dz / n)
        prev = row
        yield row, d, state


def first_unready(ready, x0, z0, d, reach):
    """从 (x0, z0) 沿方向 d 走（DDA，逐个经过的 chunk，正好穿过角时两边相邻的都算），第一个没就绪的 chunk 的边界离起点多远，
    最多 reach。起点所在的 chunk 不查（codex-audit K3：原来每 16 格采一个点，斜飞会漏掉擦过的 chunk）。"""
    eps = 1e-9
    cx, cz = math.floor(x0 / 16), math.floor(z0 / 16)
    sx = 1 if d[0] > 0 else -1
    sz = 1 if d[1] > 0 else -1
    inf = float('inf')
    tx = ((cx + (sx > 0)) * 16 - x0) / d[0] if abs(d[0]) > eps else inf
    tz = ((cz + (sz > 0)) * 16 - z0) / d[1] if abs(d[1]) > eps else inf
    dtx = 16 / abs(d[0]) if abs(d[0]) > eps else inf
    dtz = 16 / abs(d[1]) if abs(d[1]) > eps else inf
    while True:
        t = min(tx, tz)
        if t > reach:
            return reach
        if abs(tx - tz) < 1e-7:   # 正好穿过角：两边相邻的也擦过
            if not ready((cx + sx, cz)) or not ready((cx, cz + sz)):
                return t
            cx, cz, tx, tz = cx + sx, cz + sz, tx + dtx, tz + dtz
        elif tx < tz:
            cx, tx = cx + sx, tx + dtx
        else:
            cz, tz = cz + sz, tz + dtz
        if not ready((cx, cz)):
            return t


def forward_ready(events, rows, view=10, band=1, run=None, initial=None):
    """前方已就绪距离（调度 11-09，codex-audit K3 改成 DDA）：每秒末，从玩家位置沿飞行方向的中心线、和左右各 16 × band 格的平行线走，
    碰到第一个没就绪的 chunk 有多远（格），取最近的那条，最多视距（view × 16 格）。返回 {t_s: 格}；初始状态算不了时 None。"""
    out = {}
    have = False
    for r, d, state in _replay(events, rows, run, initial, view):
        have = True
        px, pz = -d[1], d[0]
        ready = lambda c: bool(state.get(c))   # noqa: E731
        out[r['t_s']] = min(first_unready(ready, r['x'] + px * 16 * o, r['z'] + pz * 16 * o, d, view * 16)
                            for o in range(-band, band + 1))
    return out if have or not rows else None


def front_sector_ready(events, rows, view=10, half_angle=45, run=None, initial=None):
    """前方 90 度扇形就绪率（调度 11-09）：每秒末，chunk 中心在飞行方向左右各 half_angle 度以内、离玩家不超过视距（view × 16 格）的
    chunk 里，就绪的比例。玩家脚下那个不算。返回 {t_s: 比例}；初始状态算不了时 None。"""
    cos_a = math.cos(math.radians(half_angle))
    out = {}
    have = False
    for r, d, state in _replay(events, rows, run, initial, view):
        have = True
        x, z, reach = r['x'], r['z'], view * 16
        cx0, cz0 = math.floor(x / 16), math.floor(z / 16)
        n = ok = 0
        for cx in range(cx0 - view - 1, cx0 + view + 2):
            for cz in range(cz0 - view - 1, cz0 + view + 2):
                if (cx, cz) == (cx0, cz0):
                    continue
                rx, rz = cx * 16 + 8 - x, cz * 16 + 8 - z
                dist = math.hypot(rx, rz)
                if dist == 0 or dist > reach + 1e-9 or rx * d[0] + rz * d[1] < cos_a * dist - 1e-9:
                    continue
                n += 1
                ok += bool(state.get((cx, cz)))
        out[r['t_s']] = ok / n if n else None
    return out if have or not rows else None


def sector_summary(per, rows, start, radius):
    i = _exit_index(rows, start, radius)
    vals = [per[r['t_s']] for r in (rows[i + 1:] if i is not None and per else []) if per.get(r['t_s']) is not None]
    return dict(front_sector_ready_mean=sum(vals) / len(vals) if vals else None, front_sector_ready_min=min(vals) if vals else None)


def forward_summary(per, rows, start, radius):
    i = _exit_index(rows, start, radius)
    if i is None or per is None:
        return dict(forward_ready_median=None, forward_ready_min=None)
    vals = sorted(per[r['t_s']] for r in rows[i + 1:] if r['t_s'] in per)
    if not vals:
        return dict(forward_ready_median=None, forward_ready_min=None)
    return dict(forward_ready_median=vals[len(vals) // 2], forward_ready_min=vals[0])


AUTOSAVE_TICKS = 6000


def autosave_seconds(rows, start_tick, every=AUTOSAVE_TICKS):
    """哪几秒里有 autosave（服务器 tick 数到 every 的整数倍；sync-chunk-writes=true 时存盘同步写在主线程）。
    start_tick：起飞时的 tick 数（驱动的 fly_start_tick），按逐秒行的 ticks 累加。"""
    if start_tick is None:
        return []
    out, t = [], start_tick
    for r in rows:
        nt = t + r.get('ticks', 0)
        if nt // every > t // every:
            out.append(r['t_s'])
        t = nt
    return out


def max_sustainable(runs, limit=0.05, key='config'):
    """runs：[{config, speed_bps, stall_after_exit}]。返回 {config: 能扛住的最高速度或 None}。"""
    by = {}
    for r in runs:
        by.setdefault(r[key], []).append(r)
    out = {}
    for name, rs in by.items():
        best = None
        for r in sorted(rs, key=lambda r: r['speed_bps']):
            s = r.get('stall_after_exit')
            if s is None or s >= limit:
                break
            best = r['speed_bps']
        out[name] = best
    return out


def recompute(attempt_dir, params) -> dict:
    """按归档的逐秒行重算出缓存区以后的指标（报告用；11-09 第一轮的速度比按错的速度算过）。"""
    import json
    from pathlib import Path
    from analysis.flight_driver import scenario_speed
    from bench.demo.ab import DEFAULT_START
    raw = Path(attempt_dir) / 'flight' / 'raw'
    rows = [json.loads(l) for l in (raw / 'seconds.jsonl').read_text().splitlines() if l.strip()]
    radius = (params.get('cache_radius') or 0) * 16
    out = after_exit(rows, DEFAULT_START, radius, scenario_speed(params))
    out.update(view_ready(rows, DEFAULT_START, radius))
    if (raw / 'chunks.jsonl').exists():
        ev = [json.loads(l) for l in (raw / 'chunks.jsonl').read_text().splitlines() if l.strip()]
        view = params.get('view_distance') or 10
        run = json.loads((raw / 'run.json').read_text()) if (raw / 'run.json').exists() else None
        initial = ([json.loads(l) for l in (raw / 'initial.jsonl').read_text().splitlines() if l.strip()]
                   if (raw / 'initial.jsonl').exists() else None)
        out.update(forward_summary(forward_ready(ev, rows, view=view, run=run, initial=initial), rows, DEFAULT_START, radius))
        out.update(sector_summary(front_sector_ready(ev, rows, view=view, run=run, initial=initial), rows, DEFAULT_START, radius))
    return out


def rows_recomputed(campaign) -> list[dict]:
    """matrix_summary.collect 的行，出缓存区以后的指标按归档重算（每个单元取通过的那次尝试）。"""
    import json
    from pathlib import Path
    from analysis.matrix_summary import collect
    rows = collect(campaign)
    by_cell = {}
    for cdir in Path(campaign).iterdir():
        for a in sorted(cdir.glob('attempt-*')):
            m = a / 'manifest.json'
            if m.exists() and json.loads(m.read_text()).get('status') == 'passed' and (a / 'flight' / 'raw' / 'seconds.jsonl').exists():
                by_cell[cdir.name] = a
    for r in rows:
        a = by_cell.get(r['cell_id'])
        if a is not None and r.get('cache_radius'):
            r.update(recompute(a, r))
    return rows


PROFILE_NAME = {None: '原版（0 个 donor）', '': '原版（0 个 donor）', '1x8': '1 个 donor，8 核', '1x4': '1 个 donor，4 核',
                '2x4': '2 个 donor，各 4 核', '3x4': '3 个 donor，各 4 核'}
SCEN_NAME = {'firework': '直线', 'firework_turning': '转弯'}


ORDER = [PROFILE_NAME[None], PROFILE_NAME['1x8'], PROFILE_NAME['1x4'], PROFILE_NAME['2x4'], PROFILE_NAME['3x4']]


def _cfg(r):
    """配置名；行带 code（哪套代码，三臂对比用）时后面标上。"""
    base = PROFILE_NAME.get(r.get('donor_profile'), r.get('donor_profile')) if r.get('donors') else PROFILE_NAME[None]
    return f"{base} · {r['code']}" if r.get('code') else base


def _order(names):
    """原版在前，再按 donor 配置、代码版本。"""
    def k(n):
        base, _, code = n.partition(' · ')
        return (ORDER.index(base) if base in ORDER else len(ORDER), base, code)
    return sorted(set(names), key=k)


def parse_campaign_arg(a):
    """命令行的 campaign 参数：目录，或 目录=代码版本。"""
    path, eq, label = a.rpartition('=')
    return (path, label) if eq and label and '/' not in label else (a, None)


def _f(v, pct=False, digits=1):
    if v is None or v == '':
        return '—'
    if isinstance(v, str):
        return v
    return f'{v * 100:.0f}%' if pct else (f'{v:.{digits}f}' if isinstance(v, float) else str(v))


def _mean(rs, k):
    vals = [r[k] for r in rs if isinstance(r.get(k), (int, float)) and not isinstance(r.get(k), bool)]
    return sum(vals) / len(vals) if vals else None


def _median(vals):
    vals = sorted(v for v in vals if isinstance(v, (int, float)) and not isinstance(v, bool))
    if not vals:
        return None
    n = len(vals)
    return vals[n // 2] if n % 2 else (vals[n // 2 - 1] + vals[n // 2]) / 2


def autosave_from(rows, run, result):
    """哪几秒里有 autosave：起点用 run.json 的 start_tick（起飞那一刻，codex-audit K8），没有再用驱动的 fly_start_tick（偏早，等初始视野之前）。"""
    start = (run or {}).get('start_tick')
    if start is None or start < 0:
        start = (result or {}).get('fly_start_tick')
    return autosave_seconds(rows, start)


def _stat(rs, k, pct=False, digits=1):
    """一轮：就是那个数；多轮（调度 11-09：关键对比每臂至少 3 轮）：中位数（最低～最高，几轮）。"""
    vals = sorted(r[k] for r in rs if isinstance(r.get(k), (int, float)) and not isinstance(r.get(k), bool))
    if len(vals) < 2:
        return _f(vals[0] if vals else None, pct, digits)
    n = len(vals)
    med = vals[n // 2] if n % 2 else (vals[n // 2 - 1] + vals[n // 2]) / 2
    return f'{_f(float(med), pct, digits)}（{_f(float(vals[0]), pct, digits)}～{_f(float(vals[-1]), pct, digits)}，{n} 轮）'


def report(rows, note='演示，粗测：这台机器上按 N100 限核，每种配置 1 次，可能有别的测试并行') -> str:
    """烟花鞘翅第一段的结果表（markdown）。第一张表：能扛住的最高速度（原版、各 donor 配置，调度要放在最前面）；
    第二张：33.5 格/秒（真实烟花速度）下直线和转弯的每轮指标。只看出了缓存区以后。"""
    fw = [r for r in rows if r.get('experiment', 'flight') == 'flight' and r.get('scenario') in SCEN_NAME]
    sweep = [dict(r, config=_cfg(r)) for r in fw if r.get('scenario') == 'firework' and r.get('speed_bps') is not None]
    best = max_sustainable(sweep, limit=0.05)
    names = _order(best)
    order = _order(_cfg(r) for r in fw)
    out = [f'> {note}', '',
           '| 配置 | 能扛住的最高速度（格/秒，出缓存区后卡住 < 5%） | 各速度下出缓存区后的卡住占比 |', '| --- | --- | --- |']
    for n in names:
        per = sorted((r['speed_bps'], r.get('stall_after_exit')) for r in sweep if r['config'] == n)
        out.append(f"| {n} | **{_f(best[n])}** | " + '，'.join(f'{_f(v, digits=1) if isinstance(v, float) else v}: {_f(s, True)}' for v, s in per) + ' |')
    demo = [r for r in sweep if r['speed_bps'] in (10, 15)]
    if demo:   # 调度 11-09：演示用这组；codex-audit K9：标题不先下结论，结论句按同条件重复样本的中位数算
        out += ['', '## 演示用：10/15 格每秒对照', '',
                '| 速度（格/秒） | 配置 | 卡住占比 | 前方已就绪距离（中位，格） | 视距圈就绪（平均） | 前方 90 度扇形就绪（平均） |',
                '| --- | --- | --- | --- | --- | --- |']
        notes = []
        for v in (10, 15):
            here = [n for n in names if any(r['speed_bps'] == v and r['config'] == n for r in demo)]
            for n in here:
                rs = [r for r in demo if r['speed_bps'] == v and r['config'] == n]
                out.append(f"| {v} | {n} | {_stat(rs, 'stall_after_exit', True)} | {_stat(rs, 'forward_ready_median', digits=0)} "
                           f"| {_stat(rs, 'view_ready_mean', True)} | {_stat(rs, 'front_sector_ready_mean', True)} |")
            if len(here) < 2:
                continue
            by = {n: [r for r in demo if r['speed_bps'] == v and r['config'] == n] for n in here}
            k = min(len(rs) for rs in by.values())
            stalls = {n: _median([r.get('stall_after_exit') for r in rs]) for n, rs in by.items()}
            parts = []
            if all(x is not None and x < .05 for x in stalls.values()):
                parts.append(('两种配置' if len(here) == 2 else '各配置') + '都不卡（卡住 < 5%）')
            else:
                parts += [f'{n} 卡住 {_f(x, True)}' for n, x in stalls.items() if x is None or x >= .05]
            base = next((n for n in here if n.startswith('原版')), None)
            fb = _median([r.get('forward_ready_median') for r in by[base]]) if base else None
            for n in here:
                fn = _median([r.get('forward_ready_median') for r in by[n]])
                if n != base and fb and fn is not None:
                    parts.append(f'前方已就绪距离 {n} 是原版的 {fn / fb:.1f} 倍')
            notes.append(f"- {v} 格/秒：" + '；'.join(parts) + ('（各 1 轮，只能当粗测）' if k < 2 else f'（中位数，各 {k} 轮）'))
        if notes:
            out += [''] + notes
    real = [r for r in fw if r.get('speed_bps') in (None, 33.5)]
    out += ['', '真实烟花速度（33.5 格/秒）下，出了缓存区以后：', '',
            '| 场景 | 配置 | 卡住占比 | 前方已就绪（中位 / 最低，格） | 视距圈就绪（平均 / 最低） | 前方 90 度扇形就绪（平均 / 最低） | 实际 / 理想速度 | 第一次卡住（出去后几秒） | MSPT p95 / max | Can\'t keep up | moved too quickly | 接着做完的占比（donor 导入或缓存区边上的半成品） | 丢弃：来晚 / 碰到服务器 / 过时 | 导入主线程（ms） | worker 核数 | tile/s |',
            '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |']
    for scen in ('firework', 'firework_turning'):
        for n in order:
            rs = [r for r in real if r.get('scenario') == scen and _cfg(r) == n]
            if not rs:
                continue
            out.append(f"| {SCEN_NAME[scen]} | {n} | {_stat(rs, 'stall_after_exit', True)} "
                       f"| {_f(_mean(rs, 'forward_ready_median'), digits=0)} / {_f(_mean(rs, 'forward_ready_min'), digits=0)} "
                       f"| {_stat(rs, 'view_ready_mean', True)} / {_f(_mean(rs, 'view_ready_min'), True)} "
                       f"| {_stat(rs, 'front_sector_ready_mean', True)} / {_f(_mean(rs, 'front_sector_ready_min'), True)} "
                       f"| {_stat(rs, 'speed_ratio_after_exit', True)} "
                       f"| {_f(_mean(rs, 'first_stall_after_exit_s'), digits=0)} | {_f(_mean(rs, 'mspt_p95'))} / {_stat(rs, 'mspt_max', digits=0)} "
                       f"| {_f(_mean(rs, 'cant_keep_up'), digits=0)} | {_f(_mean(rs, 'moved_too_quickly'), digits=0)} | {_f(_mean(rs, 'resumed_share'), True)} "
                       f"| {_f(_mean(rs, 'discard_late'), digits=0)} / {_f(_mean(rs, 'discard_server'), digits=0)} / {_f(_mean(rs, 'stale_dropped'), digits=0)} "
                       f"| {_f(_mean(rs, 'import_ms'), digits=0)} | {_f(_mean(rs, 'worker_cores'))} | {_f(_mean(rs, 'tile_s'), digits=2)} |")
    if any(r.get('slot') for r in real):   # 调度 11-09：同配置在不同槽位差得多（核分配、超线程、同机负载），每轮记槽位
        out += ['', '## 每轮明细', '', '| 场景 | 配置 | 轮 | 槽位 | 卡住占比 | worker 核数 | tile/s | MSPT max |', '| --- | --- | --- | --- | --- | --- | --- | --- |']
        for scen in ('firework', 'firework_turning'):
            for n in order:
                for r in sorted((r for r in real if r.get('scenario') == scen and _cfg(r) == n), key=lambda r: (r.get('slot') or '', r.get('repeat') or 0)):
                    out.append(f"| {SCEN_NAME[scen]} | {n} | {r.get('repeat', '—')} | {r.get('slot') or '—'} | {_f(r.get('stall_after_exit'), True)} "
                               f"| {_f(r.get('worker_cores'))} | {_f(r.get('tile_s'), digits=2)} | {_f(r.get('mspt_max'), digits=0)} |")
    return '\n'.join(out) + '\n'


if __name__ == '__main__':
    import argparse
    import sys
    from pathlib import Path
    from analysis.matrix_summary import collect
    ap = argparse.ArgumentParser(description='烟花鞘翅结果表：python -m analysis.firework <campaign 目录>...')
    ap.add_argument('campaigns', nargs='+')
    ap.add_argument('--out')
    a = ap.parse_args()
    rows = []
    for c in a.campaigns:
        path, code = parse_campaign_arg(c)
        rows += [dict(r, code=code) if code else r for r in rows_recomputed(Path(path))]
    md = report(rows)
    if a.out:
        Path(a.out).write_text(md)
    sys.stdout.write(md)
