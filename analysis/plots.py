"""第 10 步：只读汇总表画图（docs/step10-plan.md 第 4 节）。不读原始归档、不起服务器。

读 campaign 目录里的 matrix-summary.csv、matrix-runs.csv、x-by-n.csv，以及第 1 步的
analysis/results/layer1-summary.csv、determinism.csv（图 10）。缺某种实验的数据时，那张图画"无数据"和原因。

配色：参考配色（dataviz 技能 references/palette.md）的前 4 个类别槽，按固定顺序分配，不循环。
这台机器没有 node，没重新跑 validate_palette.js；用的是那份文件里已校验过的原值（相邻配对通过）。

用法：python -m analysis.plots --campaign analysis/results/runs/matrix-smoke
"""
from __future__ import annotations

import argparse
import json
import csv
import math
from pathlib import Path

import matplotlib

matplotlib.use('Agg')
import matplotlib.pyplot as plt  # noqa: E402

from bench.models import ROOT  # noqa: E402

FIGURES = ('fig01_void_latency_vs_donors', 'fig02_cpu_mspt_vs_donors', 'fig03_capacity', 'fig04_ablation',
           'fig05_tile_size', 'fig06_latency', 'fig07_detection_cost', 'fig08_chunks_before_blacklist',
           'fig09_frametime', 'fig10_baseline', 'fig11_x_vs_n')
SERIES = ('#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4')
SURFACE, INK, INK2, MUTED, GRID, AXIS = '#fcfcfb', '#0b0b0b', '#52514e', '#898781', '#e1e0d9', '#c3c2b7'
SPEED_ORDER = ('walk', 'horse', 'elytra')
SPEED_NAME = {'walk': '走路', 'horse': '骑马', 'elytra': '鞘翅'}
X_TARGET = 2.0
MSPT_LIMIT = 50.0


def _style():
    plt.rcParams.update({
        'font.family': ['Noto Sans CJK JP', 'Droid Sans Fallback', 'DejaVu Sans'],
        'axes.unicode_minus': False, 'figure.facecolor': SURFACE, 'axes.facecolor': SURFACE,
        'axes.edgecolor': AXIS, 'axes.labelcolor': INK2, 'axes.titlecolor': INK, 'text.color': INK,
        'xtick.color': MUTED, 'ytick.color': MUTED, 'axes.grid': True, 'grid.color': GRID, 'grid.linewidth': 0.6,
        'axes.spines.top': False, 'axes.spines.right': False, 'lines.linewidth': 2, 'lines.markersize': 6,
        'legend.frameon': False, 'font.size': 10, 'axes.titlesize': 11,
    })


def _value(s):
    if s is None or s == '':
        return None
    if s in ('True', 'False'):
        return s == 'True'
    try:
        v = float(s)
    except ValueError:
        return s
    return int(v) if v.is_integer() and 'e' not in s.lower() and '.' not in s and 'inf' not in s.lower() else v


def read(path) -> list[dict]:
    path = Path(path)
    if not path.exists():
        return []
    with path.open() as f:
        return [{k: _value(v) for k, v in r.items()} for r in csv.DictReader(f) if 'empty' not in r]


def _ok(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)


def _err(rows, metric):
    """t 区间换成误差棒的上下长度；没有区间就是 0。"""
    lo = [r[metric + '.mean'] - r[metric + '.ci_low'] if _ok(r.get(metric + '.ci_low')) else 0 for r in rows]
    hi = [r[metric + '.ci_high'] - r[metric + '.mean'] if _ok(r.get(metric + '.ci_high')) else 0 for r in rows]
    return [lo, hi]


def _save(fig, out, name):
    fig.tight_layout()
    fig.savefig(out / f'{name}.png', dpi=150)
    plt.close(fig)
    return 'ok'


# 这一次 plot_all 里每张"无数据"图的原因，最后写进 figures/no-data.json（报告引用）
_REASONS = {}
STEP8_PAUSED = '第 8 步暂停（用户 11-09 决定），没有 verify 实验'


