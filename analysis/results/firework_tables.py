"""重新生成 analysis/results/firework-3arm.md：python analysis/results/firework_tables.py（归档在 firework-runs/，按 codex-audit K1～K3 修好的算法重算）。"""
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

rows = []
for name, code in CAMPAIGNS:
    if (HERE / name).exists():
        rows += [dict(r, code=code, slot=name.split('-')[0].replace('u1', 'k4')) for r in f.rows_recomputed(HERE / name)]
WARN = ('> **注意（11-09 11:00 ET 发现）**：下面这些批次里，donor 用的都是槽位里早上留下的旧 worker jar（跑批只跑了 collectMods，没跑 collectWorkerMods），'
        '标的代码版本只对服务器（coordinator）一侧成立。k2 的旧 worker（含 PREFETCHED）和 k3、k4 的（更旧，两个相同）也不一样。所以不同代码之间 donor 侧的差别'
        '（用核、tile/s、卡住、PalettedContainer 崩溃次数）都不能当成代码的差别；原版的数、尖峰、同槽位"donor 对原版"还算数。用新 worker 重跑的批次标 "· 新 worker"。')
md = '# 烟花鞘翅：几套代码对比（11-09）\n\n' + WARN + '\n\n' + f.report(rows, note=NOTE)
(Path(__file__).parent / 'firework-3arm.md').write_text(md)
print(md)
