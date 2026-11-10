"""重新生成 analysis/results/firework-3arm.md：python analysis/results/firework_tables.py（归档在 firework-runs/，按 codex-audit K1～K3 修好的算法重算）。"""
import re
from pathlib import Path
from analysis import firework as f

HERE = Path(__file__).parent / 'firework-runs'
CAMPAIGNS = [('k2-637c2de-ufw-main-8', '637c2de'), ('k3-30a7c22-ufw-main-8', '30a7c22'), ('u1-30a7c22-ufw-sweep8-lo', '30a7c22'),
             ('k3-2359f7d-ufw-main-8', '2359f7d'), ('k3-2359f7d-ufw-mspt', '2359f7d'), ('k2-10cadc8-ufw-turn-first', '10cadc8'),
             ('u1-10cadc8-ufw-straight3', '10cadc8'), ('u1-635a362-ufw-turn-first', '635a362'), ('k3-6b63414-ufw-mw-turn', '6b63414'),
             ('k2-03b2981w-ufw-mw-straight', '03b2981 · 新 worker'), ('k3-03b2981w-ufw-mw-turn', '03b2981 · 新 worker'),
             ('u1-03b2981w-ufw-turn-first', '03b2981 · 新 worker'),
             ('k3-f19d087sb-ufw-scan-straight', 'f19d087'), ('u1-f19d087sb-ufw-scan-turn', 'f19d087')]
SWEEP = [('k3-e7fed5fsw-ufw-sweep-c6', 'e7fed5f'), ('u1-e7fed5fsw-ufw-sweep-c10', 'e7fed5f'), ('k2-e7fed5fsw-ufw-sweep-mc', 'e7fed5f')]
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


