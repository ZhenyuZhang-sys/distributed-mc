"""重新生成 analysis/results/firework-3arm.md：python analysis/results/firework_tables.py（归档在 firework-runs/，按 codex-audit K1～K3 修好的算法重算）。"""
import re
from pathlib import Path
from analysis import firework as f

HERE = Path(__file__).parent / 'firework-runs'
CAMPAIGNS = [('k2-637c2de-ufw-main-8', '637c2de'), ('k3-30a7c22-ufw-main-8', '30a7c22'), ('u1-30a7c22-ufw-sweep8-lo', '30a7c22'),
             ('k3-2359f7d-ufw-main-8', '2359f7d'), ('k3-2359f7d-ufw-mspt', '2359f7d'), ('k2-10cadc8-ufw-turn-first', '10cadc8'),
             ('u1-10cadc8-ufw-straight3', '10cadc8'), ('u1-635a362-ufw-turn-first', '635a362'), ('k3-6b63414-ufw-mw-turn', '6b63414'),
             ('k2-03b2981w-ufw-mw-straight', '03b2981 · 新 worker'), ('k3-03b2981w-ufw-mw-turn', '03b2981 · 新 worker'),
             ('u1-03b2981w-ufw-turn-first', '03b2981 · 新 worker')]
NOTE = ('用户同款服务器：2 核、3G、-Xmx2G、视距 10/6；donor 8 核。几个槽位并行跑，粗测。代码：637c2de = fish-opt+kexu，'
        '30a7c22 / 2359f7d / 3132230 = fish-perf 各版本 + kexu。前方已就绪距离、扇形按 codex-audit K1～K3 修好的算法从归档重算'
        '（初始视野补上、route 按航段取方向、DDA 逐 chunk），单位格。多轮的格子写中位数（最低～最高，几轮）')

rows, rows_of = [], {}
for name, code in CAMPAIGNS:
    if (HERE / name).exists():
        rows_of[name] = [dict(r, code=code, slot=name.split('-')[0].replace('u1', 'k4')) for r in f.rows_recomputed(HERE / name)]
        rows += rows_of[name]
WARN = ('> **注意（11-09 11:00 ET 发现）**：下面这些批次里，donor 用的都是槽位里早上留下的旧 worker jar（跑批只跑了 collectMods，没跑 collectWorkerMods），'
        '标的代码版本只对服务器（coordinator）一侧成立。k2 的旧 worker（含 PREFETCHED）和 k3、k4 的（更旧，两个相同）也不一样。所以不同代码之间 donor 侧的差别'
        '（用核、tile/s、卡住、PalettedContainer 崩溃次数）都不能当成代码的差别；原版的数、尖峰、同槽位"donor 对原版"还算数。用新 worker 重跑的批次标 "· 新 worker"。')


def _crashes(name):
    """这批里 donor 任务失败的次数（PalettedContainer 多线程那种）：数服务器（coordinator）日志里的"任务 N 在 worker M 上失败"。"""
    return sum(len(re.findall(r'任务 \d+ 在 worker \d+ 上失败', p.read_text(errors='replace'))) for p in (HERE / name).rglob('server-log.txt'))


def _pick(name, donors):
    return [r for r in rows_of[name] if (r.get('donors') or 0) == donors]


def _s(name, donors, k='stall_after_exit', pct=True, digits=1):
    return f._stat(_pick(name, donors), k, pct, digits)


