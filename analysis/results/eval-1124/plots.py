"""course project 评测（11-24）出图：E1 主结果、E1 逐秒、E2 速度、E3 加核。
用法：python plots.py <e1 目录> <e23 目录> <e3 目录> <输出目录>（E3 驱动只支持 1/2/4 核，改成 4 核）"""
import csv, json, sys, statistics as st
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

from matplotlib import font_manager
font_manager.fontManager.addfont('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')
plt.rcParams['font.sans-serif'] = [font_manager.FontProperties(fname='/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc').get_name(), 'DejaVu Sans']
plt.rcParams['axes.unicode_minus'] = False
GREY, BLUE, INK, MUTED = '#9a9a96', '#2a6fdb', '#222222', '#6b6b6b'
WORKER_MD5 = 'c50dcbf9'   # worker jar md5 前 8 位（flight_driver 记的是 md5）

e1, e23, e3, out = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4])
out.mkdir(parents=True, exist_ok=True)


def load(c):
    rows = list(csv.DictReader(open(c / 'matrix-runs.csv')))
    for r in rows:
        r['_dir'] = c / r['cell_id']
    return rows


def f(r, k):
    try:
        return float(r[k])
    except (KeyError, ValueError):
        return float('nan')


def donor(r):
    return int(r['donors']) > 0


def speed(r):
    route = r.get('scenario_route', '')
    try:
        return float(route.split(',')[1])
    except Exception:
        return 33.5


def style(ax):
    for s in ('top', 'right'):
        ax.spines[s].set_visible(False)
    ax.spines['left'].set_color(GREY)
    ax.spines['bottom'].set_color(GREY)
    ax.tick_params(colors=MUTED)


R1, R23, R3 = load(e1), load(e23), load(e3)
summary = {}

# 检查：donor 轮用的 worker jar 是不是这次构建的
bad = [r['cell_id'] for r in R1 + R23 + R3 if donor(r) and r.get('worker_jar') != WORKER_MD5]
summary['worker_jar_mismatch'] = bad

# 图 1：E1 卡住占比，中位数 + 范围
fig, ax = plt.subplots(figsize=(7, 2.8))
arms = [('原版（2 核）', [r for r in R1 if not donor(r)], GREY), ('+1 个 4 核 donor', [r for r in R1 if donor(r)], BLUE)]
for i, (name, rs, c) in enumerate(arms):
    v = [100 * f(r, 'stall_after_exit') for r in rs]
    if not v:
        continue
    m = st.median(v)
    ax.barh(i, m, color=c, height=0.5)
    ax.plot([min(v), max(v)], [i, i], color=INK, lw=1.5)
    ax.text(max(v) + 1, i, f'{m:.0f}%（{min(v):.0f}–{max(v):.0f}%，{len(v)} 轮）', va='center', color=INK, fontsize=10)
    summary[f'E1 {name} 卡住%'] = v
    summary[f'E1 {name} MSPT p95'] = [round(f(r, 'mspt_p95'), 1) for r in rs]
ax.set_yticks(range(len(arms)), [a[0] for a in arms])
ax.invert_yaxis()
ax.set_xlabel('飞出缓存区后卡住的时间占比（%）', color=MUTED)
ax.set_xlim(0, 100)
style(ax)
ax.set_title('图 1  2 核服务器加 1 个 donor 后，玩家卡住的时间大幅减少', loc='left', fontsize=11, color=INK)
fig.tight_layout()
fig.savefig(out / 'fig1-stall.png', dpi=200)
plt.close(fig)

# 图 2：E1 各取 1 轮（中位数那轮），逐秒视野里没就绪的比例
def seconds(r):
    p = sorted(Path(r['_dir']).glob('attempt-*/flight/**/seconds.jsonl')) or sorted(Path(r['_dir']).glob('attempt-*/**/seconds.jsonl'))
    return [json.loads(l) for l in open(p[-1])] if p else []