def _hopeless(name, cell):
    """这一格（通过的那次尝试）起飞后服务器日志里"排队的 N 个 tile 按现在的吞吐来不及"的 N 之和。"""
    tries = sorted((HERE / name / cell).glob('attempt-*'))
    p = tries[-1] / 'server-log.txt' if tries else None
    return sum(int(x) for x in re.findall(r'排队的 (\d+) 个 tile 按现在的吞吐来不及', p.read_text(errors='replace'))) if p and p.exists() else None


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
         ''] + scan_budget() + [
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


def scan_budget():
    """扫描预算 500（旧默认）对 2000（部署值）（调度 11-09 下午）：main f19d087，1×8 donor，同槽位交替各 3 轮，原版 1 轮。"""
    st, tn = 'k3-f19d087sb-ufw-scan-straight', 'u1-f19d087sb-ufw-scan-turn'
    if st not in rows_of or tn not in rows_of:
        return []
    arm = lambda name, b: [r for r in rows_of[name] if r.get('donors') and r.get('scan_budget_us') == b]
    van = lambda name: [r for r in rows_of[name] if not r.get('donors')]
    st_ = lambda rs, k, pct=False, d=1: f._stat(rs, k, pct, d)
    L = ['**扫描预算（部署包里唯一的参数改动）**：-Dmcdonate.demandScanBudgetMicros 500（代码默认）对 2000（部署 .env）。main f19d087，'
         '1 个 8 核 donor，开关和提前量同部署；同槽位 500、2000 交替各 3 轮，原版 1 轮作参照；每轮跑完核对过服务器用的就是这个值。', '',
         '| 场景（槽位） | 预算 | 卡住占比 | 前方空白时长 中位 / p90（秒） | 前方已就绪（中位，格） | 实际 / 理想速度 | tile/s | worker 核数 | MSPT p95 / max | 任务失败 |',
         '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |']
    for scen, name in (('直线（k3）', st), ('转弯（k4）', tn)):
        fails = _crashes(name)
        L.append(f'| {scen} | 原版 | {st_(van(name), "stall_after_exit", True)} | {st_(van(name), "blank_median_s")} / {st_(van(name), "blank_p90_s")} | {st_(van(name), "forward_ready_median", d=0)} '
                 f'| {st_(van(name), "speed_ratio_after_exit", True)} | — | — | {st_(van(name), "mspt_p95")} / {st_(van(name), "mspt_max", d=0)} | — |')
        for b in (500, 2000):
            rs = arm(name, b)
            L.append(f'| {scen} | {b} µs | {st_(rs, "stall_after_exit", True)} | {st_(rs, "blank_median_s")} / {st_(rs, "blank_p90_s")} | {st_(rs, "forward_ready_median", d=0)} '
                     f'| {st_(rs, "speed_ratio_after_exit", True)} | {st_(rs, "tile_s", d=2)} | {st_(rs, "worker_cores")} '
                     f'| {st_(rs, "mspt_p95")} / {st_(rs, "mspt_max", d=0)} | {"全批 " + str(fails) + " 次" if b == 500 else "（同上）"} |')
    m = lambda rs, k='stall_after_exit': f._median([r[k] for r in rs if isinstance(r.get(k), (int, float))])
    L += ['', f'- 直线：2000 把卡住从 {f._f(m(arm(st, 500)), True)} 降到 {f._f(m(arm(st, 2000)), True)}，donor 做完的 tile 多了一倍'
              f'（{f._f(m(arm(st, 500), "tile_s"), digits=2)} → {f._f(m(arm(st, 2000), "tile_s"), digits=2)} 个/秒），前方准备好的距离 '
              f'{f._f(m(arm(st, 500), "forward_ready_median"), digits=0)} → {f._f(m(arm(st, 2000), "forward_ready_median"), digits=0)} 格。'
              '和 codex-server 1 个 4 核 donor 的 17% → 4% 一致：瓶颈是服务器每次扫描找需求的时间预算，不是 donor 算力。',
          f'- 转弯：{f._f(m(arm(tn, 500)), True)} → {f._f(m(arm(tn, 2000)), True)}，改善小（tile/s 也多了 '
          f'{f._f(m(arm(tn, 500), "tile_s"), digits=2)} → {f._f(m(arm(tn, 2000), "tile_s"), digits=2)}，多做的没怎么变成少卡，原因没查）。',
          '- 500 这一臂和上午新 worker（03b2981）的 1×8 对得上（直线 16%，转弯 30%～31%），两批之间可比。',
          f'- 代价：服务器 MSPT p95 直线 {f._f(m(arm(st, 500), "mspt_p95"))} → {f._f(m(arm(st, 2000), "mspt_p95"))} ms、'
          f'转弯 {f._f(m(arm(tn, 500), "mspt_p95"))} → {f._f(m(arm(tn, 2000), "mspt_p95"))} ms，离 50 ms 很远。部署值 2000 保留。',
          '- f19d087 的 worker 有"重导出外圈 chunk 不在内存"的 bug（v2.1 修了），会偶尔让一个任务失败重排；这两批数下来 '
          f'{_crashes(st) + _crashes(tn)} 次，对比不受影响。', '']
    return L


def _same_cell(name, old, cell):
    """这一格是不是从 old 那批原样拷过来的（复用通过的格子）：两边的 run.json 一模一样。"""
    a, b = sorted((HERE / name / cell).rglob('run.json')), sorted((HERE / old / cell).rglob('run.json'))
    return bool(a) and len(a) == len(b) and all(x.read_bytes() == y.read_bytes() for x, y in zip(a, b))


AB_COLS = (('前方空白 中位（秒）', 'blank_median_s', False, 1), ('p90（秒）', 'blank_p90_s', False, 1),
           ('视野里等到就绪', 'blank_ready_share', True, 1), ('前方已就绪（中位，格）', 'forward_ready_median', False, 0),
           ('卡住（参考）', 'stall_after_exit', True, 1), ('交回被接受', 'imports_accepted', False, 0), ('hopeless 丢弃', 'hopeless', False, 0),
           ('飞过丢弃', 'stale_dropped', False, 0), ('worker 核', 'worker_cores', False, 2), ('tile/s', 'tile_s', False, 2),
           ('MSPT p95', 'mspt_p95', False, 1), ('MSPT max', 'mspt_max', False, 0),
           ('≤48 chunk 空白中位', 'blank_near_median_s', False, 1), ('≤48 等到就绪', 'blank_near_ready_share', True, 1),
           ('>48 chunk 空白中位', 'blank_far_median_s', False, 1),
           ('站着时后台导入', 'stand_bg_imported', False, 0), ('站着 MSPT p95', 'stand_mspt_p95', False, 1), ('站着 MSPT max', 'stand_mspt_max', False, 0))


def ab(title, intro, campaigns, arm):
    """同槽位 A/B 的通用表：campaigns 是 firework-runs 下的目录名，arm(行) 给臂的名字（None 不要）。"""
    rows = []
    for name in campaigns:
        if (HERE / name).exists():
            for r in f.rows_recomputed(HERE / name):
                r['hopeless'] = _hopeless(name, r['cell_id']) if r.get('donors') else None
                r['_arm'] = arm(r, name)
                rows.append(r)
    rows = [r for r in rows if r['_arm']]
    if not rows:
        return []
    names = sorted({r['_arm'] for r in rows}, key=lambda n: (not n.startswith('原版'), n))
    L = [f'## {title}', '', intro, '', '| 臂 | ' + ' | '.join(c[0] for c in AB_COLS) + ' |', '| --- ' * (len(AB_COLS) + 1) + '|']
    for n in names:
        rs = [r for r in rows if r['_arm'] == n]
        L.append(f'| {n} | ' + ' | '.join(f._stat(rs, k, pct, d) for _, k, pct, d in AB_COLS) + ' |')
    fails = sum(_crashes(c) for c in campaigns if (HERE / c).exists())
    return L + ['', f'任务失败 {fails} 次。', '']


def experiments_evening():
    L = ab('前方优先扫描 demandForwardFirst（fish-fwd 295dcb2，k3）',
           '2 核，预算 2000，1×8 donor，直线 33.5，lookahead 24、corridor 6；同槽位交替各 3 轮；每轮核对 status 的 config.demandForwardFirst。'
           '开了以后空白中位、p90、前方已就绪三项的范围都不和关的重叠（稳定地好一点），MSPT 基本不变；hopeless 两臂一样多。',
           ['k3-295dcb2fwd-ufw-fwd'], lambda r, n: f"前方优先 {'开' if r.get('forward_first') else '关'}")
    L += ab('玩家票节流 playerTicketThrottle（fish-throttle 7f48ebe，k4）',
            '2 核，预算 2000，1×8 donor，直线 33.5，L16 C6，前方优先关；donor 臂同槽位交替各 3 轮，原版各 1 轮；每轮核对 status 的 '
            'playerTicketThrottle.actual 主世界值。16 没有帮上忙：donor 臂空白不比 4 好、前方已就绪更近、MSPT p95 高约 1.4 ms；原版臂各 1 轮，不下结论。',
            ['u1-7f48ebethr-ufw-throttle'], lambda r, n: f"{'1×8 donor' if r.get('donors') else '原版'} · 节流 {r.get('ticket_throttle')}")
    L += ab('donor 速率估计（fish-rate，k2，按轮切代码交替）',
            '2 核，预算 2000，1×8 donor，直线 33.5，L16 C6。0dfd6c9 那组按调度只跑了 4 格就换成 ba95fde（每格 1～2 轮，粗测）；ba95fde 那组各 3 轮。',
            ['k2-rate-e7fed5f', 'k2-rate-0dfd6c9', 'k2-rate2-e7fed5f', 'k2-rate2-ba95fde'],
            lambda r, n: f"{n.split('-')[-1]} · mc{r.get('max_concurrency')}" + (' · 第二批' if 'rate2' in n else ''))
    for code, note in (('92f40c4', '第一版'), ('f9d27c0', '修了后台导入节流（codex-audit 第十七节）'), ('60efd48', '修了 lanes 回退（含 0163fc8）'),
                       ('097bea7', 'fish-v23，codex-audit 第十八节 X1～X4'), ('1409315', 'fish-v23，后台先沿朝向往前铺；假玩家站着朝航线看'),
                       ('56b4eee', 'v2.3 包：1409315 + df9c0e2 前方样本单独窗口 + 朝向修复')):
        L += ab(f'加速模式 boost（fish-boost {code}，{note}）',
                '2 核，预算 2000，1×8 donor，L16 C6，直线 33.5 格/秒 120 秒；测试环境用 worker 的 /donor boost 代替客户端开关，每轮核对 coordinator status。'
                '"先站 60 秒"是起飞前在原地站着（后台先算周围）；不站的是直接飞。f9d27c0：开的那臂飞完时 lanes 都退到 1，前方变差。',
                sorted(d.name for d in HERE.glob(f'*-{code}boost-*')),
                lambda r, n: ('原版' if not r.get('donors') else f"boost {'开' if r.get('boost') else '关'}")
                + (f" · 先站 {r['stand_s']} 秒" if r.get('stand_s') else ' · 直接飞'))
    return L


def boost_breakdown(name='k3-56b4eeeboost-ufw-boost-face60', title='后台导入过的 chunk 到达时为什么还空白（v2.3 56b4eee，站 60 秒，k3）', notes=None):
    """后台导入过的 chunk 到达时为什么还空白（调度 11-10）：按 chunk 对齐导入、服务器做完、进视野、可发送；邻居导没导入；服务器 CPU。"""
    import json
    from bench.demo.ab import DEFAULT_START
    base = HERE / name
    if not base.exists():
        return []
    sx, sz = DEFAULT_START
    per = {}   # (boost, 区, 类) -> [每轮的值 dict]
    nb = {}    # (boost, 区, 邻居) -> [每轮中位]
    cpu = {}   # (boost, 阶段) -> [每轮核数]
    for cell in sorted(base.iterdir()):
        tries = sorted(cell.glob('attempt-*')) if cell.is_dir() else []
        if not tries or not (tries[-1] / 'manifest.json').exists():
            continue
        a = tries[-1]
        m = json.loads((a / 'manifest.json').read_text())
        if m.get('status') != 'passed' or not m['params'].get('donors'):
            continue
        boost, raw = m['params'].get('boost'), a / 'flight' / 'raw'
        L = lambda n: [json.loads(l) for l in (raw / n).read_text().splitlines() if l.strip()]
        rows, ev, prov, run = L('seconds.jsonl'), L('chunks.jsonl'), L('provenance.jsonl'), json.loads((raw / 'run.json').read_text())
        stages = L('stages.jsonl') if (raw / 'stages.jsonl').exists() else None
        imp, full = {}, {}
        for e in sorted(prov, key=lambda e: e['t_ms']):
            k = (e['cx'], e['cz'])
            if e['kind'] == 'donor':
                imp.setdefault(k, e['t_ms'] / 1000)
            elif e['kind'] in f.FULL_KINDS:
                full.setdefault(k, e['t_ms'] / 1000)
        for zone, kw in (('≤48', dict(max_chunks=f.NEAR_CHUNKS)), ('>48', dict(min_chunks=f.NEAR_CHUNKS + 1))):
            common = dict(run=run, view=10, cache_radius_blocks=(m['params'].get('cache_radius') or 0) * 16, start=DEFAULT_START, **kw)
            b = f.blank_breakdown(ev, prov, rows, stages=stages, **common)
            for cat in ('pre', 'none'):
                per.setdefault((boost, zone, cat), []).append(b[cat])
            g = {'8 个邻居都导入了': [], '有邻居没导入': []}
            for k, te, d, c in f.blank_times(ev, rows, with_keys=True, **common):
                if k in imp and k in full and full[k] > te:
                    ok = all((k[0] + dx, k[1] + dz) in imp and imp[(k[0] + dx, k[1] + dz)] <= te
                             for dx in (-1, 0, 1) for dz in (-1, 0, 1) if dx or dz)
                    g['8 个邻居都导入了' if ok else '有邻居没导入'].append(full[k] - te)
            for kk, v in g.items():
                nb.setdefault((boost, zone, kk), []).append((f._median(v), len(v)))
        cs = [json.loads(l) for l in (a / 'flight' / 'cpu-samples.jsonl').read_text().splitlines() if l.strip()]
        t0 = cs[0]['time_ns']
        dist = lambda r: max(abs(r['x'] - sx), abs(r['z'] - sz))
        stand = m['params'].get('stand_s') or 0
        for phase, ts in (('站着', [r['t_s'] for r in rows if r['t_s'] <= stand]),
                          ('≤48 那段', [r['t_s'] for r in rows if 256 < dist(r) <= f.NEAR_CHUNKS * 16 - 160]),
                          ('>48 那段', [r['t_s'] for r in rows if dist(r) > f.NEAR_CHUNKS * 16])):
            pts = [c for c in cs if ts and min(ts) - 1 <= (c['time_ns'] - t0) / 1e9 <= max(ts)]
            if len(pts) > 1:
                cpu.setdefault((boost, phase), []).append((pts[-1]['usage_usec'] - pts[0]['usage_usec']) / 1e6 / ((pts[-1]['time_ns'] - pts[0]['time_ns']) / 1e9))
    if not per:
        return []
    med = lambda xs: f._median([x for x in xs if x is not None])
    show = lambda v, d=1: '—' if v is None else f'{v:.{d}f}'
    L = [f'## {title}', '',
         '按 chunk 对齐：导入时刻（provenance 里这个 chunk 的第一个 donor 事件）、服务器做完（第一个 resumed / vanilla / loaded）、'
         '进视野（enter）、可发送（ready，getChunkToSend 就绪）。只看前方 90 度扇形、缓存区外的 chunk；"≤48"是离起点 48 个 chunk 以内'
         '（后台走廊半径），"进视野前导入了"多半是加速后台和提前量做的，"没导入"是服务器自己做的。每格是 3 轮各自中位数的中位数。', '',
         '| boost | 区 | 类 | 每轮个数 | 空白中位（秒） | 截尾比例 | 进视野前已做完 | 导入→进视野（秒） | 进视野→做完（秒） | 做完→可发送（秒） '
         '| 进视野→加票 | 加票→读盘完成 | 读盘→full | 进视野→票到 FULL 等级 | 票到 FULL 等级→光照 | 票到 FULL 等级→full |',
         '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |']
    for (boost, zone, cat), bs in sorted(per.items(), key=lambda kv: (kv[0][1], kv[0][0], kv[0][2] != 'pre')):
        L.append(f"| {'开' if boost else '关'} | {zone} | {'进视野前导入了' if cat == 'pre' else '没导入'} | {show(med([b['n'] for b in bs]), 0)} "
                 f"| {show(med([b['blank_median_s'] for b in bs]))} | {show(100 * med([b['censored_share'] for b in bs]), 0)}% "
                 f"| {show(100 * med([b['full_before_enter_share'] for b in bs]), 0)}% | {show(med([b.get('import_to_enter_s') for b in bs]))} "
                 f"| {show(med([b['enter_to_full_s'] for b in bs]))} | {show(med([b['full_to_ready_s'] for b in bs]))} "
                 f"| {show(med([b.get('enter_to_ticket_s') for b in bs]))} | {show(med([b.get('ticket_to_read_s') for b in bs]))} "
                 f"| {show(med([b.get('read_to_full_s') for b in bs]))} | {show(med([b.get('enter_to_ticket_full_s') for b in bs]))} "
                 f"| {show(med([b.get('ticket_full_to_light_s') for b in bs]))} | {show(med([b.get('ticket_full_to_full_s') for b in bs]))} |")
    L += ['', '进视野→做完，按 8 个邻居是不是都在进视野前导入了分开（秒，括号是每轮个数）：', '',
          '| boost | 区 | 8 个邻居都导入了 | 有邻居没导入 |', '| --- | --- | --- | --- |']
    for boost in sorted({k[0] for k in nb}):
        for zone in ('≤48', '>48'):
            a8, sm = nb.get((boost, zone, '8 个邻居都导入了'), []), nb.get((boost, zone, '有邻居没导入'), [])
            L.append(f"| {'开' if boost else '关'} | {zone} | {show(med([x for x, _ in a8]))}（{show(med([n for _, n in a8]), 0)}） "
                     f"| {show(med([x for x, _ in sm]))}（{show(med([n for _, n in sm]), 0)}） |")
    L += ['', '服务器容器 CPU（核，2 核上限；cgroup 采样）：', '', '| boost | 站着 | ≤48 那段 | >48 那段 |', '| --- | --- | --- | --- |']
    for boost in sorted({k[0] for k in cpu}):
        L.append(f"| {'开' if boost else '关'} | " + ' | '.join(show(med(cpu.get((boost, ph), [])), 2) for ph in ('站着', '≤48 那段', '>48 那段')) + ' |')
    if notes:
        L += [''] + notes + ['']
    return L


def sweep():
    """直线参数扫描（调度 11-09 傍晚，用户定的目标：直线往前飞时前方空白 chunk 的加载速度）。主指标前方空白时长、前方已就绪距离，按空白时长排序。"""
    have = [(n, code) for n, code in SWEEP if (HERE / n).exists()]
    if not have:
        return []
    by = {}
    for name, code in have:
        slot = name.split('-')[0].replace('u1', 'k4')
        for r in f.rows_recomputed(HERE / name):
            r['hopeless'] = _hopeless(name, r['cell_id']) if r.get('donors') else None
            key = ('原版',) if not r.get('donors') else (r.get('lookahead'), r.get('corridor_chunks') or 6, r.get('max_concurrency') or 4)
            by.setdefault((slot, key), []).append(r)
    m = lambda rs, k: f._median([r[k] for r in rs if isinstance(r.get(k), (int, float))])
    order = sorted(by.items(), key=lambda kv: (m(kv[1], 'blank_median_s') is None, m(kv[1], 'blank_median_s') or 0))
    L = ['## 直线参数扫描：前方空白 chunk 加载多快', '',
         '用户定的目标（11-09 傍晚）：直线往前飞时，前方空白 chunk 的加载速度；转弯、多人、尖峰不管。main e7fed5f，扫描预算 2000，'
         '用户同款 2 核，1 个 8 核 donor，烟花直线 33.5 格/秒。同槽位交替，每格 2 轮，原版 1 轮。按前方空白时长（中位）从短到长排。', '',
         '> **前方空白时长**：进视野那一刻在飞行方向 ±45° 以内、又在缓存区外的 chunk，从进视野到服务器可发送（getChunkToSend 就绪）的秒数。'
         '没等到就绪就离开视野、或到结束也没就绪的，记到那一刻为止（截尾，实际只会更长），所以中位数、p90 都是下限；'
         '"视野里等到就绪"是没被截尾的比例。多轮写中位数（最低～最高）。不同槽位之间只能参考，同一槽位里的才算数。', '',
         '| 排名 | 槽位 | lookahead | corridorChunks | maxConcurrency | 前方空白时长 中位（秒） | p90（秒） | 视野里等到就绪 | 前方已就绪距离（中位，格） | 卡住（参考） | donor 交回被接受 | hopeless 丢弃 | 飞过丢弃 | tile/s | MSPT p95 |',
         '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |']
    for i, ((slot, key), rs) in enumerate(order, 1):
        cols = ['原版', '—', '—'] if key == ('原版',) else [str(k) for k in key]
        L.append(f'| {i} | {slot} | ' + ' | '.join(cols) + f' | {f._stat(rs, "blank_median_s")} | {f._stat(rs, "blank_p90_s")} '
                 f'| {f._stat(rs, "blank_ready_share", True)} | {f._stat(rs, "forward_ready_median", digits=0)} '
                 f'| {f._stat(rs, "stall_after_exit", True)} | {f._stat(rs, "imports_accepted", digits=0)} | {f._stat(rs, "hopeless", digits=0)} '
                 f'| {f._stat(rs, "stale_dropped", digits=0)} | {f._stat(rs, "tile_s", digits=2)} | {f._stat(rs, "mspt_p95")} |')
    fails = sum(_crashes(n) for n, _ in have)
    L += ['', '- 同一槽位里 lookahead 16/24/32 的差别都在轮间波动以内（调度：lookahead 保持 16）。corridor 6（k3）和 10（k4）在不同槽位，'
          '还定不了，要同槽位交替补跑。k2 上 maxConcurrency 6 比 4 差（交回的少、空白长）；两臂 hopeless 丢弃都约 420～440 个每轮，'
          'donor 8 核只用 2 核多，没被喂饱（fish 认为是"速率低估循环"，fish-rate 0dfd6c9 在修，k2 在测）。',
          '- hopeless 丢弃：起飞后服务器日志"排队的 N 个 tile 按现在的吞吐来不及，丢掉，交给服务器"的 N 之和；飞过丢弃：status 的 stale.dropped；'
          '交回被接受：status 的 accepted。']
    L += ['', f'任务失败（coordinator 日志"任务 N 在 worker M 上失败"）：这几批共 {fails} 次。', '']
    return L


STAGE_NOTES = [
    '读法（11-10 kexu，用下面"按阶段"那一轮核对过）：',
    '- 导入过的前方 chunk 没有一个在进视野前被做完（"进视野前已做完" 0%），时间全在"进视野→做完"：≤48 那段 4～5 秒，后面 2.3～2.6 秒。',
    '- 拆开以后（下一节，bench 的 stages.jsonl）：读盘早就完了（第一次进加载等级后 0.03～0.07 秒，比进视野早 2～4 秒），'
    '**时间几乎全花在等票等级到 FULL（33）**：进视野→票到 FULL 等级 ≤48 那段 4.2 秒、后面 2.2 秒；票一到，0.13 秒就做完 FULL、'
    '再 0.6 秒可发送。所以卡的是玩家票的准入（DistanceManager 的节流队列），不是读盘、不是续做光照、也基本不是等邻居'
    '（8 个邻居都导入了的照样要这么久，有邻居没导入的只是少数）。和 codex-server"前方空白大头是原版玩家票排队"一致。',
    '- 本节第一版（b83e3f9）把原因写成"服务器读盘续完、光照"，是在没有 stages 时按 CPU 推的，**是错的**，以这里为准。',
    '- 服务器 CPU：站着时 0.2～0.35 核，飞起来约 1.55 核（2 核上限）。站着时有空闲，但只要票等级没到 33，导入的半成品就不会被做完。',
    '- 加速的好处主要是把"没导入（服务器从头生成，空白 10～11 秒）"变成"进视野前导入了"（≤48 那段每轮 193 → 289 个、没导入 351 → 255 个）。',
    '- 可试的方向：给走廊里已导入的 chunk 提前加一张 FULL 等级的票（绕过玩家票节流队列），到达时就只剩发送；'
    '之前的 playerTicketThrottle 4 → 16 没见效，可能是名额以外还有"等 5×5 FULL 才放下一个"的条件。',
]

md = '# 烟花鞘翅：几套代码对比（11-09）\n\n' + '\n'.join(boost_breakdown(notes=STAGE_NOTES))
md += '\n'.join(boost_breakdown('k3-stages2-ufw-stages-smoke', '按阶段拆开（bench stages.jsonl，v2.3 56b4eee + kexu，站 60 秒、boost 开，k3，1 轮）')) + '\n'.join(experiments_evening()) + '\n'.join(sweep()) + conclusion() + '\n## 下面是全部数据\n\n' + WARN + '\n\n' + f.report(rows, note=NOTE)
(Path(__file__).parent / 'firework-3arm.md').write_text(md)
print(md)
