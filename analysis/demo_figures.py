"""第 10 步按"能演示"收尾（用户 11-09）：演示矩阵的几张关键图，只读 matrix-runs.csv。

- demo1_stall：卡住占比，每种配置每轮一个点、均值一条短横线（越低越好）
- demo2_ahead：donor 写入时在玩家前方的比例（只有有 donor 的配置）
- demo3_sources：chunk 是谁做完的，三类（服务器自己生成 / 导入后服务器接着做完 / 读盘），均值，堆叠横条
- demo4_network：网络的影响，同一组里有网络和没网络并排：卡住占比、实际 / 理想距离、在前方的比例（三张小图，不画双轴）
- demo5_discards：donor 交回的 tile 为什么被丢掉（来晚、碰到服务器的、别的 donor 世界的、过时删掉的），均值，堆叠横条

挑演示默认（调度 11-09："哪个设置下 donor 的效果最大、两次之间最稳"）：同一组（group）里、同一次（repeat）的原版卡住占比
减有 donor 的，叫成对降幅；每种有 donor 的配置取它几次成对降幅里最小的那个，最大的胜出。有网络的那组是看网络影响的，不参加。
原版的数随机器负载变（同一天 25%～43%），所以只和同一组、紧挨着跑的那次原版比。

另写 demo-figures.json：每张图背后的数、各设置的成对降幅、挑出来的设置。没有数据的图写"无数据"，不报错。
用法：python -m analysis.demo_figures --campaign analysis/results/runs/matrix-demo-v2
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
from pathlib import Path

import matplotlib

matplotlib.use('Agg')
import matplotlib.pyplot as plt  # noqa: E402
import matplotlib.ticker  # noqa: E402

from analysis.plots import INK2, MUTED, SERIES, _style  # noqa: E402

FIGURES = ('demo1_stall', 'demo2_ahead', 'demo3_sources', 'demo4_network', 'demo5_discards')
SOURCES = (('full_generated', '服务器自己生成', SERIES[2]), ('full_resumed', '导入后服务器接着做完', SERIES[0]),
           ('full_loaded', '读盘', MUTED))
DISCARDS = (('discard_late', '来晚了', SERIES[1]), ('discard_server', '碰到服务器的 chunk', SERIES[3]),
            ('discard_other_world', '碰到别的 donor 世界', SERIES[4]), ('stale_dropped', '玩家飞过、过时删掉', MUTED))
NOTE = '演示，粗测：这台机器上限核，每种配置 2 次，可能有别的测试并行'


def _value(v):
    if v in ('', None):
        return None
    if v in ('True', 'False'):
        return v == 'True'
    try:
        f = float(v)
    except ValueError:
        return v
    return int(f) if f.is_integer() and 'e' not in v and '.' not in v and 'inf' not in v else f


def read_runs(campaign) -> list[dict]:
    path = Path(campaign) / 'matrix-runs.csv'
    if not path.exists():
        return []
    with path.open() as f:
        return [{k: _value(v) for k, v in r.items()} for r in csv.DictReader(f) if r.get('experiment') == 'flight']


def _group(r):
    return r.get('group') or f"{r['server_cpus']}c"


SCENARIO_NAME = {'straight': '直飞', 'turning': '转弯鞘翅', 'out_and_back': '来回飞', 'mixed': '走路骑马', 'two_players': '两个玩家'}


def label(r, vanilla_groups=None) -> str:
    scen = SCENARIO_NAME.get(r.get('scenario'), r.get('scenario'))
    base = (f'{scen} · ' if scen else '') + f"{r['server_cpus']} 核 "
    if r['donors'] == 0:
        many = vanilla_groups and len(vanilla_groups.get((r['server_cpus'], r.get('scenario')), ())) > 1
        return base + '原版' + (f'（{_group(r)} 组）' if many else '')
    s = base + f"{r['donors']} donor {r.get('features')}"
    if r.get('lookahead'):
        s += f" 提前量 {r['lookahead']}"
    if (r.get('latency_ms') or 0) > 0:
        s += ' + 网络'
    return s


def _sort_key(r):
    return (str(r.get('scenario') or ''), -r['server_cpus'], str(r.get('group') or ''), r['donors'] > 0, r.get('latency_ms') or 0,
            r.get('lookahead') or 0, r['donors'])


def configs(rows):
    """[(标签, 这种配置的所有轮)]，按核数（多的在前）、组、原版在前排好。"""
    vg = {}
    for r in rows:
        if r['donors'] == 0:
            vg.setdefault((r['server_cpus'], r.get('scenario')), set()).add(_group(r))
    out = {}
    for r in sorted(rows, key=_sort_key):
        out.setdefault(label(r, vg), []).append(r)
    return list(out.items())


def _vals(rs, key):
    return [float(r[key]) for r in rs if isinstance(r.get(key), (int, float)) and not isinstance(r.get(key), bool)
            and math.isfinite(float(r[key]))]


def _mean(v):
    return statistics.mean(v) if v else None


def settings(rows) -> list[dict]:
    """每种有 donor、没网络的配置：和同一组同一次的原版成对比卡住占比。"""
    vg = {}
    for r in rows:
        if r['donors'] == 0:
            vg.setdefault((r['server_cpus'], r.get('scenario')), set()).add(_group(r))
    vanilla = {(_group(r), r['repeat']): r for r in rows if r['donors'] == 0}
    by = {}
    for r in sorted(rows, key=_sort_key):
        if r['donors'] > 0 and not (r.get('latency_ms') or 0):
            by.setdefault(label(r, vg), []).append(r)
    out = []
    for name, rs in by.items():
        pairs = sorted((r['repeat'], vanilla[(_group(r), r['repeat'])], r) for r in rs if (_group(r), r['repeat']) in vanilla
                       and _vals([vanilla[(_group(r), r['repeat'])]], 'stall_fraction') and _vals([r], 'stall_fraction'))
        red = [v['stall_fraction'] - d['stall_fraction'] for _, v, d in pairs]
        out.append(dict(setting=name, group=_group(rs[0]), pairs=len(pairs),
                        vanilla_stall=[v['stall_fraction'] for _, v, _ in pairs], donor_stall=[d['stall_fraction'] for _, _, d in pairs],
                        paired_reduction=red, min_reduction=min(red) if red else None, mean_reduction=_mean(red),
                        relative_reduction=_mean([(v['stall_fraction'] - d['stall_fraction']) / v['stall_fraction']
                                                  for _, v, d in pairs if v['stall_fraction']]) if pairs else None))
    return out


def best_setting(sets):
    ok = [s for s in sets if s['min_reduction'] is not None]
    if not ok:
        return None
    most = max(s['pairs'] for s in ok)   # 成对次数最多的里面挑（只跑成一次的不算稳）
    return max((s for s in ok if s['pairs'] == most), key=lambda s: s['min_reduction'])


def _colors(names):
    """原版灰；有 donor 的按出现顺序取固定的系列色，同一个标签在每张图里同色。"""
    out, i = {}, 0
    for n in names:
        if '原版' in n:
            out[n] = MUTED
        else:
            out[n] = SERIES[i % len(SERIES)]
            i += 1
    return out


def _dots(ax, groups, key, names, colors, pct=True):
    for i, name in enumerate(names):
        v = _vals(groups[name], key)
        if not v:
            ax.text(i, 0, '无数据', ha='center', va='bottom', color=MUTED, fontsize=8)
            continue
        ax.scatter([i] * len(v), v, color=colors[name], s=36, zorder=3, edgecolor='white', linewidth=1.5)
        m = _mean(v)
        ax.hlines(m, i - .22, i + .22, color=colors[name], linewidth=2, zorder=2)
        ax.annotate(f'{m:.0%}' if pct else f'{m:.2f}', (i + .26, m), va='center', color=INK2, fontsize=8)
    ax.set_xticks(range(len(names)), [n.replace(' donor', '\ndonor').replace(' 提前量', '\n提前量').replace('（', '\n（')
                                      for n in names], fontsize=8)
    ax.set_xlim(-.5, len(names) - .3)
    if pct:
        ax.yaxis.set_major_formatter(matplotlib.ticker.PercentFormatter(1.0, decimals=0))


def _save(fig, out, name):
    fig.text(.01, .01, NOTE, fontsize=8, color=MUTED)
    fig.tight_layout(rect=(0, .04, 1, 1))
    fig.savefig(out / f'{name}.png', dpi=150)
    plt.close(fig)
    return 'ok'


def demo1_stall(groups, names, colors, out):
    if not any(_vals(groups[n], 'stall_fraction') for n in names):
        return '无数据'
    fig, ax = plt.subplots(figsize=(max(6.4, 1.15 * len(names) + 1.5), 4.2))
    _dots(ax, groups, 'stall_fraction', names, colors)
    ax.set_ylim(0, max(.5, max(v for n in names for v in _vals(groups[n], 'stall_fraction')) * 1.15))
    ax.set_title('卡住占比：玩家停在没加载出来的 chunk 前面等的时间（越低越好）', loc='left')
    return _save(fig, out, 'demo1_stall')


def demo2_ahead(groups, names, colors, out):
    names = [n for n in names if '原版' not in n]
    if not any(_vals(groups[n], 'ahead_total') for n in names):
        return '无数据'
    fig, ax = plt.subplots(figsize=(max(5.2, 1.15 * len(names) + 1.5), 3.8))
    _dots(ax, groups, 'ahead_total', names, colors)
    ax.set_ylim(0, 1.08)
    ax.set_title('donor 写入的 chunk 有多少在玩家前方（越高越有用）', loc='left')
    return _save(fig, out, 'demo2_ahead')


def _stacked(groups, names, parts, out, name, title, xlabel):
    if not names or not any(_vals(groups[n], parts[0][0]) for n in names):
        return '无数据'
    fig, ax = plt.subplots(figsize=(7.8, 1.2 + 0.55 * len(names)))
    for i, n in enumerate(names):
        left = 0
        for key, lab, color in parts:
            m = _mean(_vals(groups[n], key)) or 0
            ax.barh(i, m, left=left, color=color, height=.55, edgecolor='white', linewidth=2, label=lab if i == 0 else None)
            if m and m >= 0.06 * max(1, sum(_mean(_vals(groups[x], k)) or 0 for x in names for k, _, _ in parts) / len(names)):
                ax.text(left + m / 2, i, f'{m:.0f}', ha='center', va='center', color='white', fontsize=8)
            left += m
    ax.set_yticks(range(len(names)), names, fontsize=9)
    ax.invert_yaxis()
    ax.set_xlabel(xlabel)
    ax.legend(loc='upper center', bbox_to_anchor=(.5, -.18 - .6 / max(len(names), 1)), ncol=len(parts), fontsize=8)
    ax.set_title(title, loc='left')
    ax.grid(axis='y', visible=False)
    return _save(fig, out, name)


def demo3_sources(groups, names, colors, out):
    return _stacked(groups, names, SOURCES, out, 'demo3_sources', 'chunk 是谁做完的：donor 分担的是"导入后服务器接着做完"那一段',
                    '一轮飞行里做完的 chunk 数（均值）')


def demo5_discards(groups, names, colors, out):
    names = [n for n in names if '原版' not in n]
    return _stacked(groups, names, DISCARDS, out, 'demo5_discards', 'donor 交回的 tile 为什么被丢掉',
                    '一轮飞行里丢掉的 tile 数（均值）')


def demo4_network(groups, names, colors, out):
    net = [n for n in names if n.endswith(' + 网络')]
    pairs = [(n[:-len(' + 网络')], n) for n in net if n[:-len(' + 网络')] in groups]
    if not pairs:
        return '无数据'
    plain, withnet = pairs[0]
    panels = (('stall_fraction', '卡住占比（越低越好）'), ('distance_ratio', '实际 / 理想距离（越高越好）'),
              ('ahead_total', 'donor 写入在玩家前方（越高越好）'))
    fig, axes = plt.subplots(1, 3, figsize=(9.6, 3.8))
    for ax, (key, title) in zip(axes, panels):
        _dots(ax, groups, key, [plain, withnet], colors)
        ax.set_ylim(0, 1.08)
        ax.set_title(title, fontsize=10, loc='left')
        ax.set_xticks(range(2), ['没网络', '35ms / 20mbit\n/ 1% 丢包'])
    fig.suptitle(f'网络的影响：{plain}', x=.01, ha='left', fontsize=11)
    return _save(fig, out, 'demo4_network')


DRAW = dict(zip(FIGURES, (demo1_stall, demo2_ahead, demo3_sources, demo4_network, demo5_discards)))
KEYS = ('stall_fraction', 'stall_s', 'distance_ratio', 'ahead_total', 'void_mean', 'latency_p95_ms', 'mspt_p95', 'mspt_max',
        'cpu_cores_mean', 'full_generated', 'full_resumed', 'full_loaded', 'resumed_share', 'donor_events', 'imports_accepted',
        'discard_late', 'discard_server', 'discard_other_world', 'stale_dropped', 'stale_cancelled',
        'stall_fraction_max', 'cant_keep_up', 'moved_too_quickly', 'import_ms', 'worker_cores', 'tile_s', 'players')


def draw_all(campaign, out_dir) -> dict:
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    rows = read_runs(campaign)
    cfgs = configs(rows)
    groups = dict(cfgs)
    names = [n for n, _ in cfgs]
    colors = _colors(names)
    if not rows:
        status = {name: '无数据' for name in FIGURES}
    else:
        _style()
        status = {name: DRAW[name](groups, names, colors, out) for name in FIGURES}
    sets = settings(rows)
    table = dict(note=NOTE, configs=names, status=status, runs={n: len(groups[n]) for n in names},
                 mean={n: {k: _mean(_vals(groups[n], k)) for k in KEYS} for n in names},
                 per_run={n: [{'repeat': r.get('repeat'), **{k: r.get(k) for k in KEYS}} for r in groups[n]] for n in names},
                 settings=sets, best=best_setting(sets),
                 rule='同一组同一次的原版卡住占比减有 donor 的（成对降幅），取几次里最小的，最大的胜出；有网络的不参加')
    (out / 'demo-figures.json').write_text(json.dumps(table, ensure_ascii=False, indent=1, default=str) + '\n')
    return status


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--campaign', required=True)
    args = parser.parse_args()
    status = draw_all(args.campaign, Path(args.campaign) / 'figures')
    print(json.dumps(status, ensure_ascii=False))