fig, ax = plt.subplots(figsize=(7, 3))
for name, rs, c in arms:
    if not rs:
        continue
    rs = sorted(rs, key=lambda r: f(r, 'stall_after_exit'))
    r = rs[len(rs) // 2]
    s = seconds(r)
    if not s:
        continue
    xs, ys, acc = [], [], 0.0
    for x in s:
        acc += x.get('stalled_ticks', 0) / 20
        xs.append(x['t_s']); ys.append(acc)
    ax.plot(xs, ys, color=c, lw=2, label=name)
    ax.annotate(f'{ys[-1]:.0f} 秒', (xs[-1], ys[-1]), textcoords='offset points', xytext=(6, -3), color=INK, fontsize=10)
    ex = f(r, 'exit_s')
ax.axvline(ex, color=MUTED, ls='--', lw=1)
ax.text(ex + 1.5, ax.get_ylim()[1] * 0.92, '飞出缓存区', color=MUTED, fontsize=9)
ax.set_xlabel('飞行时间（秒）', color=MUTED)
ax.set_ylabel('累计卡住时间（秒）', color=MUTED)
ax.set_xlim(0, 132)
ax.legend(frameon=False, loc='upper left', bbox_to_anchor=(0.1, 0.8))
style(ax)
ax.set_title('图 2  原版飞出缓存区后几乎一直在卡，donor 只偶尔卡一下（各取中位数那一轮）', loc='left', fontsize=10, color=INK)
fig.tight_layout()
fig.savefig(out / 'fig2-void-timeline.png', dpi=200)
plt.close(fig)

# 图 3：速度–卡住（E2 10/20 + E1 33.5 的中位数）
fig, ax = plt.subplots(figsize=(7, 3))
pts = {}
for r in R23:
    if r['server_cpus'] == '2':
        pts.setdefault(donor(r), {}).setdefault(speed(r), []).append(100 * f(r, 'stall_after_exit'))
for r in R1:
    pts.setdefault(donor(r), {}).setdefault(33.5, []).append(100 * f(r, 'stall_after_exit'))
for d, name, c in ((False, '原版', GREY), (True, '+1 donor', BLUE)):
    xs = sorted(pts.get(d, {}))
    ys = [st.median(pts[d][x]) for x in xs]
    ax.plot(xs, ys, marker='o', color=c, lw=2, ms=7, label=name)
    for x, y in zip(xs, ys):
        ax.annotate(f'{y:.0f}%', (x, y), textcoords='offset points', xytext=(6, -14) if d else (6, 6), color=INK, fontsize=9)
    summary[f'E2 {name}'] = {x: pts[d][x] for x in xs}
ax.axhline(5, color=MUTED, ls=':', lw=1)
ax.text(24, 6, '5% 以下算能扛住', color=MUTED, fontsize=9)
ax.set_xlabel('飞行速度（格/秒）', color=MUTED)
ax.set_ylabel('卡住时间占比（%）', color=MUTED)
ax.legend(frameon=False)
style(ax)
ax.set_title('图 3  原版到 20 格/秒还扛得住，33.5 格/秒崩了；donor 在 33.5 格/秒仍在 5% 以下', loc='left', fontsize=10, color=INK)
fig.tight_layout()
fig.savefig(out / 'fig3-speed.png', dpi=200)
plt.close(fig)

# 图 4：2 核 / 3 核 × 原版 / donor（33.5 格/秒）
fig, ax = plt.subplots(figsize=(7, 3))
cells = [('2 核 原版', [r for r in R1 if not donor(r)], GREY),
         ('2 核 + donor', [r for r in R1 if donor(r)], BLUE),
         ('4 核 原版', [r for r in R3 if not donor(r)], GREY),
         ('4 核 + donor', [r for r in R3 if donor(r)], BLUE)]
for i, (name, rs, c) in enumerate(cells):
    v = [100 * f(r, 'stall_after_exit') for r in rs]
    if not v:
        continue
    m = st.median(v)
    ax.barh(i, m, color=c, height=0.55)
    ax.text(m + 1, i, f'{m:.0f}%' + (f'（{len(v)} 轮）' if len(v) > 1 else ''), va='center', color=INK, fontsize=10)
    summary[f'E3 {name}'] = v
ax.set_yticks(range(len(cells)), [c[0] for c in cells])
ax.invert_yaxis()
ax.set_xlim(0, 100)
ax.set_xlabel('卡住时间占比（%），烟花鞘翅 33.5 格/秒', color=MUTED)
style(ax)
ax.set_title('图 4  加 1 个 donor 和把服务器从 2 核加到 4 核，哪个更有效', loc='left', fontsize=11, color=INK)
fig.tight_layout()
fig.savefig(out / 'fig4-cores.png', dpi=200)
plt.close(fig)

json.dump(summary, open(out / 'summary.json', 'w'), ensure_ascii=False, indent=1, default=str)
print(json.dumps(summary, ensure_ascii=False, indent=1, default=str))