def _no_data(out, name, reason):
    _REASONS[name] = reason
    fig, ax = plt.subplots(figsize=(6, 2.4))
    ax.axis('off')
    ax.text(0.5, 0.5, f'无数据\n{reason}', ha='center', va='center', color=MUTED, fontsize=11, transform=ax.transAxes)
    _save(fig, out, name)
    return '无数据'


def _flight(summary, **fixed):
    out = []
    for r in summary:
        if r.get('experiment') != 'flight':
            continue
        r = dict(r)
        r.setdefault('latency_ms', 0)
        r['latency_ms'] = r['latency_ms'] or 0
        r['tile_size'] = r.get('tile_size') or 4
        r['bots'] = r.get('bots') or 1
        r['features'] = r.get('features') or 'v0'
        if all(r.get(k) == v for k, v in fixed.items()):
            out.append(r)
    return out


def _pick_features(rows):
    return 'all' if any(r['features'] == 'all' for r in rows if r['donors'] > 0) else 'v0'


def _by_donors(summary):
    rows = _flight(summary, server_cpus=2, latency_ms=0, bots=1, tile_size=4)
    feats = _pick_features(rows)
    return [r for r in rows if r['donors'] == 0 or r['features'] == feats], feats


def _integer_ticks(ax, rows, key):
    """横轴只标数据里实际有的整数（donor 数），不出现 0.5 这种刻度。"""
    ticks = sorted({r[key] for r in rows if _ok(r.get(key))})
    if ticks:
        ax.set_xticks(ticks)


def _speed_lines(ax, rows, metric, ylabel, scale=1.0):
    for i, speed in enumerate(s for s in SPEED_ORDER if any(r['speed'] == s for r in rows)):
        rs = sorted((r for r in rows if r['speed'] == speed and _ok(r.get(metric + '.mean'))), key=lambda r: r['donors'])
        if not rs:
            continue
        err = [[e * scale for e in side] for side in _err(rs, metric)]
        ax.errorbar([r['donors'] for r in rs], [r[metric + '.mean'] * scale for r in rs], yerr=err, color=SERIES[i],
                    marker='o', capsize=3, label=SPEED_NAME[speed])
    _integer_ticks(ax, rows, 'donors')
    ax.set_xlabel('donor 数')
    ax.set_ylabel(ylabel)


def fig01(d, out, name):
    rows, feats = _by_donors(d['summary'])
    if not rows:
        return _no_data(out, name, '没有 2 核、0 延迟的 flight 结果')
    fig, (a, b) = plt.subplots(1, 2, figsize=(10, 4))
    _speed_lines(a, rows, 'void_mean', '虚空率均值（%）', 100)
    _speed_lines(b, rows, 'latency_p95_ms', '就绪延迟 p95（秒）', 1 / 1000)
    a.set_title('虚空率 vs donor 数')
    b.set_title('就绪延迟 p95 vs donor 数')
    a.legend()
    fig.suptitle(f'2 核服务器，1 个假玩家，开关 {feats}；误差棒为 95% 区间', color=INK2, fontsize=9)
    return _save(fig, out, name)


def fig02(d, out, name):
    rows, feats = _by_donors(d['summary'])
    if not rows:
        return _no_data(out, name, '没有 2 核、0 延迟的 flight 结果')
    fig, (a, b) = plt.subplots(1, 2, figsize=(10, 4))
    _speed_lines(a, rows, 'cpu_cores_mean', '服务器 CPU（核）')
    _speed_lines(b, rows, 'mspt_p95', 'MSPT p95（毫秒）')
    b.axhline(MSPT_LIMIT, color=MUTED, linestyle='--', linewidth=1)
    b.text(b.get_xlim()[1], MSPT_LIMIT, ' 50 ms', color=MUTED, va='center', fontsize=8)
    a.set_title('服务器 CPU vs donor 数')
    b.set_title('MSPT vs donor 数')
    a.legend()
    return _save(fig, out, name)


