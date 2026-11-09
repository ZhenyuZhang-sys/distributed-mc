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


def forward_ready(events, rows, view=10, band=1):
    """前方已就绪距离（调度 11-09）：每秒末，从玩家所在的 chunk 沿飞行方向往前，3 列宽（左右各 band 列）的带子连续就绪了多少格。
    就绪按 chunks.jsonl 的 enter / ready / leave 重放（不在视野里的算没就绪），最多看视距那么远。返回 {t_s: 格}。"""
    import bisect
    evs = sorted(events, key=lambda e: e['t_ms'])
    times = [e['t_ms'] for e in evs]
    state, j, out, prev, d = {}, 0, {}, None, (1.0, 0.0)
    for r in rows:
        t = r['t_s'] * 1000
        k = bisect.bisect_right(times, t)
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
        if prev is not None:
            dx, dz = r['x'] - prev['x'], r['z'] - prev['z']
            n = math.hypot(dx, dz)
            if n > 1e-6:
                d = (dx / n, dz / n)
        prev = r
        px, pz = -d[1], d[0]
        ok = 0
        for step in range(1, view + 1):
            fx, fz = r['x'] + d[0] * 16 * step, r['z'] + d[1] * 16 * step
            cells = {(math.floor((fx + px * 16 * o) / 16), math.floor((fz + pz * 16 * o) / 16)) for o in range(-band, band + 1)}
            if all(state.get(c) for c in cells):
                ok = step
            else:
                break
        out[r['t_s']] = ok * 16
    return out


def forward_summary(per, rows, start, radius):
    i = _exit_index(rows, start, radius)
    if i is None:
        return dict(forward_ready_median=None, forward_ready_min=None)
    vals = sorted(per[r['t_s']] for r in rows[i + 1:] if r['t_s'] in per)
    if not vals:
        return dict(forward_ready_median=None, forward_ready_min=None)
    return dict(forward_ready_median=vals[len(vals) // 2], forward_ready_min=vals[0])


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
        out.update(forward_summary(forward_ready(ev, rows, view=params.get('view_distance') or 10), rows, DEFAULT_START, radius))
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


def _cfg(r):
    return PROFILE_NAME.get(r.get('donor_profile'), r.get('donor_profile')) if r.get('donors') else PROFILE_NAME[None]


def _f(v, pct=False, digits=1):
    if v is None or v == '':
        return '—'
    if isinstance(v, str):
        return v
    return f'{v * 100:.0f}%' if pct else (f'{v:.{digits}f}' if isinstance(v, float) else str(v))


def _mean(rs, k):
    vals = [r[k] for r in rs if isinstance(r.get(k), (int, float)) and not isinstance(r.get(k), bool)]
    return sum(vals) / len(vals) if vals else None


def report(rows, note='演示，粗测：这台机器上按 N100 限核，每种配置 1 次，可能有别的测试并行') -> str:
    """烟花鞘翅第一段的结果表（markdown）。第一张表：能扛住的最高速度（原版、各 donor 配置，调度要放在最前面）；
    第二张：33.5 格/秒（真实烟花速度）下直线和转弯的每轮指标。只看出了缓存区以后。"""
    fw = [r for r in rows if r.get('experiment', 'flight') == 'flight' and r.get('scenario') in SCEN_NAME]
    sweep = [dict(r, config=_cfg(r)) for r in fw if r.get('scenario') == 'firework' and r.get('speed_bps') is not None]
    best = max_sustainable(sweep, limit=0.05)
    order = [PROFILE_NAME[None], PROFILE_NAME['1x8'], PROFILE_NAME['1x4'], PROFILE_NAME['2x4'], PROFILE_NAME['3x4']]
    names = [n for n in order if n in best] + [n for n in best if n not in order]
    out = [f'> {note}', '',
           '| 配置 | 能扛住的最高速度（格/秒，出缓存区后卡住 < 5%） | 各速度下出缓存区后的卡住占比 |', '| --- | --- | --- |']
    for n in names:
        per = sorted((r['speed_bps'], r.get('stall_after_exit')) for r in sweep if r['config'] == n)
        out.append(f"| {n} | **{_f(best[n])}** | " + '，'.join(f'{_f(v, digits=1) if isinstance(v, float) else v}: {_f(s, True)}' for v, s in per) + ' |')
    real = [r for r in fw if r.get('speed_bps') in (None, 33.5)]
    out += ['', '真实烟花速度（33.5 格/秒）下，出了缓存区以后：', '',
            '| 场景 | 配置 | 卡住占比 | 前方已就绪（中位 / 最低，格） | 视距圈就绪（平均 / 最低） | 实际 / 理想速度 | 第一次卡住（出去后几秒） | MSPT p95 / max | Can\'t keep up | moved too quickly | 接着做完的占比（donor 导入或缓存区边上的半成品） | 丢弃：来晚 / 碰到服务器 / 过时 | 导入主线程（ms） | worker 核数 | tile/s |',
            '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |']
    for scen in ('firework', 'firework_turning'):
        for n in order:
            rs = [r for r in real if r.get('scenario') == scen and _cfg(r) == n]
            if not rs:
                continue
            out.append(f"| {SCEN_NAME[scen]} | {n} | {_f(_mean(rs, 'stall_after_exit'), True)} "
                       f"| {_f(_mean(rs, 'forward_ready_median'), digits=0)} / {_f(_mean(rs, 'forward_ready_min'), digits=0)} "
                       f"| {_f(_mean(rs, 'view_ready_mean'), True)} / {_f(_mean(rs, 'view_ready_min'), True)} "
                       f"| {_f(_mean(rs, 'speed_ratio_after_exit'), True)} "
                       f"| {_f(_mean(rs, 'first_stall_after_exit_s'), digits=0)} | {_f(_mean(rs, 'mspt_p95'))} / {_f(_mean(rs, 'mspt_max'), digits=0)} "
                       f"| {_f(_mean(rs, 'cant_keep_up'), digits=0)} | {_f(_mean(rs, 'moved_too_quickly'), digits=0)} | {_f(_mean(rs, 'resumed_share'), True)} "
                       f"| {_f(_mean(rs, 'discard_late'), digits=0)} / {_f(_mean(rs, 'discard_server'), digits=0)} / {_f(_mean(rs, 'stale_dropped'), digits=0)} "
                       f"| {_f(_mean(rs, 'import_ms'), digits=0)} | {_f(_mean(rs, 'worker_cores'))} | {_f(_mean(rs, 'tile_s'), digits=2)} |")
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
    rows = [r for c in a.campaigns for r in rows_recomputed(Path(c))]
    md = report(rows)
    if a.out:
        Path(a.out).write_text(md)
    sys.stdout.write(md)