def conclusion():
    """给用户的结论（调度 11-09 13:58）：只用新 worker（03b2981）同槽位 3 轮的数；旧 worker 的只作参考。"""
    st, tk4, tk3 = 'k2-03b2981w-ufw-mw-straight', 'u1-03b2981w-ufw-turn-first', 'k3-03b2981w-ufw-mw-turn'
    new = [st, tk4, tk3]
    donor_rounds = sum(len([r for r in rows_of[n] if r.get('donors')]) for n in new)
    crashes = sum(_crashes(n) for n in new)
    old_crashes = sum(_crashes(n) for n in ('k2-3132230-ufw-turn-first', 'k2-10cadc8-ufw-turn-first'))
    sweep = [dict(r, config=f._cfg(r)) for r in rows if r.get('code') == '30a7c22' and r.get('scenario') == 'firework' and r.get('speed_bps') is not None]
    best = f.max_sustainable(sweep)
    demo = {}
    for r in sweep:
        demo.setdefault((r['speed_bps'], bool(r.get('donors'))), r)
    spikes = [r for r in rows if isinstance(r.get('mspt_max'), (int, float)) and r['mspt_max'] >= f.SPIKE_MS]
    gc = max((r['gc_max_ms'] for r in spikes if isinstance(r.get('gc_max_ms'), (int, float))), default=None)
    van_spike = max((r['mspt_max'] for r in spikes if not r.get('donors')), default=None)
    don_spike = max((r['mspt_max'] for r in spikes if r.get('donors')), default=None)
    reused = sum(1 for n, old in ((tk4, 'u1-635a362-ufw-turn-first'), (tk3, 'k3-6b63414-ufw-mw-turn'))
                 for r in _pick(n, 0) if _same_cell(n, old, r['cell_id']))
    m = lambda name, d, k='stall_after_exit': f._median([r[k] for r in _pick(name, d) if isinstance(r.get(k), (int, float))])
    span = lambda vals, pct=True, digits=1: (f._f(min(vals), pct, digits) if f._f(min(vals), pct, digits) == f._f(max(vals), pct, digits)
                                             else f'{f._f(min(vals), pct, digits)}～{f._f(max(vals), pct, digits)}')
    L = ['## 给用户的结论', '',
         '用户同款服务器（2 核、3G、-Xmx2G、视距 10/6），真实烟花速度 33.5 格/秒，出了缓存区以后卡住的时间占比。'
         '下面的主结论只用新 worker（03b2981，修好了 PalettedContainer 崩溃）的批次，每个对比都在同一个槽位里交替跑、每臂 3 轮，写中位数（最低～最高）。', '',
         '| 场景（槽位） | 原版（0 个 donor） | 1 个 donor，8 核 | 2 个 donor，各 4 核 |', '| --- | --- | --- | --- |',
         f'| 直线（k2） | {_s(st, 0)} | {_s(st, 1)} | {_s(st, 2)} |',
         f'| 转弯（k4） | {_s(tk4, 0)} | {_s(tk4, 1)} | — |',
         f'| 转弯（k3） | {_s(tk3, 0)} | {_s(tk3, 1)} | {_s(tk3, 2)} |', '',
         f'1. **donor 管用**：直线卡住从原版的 {f._f(f._median([r["stall_after_exit"] for r in _pick(st, 0)]), True)} 降到 '
         f'{f._f(f._median([r["stall_after_exit"] for r in _pick(st, 1)]), True)}；转弯从 {span([m(tk4, 0), m(tk3, 0)])} 降到 {span([m(tk4, 1), m(tk3, 1)])}（k4、k3 两个槽位各 3 轮，结果一致）。',
         f'2. **不崩**：新 worker 的 {donor_rounds} 轮 donor 里，donor 任务失败 {crashes} 次（数服务器日志里的"任务 N 在 worker M 上失败"；修之前的 3132230、10cadc8 两批按同样的数法是 {old_crashes} 次）。',
         f'3. **多世界（2 个 donor 各 4 核）不比 1 个 8 核 donor 好**：直线 {f._f(f._median([r["stall_after_exit"] for r in _pick(st, 2)]), True)} 对 '
         f'{f._f(f._median([r["stall_after_exit"] for r in _pick(st, 1)]), True)}，转弯 {f._f(f._median([r["stall_after_exit"] for r in _pick(tk3, 2)]), True)} 对 '
         f'{f._f(f._median([r["stall_after_exit"] for r in _pick(tk3, 1)]), True)}。它能跑、稳定，donor 用的核更多（转弯 k3 中位 {f._f(m(tk3, 2, "worker_cores"), digits=2)} 对 '
         f'{f._f(m(tk3, 1, "worker_cores"), digits=2)} 核），但卡住不再下降：瓶颈在 2 核的服务器这一侧，不在 donor 算力。',
         f'4. **卡顿尖峰是原版自己的**：MSPT ≥ {f.SPIKE_MS} ms 的尖峰原版也有（原版最高 {f._f(van_spike, digits=0)} ms，有 donor 的最高 {f._f(don_spike, digits=0)} ms）；'
         f'有 GC 日志的轮里 GC 最长停顿只有 {f._f(gc, digits=0)} ms，不是 GC，是原版生怪时同步取 chunk（codex-server 用 profiler 定位）。详见文末"尖峰"。',
         '5. **上午旧 jar 的那些对比作废**：上午各批 donor 用的是旧 worker jar（构建时没跑 collectWorkerMods），'
         '那些批次里"哪个代码版本更好"的 donor 侧差别（用核、tile/s、卡住、崩溃次数）都不算数，表里没标"· 新 worker"的 donor 行都属于这种。现在驱动会在开跑前核对 worker jar，对不上就拒绝。', '',
         '**只作参考（旧 worker 那批，30a7c22，每种 1 轮）**：', '',
         '| 速度（格/秒） | 卡住（原版 / 1 donor） | 前方已就绪距离，中位（格） | 前方 90 度扇形就绪 |', '| --- | --- | --- | --- |']
    for v in (10, 15):
        a, b = demo.get((v, False)), demo.get((v, True))
        if a and b:
            L.append(f'| {v} | {f._f(a.get("stall_after_exit"), True)} / {f._f(b.get("stall_after_exit"), True)} '
                     f'| {f._f(a.get("forward_ready_median"), digits=0)} → {f._f(b.get("forward_ready_median"), digits=0)} '
                     f'| {f._f(a.get("front_sector_ready_mean"), True)} → {f._f(b.get("front_sector_ready_mean"), True)} |')
    ratios = [demo[(v, True)]['forward_ready_median'] / demo[(v, False)]['forward_ready_median'] for v in (10, 15)
              if (v, True) in demo and (v, False) in demo and demo[(v, False)].get('forward_ready_median')]
    vb = next((x for n, x in best.items() if '原版' in n), None)
    db = next((x for n, x in best.items() if '原版' not in n), None)
    L += ['', f'- 低速演示（10、15 格/秒）两种都不卡，差别在前面准备好了多远：有 donor 时前方已就绪距离是原版的 {span(ratios, False)} 倍。',
          f'- 能扛住的最高速度（出缓存区后卡住 < 5%）：原版 {vb} 格/秒，1 个 donor {db} 格/秒（旧 worker，5 格/秒一档，各 1 轮）。', '',
          f'> 说明：k4 转弯和 k3 转弯两批的原版轮有 {reused} 轮是同槽位上午跑的（复用了 635a362、6b63414 两批的原版格子）。原版不用 worker，'
          '服务器一侧的改动不碰原版生成，所以照样算数，但不是严格的交替。各轮的数在文末"每轮明细"。', '']
    return '\n'.join(L)


def _same_cell(name, old, cell):
    """这一格是不是从 old 那批原样拷过来的（复用通过的格子）：两边的 run.json 一模一样。"""
    a, b = sorted((HERE / name / cell).rglob('run.json')), sorted((HERE / old / cell).rglob('run.json'))
    return bool(a) and len(a) == len(b) and all(x.read_bytes() == y.read_bytes() for x, y in zip(a, b))


md = '# 烟花鞘翅：几套代码对比（11-09）\n\n' + conclusion() + '\n## 下面是全部数据\n\n' + WARN + '\n\n' + f.report(rows, note=NOTE)
(Path(__file__).parent / 'firework-3arm.md').write_text(md)
print(md)