def fig03(d, out, name):
    rows = [r for r in d['summary'] if r.get('experiment') == 'capacity' and _ok(r.get('capacity_bots.mean'))]
    if not rows:
        return _no_data(out, name, '没有 capacity 结果（冒烟矩阵不跑）')
    fig, ax = plt.subplots(figsize=(6, 4))
    for i, f in enumerate(sorted({r['features'] for r in rows}, key=lambda x: (x != 'v0', x))):
        rs = sorted((r for r in rows if r['features'] == f), key=lambda r: r['donors'])
        ax.plot([r['donors'] for r in rs], [r['capacity_bots.mean'] for r in rs], marker='o', color=SERIES[i % len(SERIES)], label=f)
    _integer_ticks(ax, rows, 'donors')
    ax.set_xlabel('donor 数')
    ax.set_ylabel('最多几个鞘翅玩家（虚空率 ≤ 5%）')
    ax.set_title('可持续探索容量')
    ax.legend()
    return _save(fig, out, name)


def fig04(d, out, name):
    rows = [r for r in _flight(d['summary'], server_cpus=2, latency_ms=0, bots=1, tile_size=4, speed='elytra', donors=2)
            if _ok(r.get('latency_p95_ms.mean'))]
    if len({r['features'] for r in rows}) < 2:
        return _no_data(out, name, '要至少两种开关组合（2 donor、鞘翅、2 核）')
    rows.sort(key=lambda r: (r['features'] != 'v0', r['features'] == 'all', r['features']))
    fig, ax = plt.subplots(figsize=(6, 0.6 * len(rows) + 1.5))
    y = range(len(rows))
    ax.barh(list(y), [r['latency_p95_ms.mean'] / 1000 for r in rows], color=SERIES[0], height=0.6,
            xerr=[[e / 1000 for e in side] for side in _err(rows, 'latency_p95_ms')], ecolor=INK2, capsize=3)
    ax.set_yticks(list(y), [r['features'] for r in rows])
    ax.invert_yaxis()
    ax.set_xlabel('就绪延迟 p95（秒）')
    ax.set_title('第 7 步开关的消融（2 donor、鞘翅、2 核）')
    ax.grid(axis='y', visible=False)
    return _save(fig, out, name)


def fig05(d, out, name):
    rows = [r for r in _flight(d['summary'], server_cpus=2, latency_ms=0, speed='elytra', donors=2)
            if r['features'] == _pick_features(_flight(d['summary'])) and _ok(r.get('latency_p95_ms.mean'))]
    if len({r['tile_size'] for r in rows}) < 2:
        return _no_data(out, name, '要至少两种 tile 大小')
    rows.sort(key=lambda r: r['tile_size'])
    fig, (a, b) = plt.subplots(1, 2, figsize=(10, 4))
    a.plot([r['tile_size'] for r in rows], [r.get('ring_overhead.mean') for r in rows], marker='o', color=SERIES[0])
    b.plot([r['tile_size'] for r in rows], [r['latency_p95_ms.mean'] / 1000 for r in rows], marker='o', color=SERIES[0])
    for ax in (a, b):
        ax.set_xscale('log', base=2)
        ax.set_xticks([1, 2, 4, 8], ['1×1', '2×2', '4×4', '8×8'])
        ax.set_xlabel('tile 大小')
    a.set_ylabel('外圈重复计算比例')
    b.set_ylabel('就绪延迟 p95（秒）')
    a.set_title('外圈重复计算')
    b.set_title('就绪延迟')
    return _save(fig, out, name)


def fig06(d, out, name):
    rows = [r for r in _flight(d['summary'], server_cpus=2, speed='elytra', donors=2, tile_size=4, bots=1)
            if r['features'] == 'all' and _ok(r.get('latency_p95_ms.mean'))]
    if len({r['latency_ms'] for r in rows}) < 2:
        return _no_data(out, name, '要至少两档网络延迟（2 donor、鞘翅、全开）')
    rows.sort(key=lambda r: r['latency_ms'])
    fig, ax = plt.subplots(figsize=(6, 4))
    ax.errorbar([r['latency_ms'] for r in rows], [r['latency_p95_ms.mean'] / 1000 for r in rows],
                yerr=[[e / 1000 for e in side] for side in _err(rows, 'latency_p95_ms')], marker='o', color=SERIES[0], capsize=3)
    ax.set_xlabel('donor 到服务器的单向延迟（毫秒，固定延迟，无丢包）')
    ax.set_ylabel('就绪延迟 p95（秒）')
    ax.set_title('网络延迟敏感性')
    return _save(fig, out, name)


