"""第 10 步按"能演示"收尾（用户 11-09）：演示矩阵的四张关键图，只读 matrix-runs.csv。

- demo1_stall：卡住占比，三种配置（原版 / deadline / deadline + 网络），每轮一个点、均值一条短横线
- demo2_ahead：donor 写入时在玩家前方的比例（只有有 donor 的配置）
- demo3_sources：chunk 是谁做完的，三类（服务器自己生成 / 导入后服务器接着做完 / 读盘），每种配置的均值，堆叠横条
- demo4_network：网络的影响，deadline 有网络和没网络并排：卡住占比、实际 / 理想距离、在前方的比例（三张小图，不画双轴）

另写 demo-figures.json：每张图背后的数（报告里引用，也给看不清图的人）。没有数据的图写"无数据"，不报错。
用法：python -m analysis.demo_figures --campaign analysis/results/runs/matrix-demo
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

from analysis.plots import INK2, MUTED, SERIES, _style  # noqa: E402

FIGURES = ('demo1_stall', 'demo2_ahead', 'demo3_sources', 'demo4_network')
CONFIGS = (
    ('原版（0 donor）', lambda r: r['donors'] == 0),
    ('deadline（2 donor）', lambda r: r['donors'] == 2 and r.get('features') == 'deadline' and not r.get('latency_ms')),
    ('deadline + 网络（2 donor）', lambda r: r['donors'] == 2 and r.get('features') == 'deadline' and (r.get('latency_ms') or 0) > 0),
)
COLOR = dict(zip((c[0] for c in CONFIGS), (MUTED, SERIES[0], SERIES[1])))
SOURCES = (('full_generated', '服务器自己生成', SERIES[2]), ('full_resumed', '导入后服务器接着做完', SERIES[0]),
           ('full_loaded', '读盘', MUTED))
NOTE = '演示，粗测：这台机器上限 2 核，每种配置 2 次，可能有别的测试并行'


def _value(v):
    if v in ('', None):
        return None
    if v in ('True', 'False'):
        return v == 'True'
    try:
        f = float(v)
    except ValueError:
        return v
    return int(f) if f.is_integer() and 'e' not in v and '.' not in v else f


def read_runs(campaign) -> list[dict]:
    path = Path(campaign) / 'matrix-runs.csv'
    if not path.exists():
        return []
    with path.open() as f:
        return [{k: _value(v) for k, v in r.items()} for r in csv.DictReader(f) if r.get('experiment') == 'flight']


def _groups(rows):
    return {name: [r for r in rows if match(r)] for name, match in CONFIGS}


def _vals(rs, key):
    return [float(r[key]) for r in rs if isinstance(r.get(key), (int, float)) and not isinstance(r.get(key), bool)
            and math.isfinite(float(r[key]))]


def _mean(v):
    return statistics.mean(v) if v else None


def _dots(ax, groups, key, names, pct=True):
    for i, name in enumerate(names):
        v = _vals(groups[name], key)
        if not v:
            ax.text(i, 0, '无数据', ha='center', va='bottom', color=MUTED, fontsize=9)
            continue
        ax.scatter([i] * len(v), v, color=COLOR[name], s=36, zorder=3, edgecolor='white', linewidth=1.5)
        m = _mean(v)
        ax.hlines(m, i - .22, i + .22, color=COLOR[name], linewidth=2, zorder=2)
        ax.annotate(f'{m:.0%}' if pct else f'{m:.2f}', (i + .26, m), va='center', color=INK2, fontsize=9)
    ax.set_xticks(range(len(names)), [n.replace('（', '\n（') for n in names])
    ax.set_xlim(-.5, len(names) - .3)
    if pct:
        ax.yaxis.set_major_formatter(matplotlib.ticker.PercentFormatter(1.0, decimals=0))


def _save(fig, out, name):
    fig.text(.01, .01, NOTE, fontsize=8, color=MUTED)
    fig.tight_layout(rect=(0, .04, 1, 1))
    fig.savefig(out / f'{name}.png', dpi=150)
    plt.close(fig)
    return 'ok'


def demo1_stall(groups, out):
    names = [n for n, _ in CONFIGS]
    if not any(_vals(groups[n], 'stall_fraction') for n in names):
        return '无数据'
    fig, ax = plt.subplots(figsize=(6.4, 3.8))
    _dots(ax, groups, 'stall_fraction', names)
    ax.set_ylim(0, max(.5, max(v for n in names for v in _vals(groups[n], 'stall_fraction')) * 1.15))
    ax.set_title('卡住占比：玩家停在没加载出来的 chunk 前面等的时间（越低越好）', loc='left')
    return _save(fig, out, 'demo1_stall')


def demo2_ahead(groups, out):
    names = [n for n, _ in CONFIGS[1:]]
    if not any(_vals(groups[n], 'ahead_total') for n in names):
        return '无数据'
    fig, ax = plt.subplots(figsize=(5.2, 3.6))
    _dots(ax, groups, 'ahead_total', names)
    ax.set_ylim(0, 1.08)
    ax.set_title('donor 写入的 chunk 有多少在玩家前方（越高越有用）', loc='left')
    return _save(fig, out, 'demo2_ahead')


def demo3_sources(groups, out):
    names = [n for n, _ in CONFIGS if groups[n]]
    if not names or not any(_vals(groups[n], 'full_generated') for n in names):
        return '无数据'
    fig, ax = plt.subplots(figsize=(7.2, 0.9 + 0.7 * len(names)))
    for i, name in enumerate(names):
        left = 0
        for key, label, color in SOURCES:
            m = _mean(_vals(groups[name], key)) or 0
            ax.barh(i, m, left=left, color=color, height=.55, edgecolor='white', linewidth=2,
                    label=label if i == 0 else None)
            if m >= 60:
                ax.text(left + m / 2, i, f'{m:.0f}', ha='center', va='center', color='white', fontsize=9)
            left += m
    ax.set_yticks(range(len(names)), names)
    ax.invert_yaxis()
    ax.set_xlabel('一轮飞行里做完的 chunk 数（均值）')
    ax.legend(loc='upper center', bbox_to_anchor=(.5, -.35 if len(names) < 3 else -.25), ncol=3, fontsize=9)
    ax.set_title('chunk 是谁做完的：donor 分担的是"导入后服务器接着做完"那一段', loc='left')
    ax.grid(axis='y', visible=False)
    return _save(fig, out, 'demo3_sources')


def demo4_network(groups, out):
    names = [n for n, _ in CONFIGS[1:]]
    if not all(groups[n] for n in names):
        return '无数据'
    panels = (('stall_fraction', '卡住占比（越低越好）'), ('distance_ratio', '实际 / 理想距离（越高越好）'),
              ('ahead_total', 'donor 写入在玩家前方（越高越好）'))
    fig, axes = plt.subplots(1, 3, figsize=(9.6, 3.6), sharey=False)
    for ax, (key, title) in zip(axes, panels):
        _dots(ax, groups, key, names)
        ax.set_ylim(0, 1.08)
        ax.set_title(title, fontsize=10, loc='left')
        ax.set_xticks(range(2), ['没网络', '35ms / 20mbit\n/ 1% 丢包'])
    fig.suptitle('网络的影响：deadline，2 个 donor', x=.01, ha='left', fontsize=11)
    return _save(fig, out, 'demo4_network')


DRAW = dict(zip(FIGURES, (demo1_stall, demo2_ahead, demo3_sources, demo4_network)))


def draw_all(campaign, out_dir) -> dict:
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    rows = read_runs(campaign)
    groups = _groups(rows)
    if not rows:
        status = {name: '无数据' for name in FIGURES}
    else:
        _style()
        status = {name: DRAW[name](groups, out) for name in FIGURES}
    keys = ('stall_fraction', 'stall_s', 'distance_ratio', 'ahead_total', 'void_mean', 'latency_p95_ms', 'mspt_p95',
            'mspt_max', 'cpu_cores_mean', 'full_generated', 'full_resumed', 'full_loaded', 'donor_events')
    table = dict(note=NOTE, configs=[n for n, _ in CONFIGS], status=status,
                 runs={n: len(groups[n]) for n, _ in CONFIGS},
                 mean={n: {k: _mean(_vals(groups[n], k)) for k in keys} for n, _ in CONFIGS},
                 per_run={n: [{k: r.get(k) for k in keys} for r in groups[n]] for n, _ in CONFIGS})
    (out / 'demo-figures.json').write_text(json.dumps(table, ensure_ascii=False, indent=1, default=str) + '\n')
    return status


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--campaign', required=True)
    args = parser.parse_args()
    print(draw_all(args.campaign, Path(args.campaign) / 'figures'))