def _verify(summary):
    return [r for r in summary if r.get('experiment') == 'verify']


def fig07(d, out, name):
    rows = _verify(d['summary'])
    cheat = [r for r in rows if r.get('cheat') not in (None, 'none') and r.get('detected.rate') is not None]
    honest = [r for r in rows if r.get('cheat') == 'none' and _ok(r.get('y.mean'))]
    if not cheat and not honest:
        return _no_data(out, name, STEP8_PAUSED if not rows else '没有 verify 结果')
    fig, (a, b) = plt.subplots(1, 2, figsize=(10, 4))
    for i, c in enumerate(sorted({r['cheat'] for r in cheat})):
        rs = sorted((r for r in cheat if r['cheat'] == c), key=lambda r: r.get('check_rate') or 0)
        a.plot([(r.get('check_rate') or 0) * 100 for r in rs], [r['detected.rate'] * 100 for r in rs], marker='o',
               color=SERIES[i % len(SERIES)], label=c)
    a.axhline(99, color=MUTED, linestyle='--', linewidth=1)
    a.set_xlabel('抽查率（%）')
    a.set_ylabel('检出率（%）')
    a.set_ylim(0, 102)
    a.set_title('每种作弊的检出率（默认强度）')
    if cheat:
        a.legend()
    hs = sorted(honest, key=lambda r: r.get('check_rate') or 0)
    b.errorbar([(r.get('check_rate') or 0) * 100 for r in hs], [r['y.mean'] * 100 for r in hs],
               yerr=[[e * 100 for e in side] for side in _err(hs, 'y')], marker='o', color=SERIES[0], capsize=3)
    b.set_xlabel('抽查率（%）')
    b.set_ylabel('Y：验证 CPU ÷ 本地生成 CPU（%）')
    b.set_title('验证成本（诚实 donor）')
    return _save(fig, out, name)


def fig08(d, out, name):
    verify = _verify(d['summary'])
    rows = [r for r in verify if r.get('cheat') not in (None, 'none') and _ok(r.get('chunks_before_blacklist.mean'))]
    if not rows:
        return _no_data(out, name, STEP8_PAUSED if not verify else '没有作弊 donor 的 verify 结果')
    names = sorted({r['cheat'] for r in rows})
    worst = [max(r['chunks_before_blacklist.mean'] for r in rows if r['cheat'] == c) for c in names]
    fig, ax = plt.subplots(figsize=(6, 0.6 * len(names) + 1.5))
    ax.barh(range(len(names)), worst, color=SERIES[0], height=0.6)
    ax.set_yticks(range(len(names)), names)
    ax.invert_yaxis()
    ax.set_xlabel('开始作弊到被拉黑，写进了多少 chunk（各抽查率里最多的）')
    ax.set_title('回滚要收拾多少')
    ax.grid(axis='y', visible=False)
    return _save(fig, out, name)


def fig09(d, out, name):
    rows = [r for r in d['runs'] if r.get('experiment') == 'frametime' and _ok(r.get('p99_ms'))]
    if not rows:
        return _no_data(out, name, '帧时间要在有显卡的真实电脑上采（第 9 步 9.7）')
    fig, ax = plt.subplots(figsize=(6, 4))
    groups = sorted({(r.get('worker'), r.get('throttle')) for r in rows}, key=str)
    for i, g in enumerate(groups):
        vals = [r['p99_ms'] for r in rows if (r.get('worker'), r.get('throttle')) == g]
        ax.scatter([i] * len(vals), vals, color=SERIES[0], s=36)
    ax.set_xticks(range(len(groups)), [f'worker {w}\n限速 {t}' for w, t in groups])
    ax.set_ylabel('p99 帧时间（毫秒）')
    ax.set_title('donor 帧时间')
    return _save(fig, out, name)


def fig10(d, out, name):
    s = d['layer1']
    if not s:
        return _no_data(out, name, '没有第 1 步的 layer1-summary.csv')
    det = d['determinism']
    fig, (a, b) = plt.subplots(1, 2, figsize=(10, 4))
    areas = [x for x in ('ocean', 'mountain', 'jungle') if any(r['area_id'] == x for r in s)]
    for i, area in enumerate(areas):
        rs = sorted((r for r in s if r['area_id'] == area), key=lambda r: r['cpu_count'])
        a.plot([r['cpu_count'] for r in rs], [r['mean'] for r in rs], marker='o', color=SERIES[i], label=area)
    a.set_xticks([1, 2, 4])
    a.set_xlabel('核数')
    a.set_ylabel('chunk/s')
    a.set_title('原版生成吞吐（第 1 步粗测）')
    a.legend()
    if det:
        rates = [sum(r['chunk_diff_rate'] for r in det if r['area_id'] == x and r['comparison_group'] != 'repeat_2cpu') /
                 max(1, sum(1 for r in det if r['area_id'] == x and r['comparison_group'] != 'repeat_2cpu')) * 100 for x in areas]
        b.bar(areas, rates, color=SERIES[0], width=0.6)
        for x, v in zip(areas, rates):
            b.text(x, v, f'{v:.1f}%', ha='center', va='bottom', color=INK2, fontsize=9)
        b.grid(axis='x', visible=False)
    b.set_ylabel('同 seed 两次生成，不完全一样的 chunk（%）')
    b.set_title('原版不确定率')
    fig.suptitle('EPYC 7542 限核粗测，部署机器 N100 上要重测', color=INK2, fontsize=9)
    return _save(fig, out, name)


def fig11(d, out, name):
    rows = sorted((r for r in d['x_by_n'] if _ok(r.get('x'))), key=lambda r: r['donors'])
    if not rows:
        return _no_data(out, name, '没有能算出 X 的 0 donor / N donor 配对')
    fig, ax = plt.subplots(figsize=(6, 4))
    lo = [r['x'] - r['ci_low'] if _ok(r.get('ci_low')) else 0 for r in rows]
    hi = [r['ci_high'] - r['x'] if _ok(r.get('ci_high')) else 0 for r in rows]
    ax.errorbar([r['donors'] for r in rows], [r['x'] for r in rows], yerr=[lo, hi], marker='o', color=SERIES[0], capsize=3)
    ax.axhline(X_TARGET, color=MUTED, linestyle='--', linewidth=1)
    ax.text(ax.get_xlim()[1], X_TARGET, ' 候选目标 2', color=MUTED, va='center', fontsize=8)
    _integer_ticks(ax, rows, 'donors')
    ax.set_xlabel('donor 数 N')
    ax.set_ylabel('X：0 donor 的 p95 ÷ N donor 的 p95')
    ax.set_title('X 随 donor 数变化（2 核、鞘翅、1 个假玩家）')
    return _save(fig, out, name)


DRAW = dict(zip(FIGURES, (fig01, fig02, fig03, fig04, fig05, fig06, fig07, fig08, fig09, fig10, fig11)))


def plot_all(campaign, out_dir, baseline_dir=ROOT / 'analysis/results') -> dict:
    """画全部图，返回 {图名: 'ok' | '无数据'}。某张图出错不影响别的图，但会抛出来让测试看到。"""
    _style()
    campaign, out = Path(campaign), Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    data = dict(summary=read(campaign / 'matrix-summary.csv'), runs=read(campaign / 'matrix-runs.csv'),
                x_by_n=read(campaign / 'x-by-n.csv'), layer1=read(Path(baseline_dir) / 'layer1-summary.csv'),
                determinism=read(Path(baseline_dir) / 'determinism.csv'))
    _REASONS.clear()
    status = {name: DRAW[name](data, out, name) for name in FIGURES}
    (out / 'no-data.json').write_text(json.dumps(dict(_REASONS), ensure_ascii=False, indent=1) + '\n')
    return status


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--campaign', required=True)
    args = parser.parse_args()
    print(plot_all(args.campaign, Path(args.campaign) / 'figures'))
