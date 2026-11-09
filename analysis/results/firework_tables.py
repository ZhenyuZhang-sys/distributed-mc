"""重新生成 analysis/results/firework-3arm.md：python analysis/results/firework_tables.py（归档在 firework-runs/，按 codex-audit K1～K3 修好的算法重算）。"""
from pathlib import Path
from analysis import firework as f

HERE = Path(__file__).parent / 'firework-runs'
CAMPAIGNS = [('k2-637c2de-ufw-main-8', '637c2de'), ('k3-30a7c22-ufw-main-8', '30a7c22'), ('u1-30a7c22-ufw-sweep8-lo', '30a7c22'),
             ('k3-2359f7d-ufw-main-8', '2359f7d'), ('k2-3132230-ufw-turn-first', '3132230'), ('u1-3132230-ufw-straight3', '3132230')]
NOTE = ('用户同款服务器：2 核、3G、-Xmx2G、视距 10/6；donor 8 核。几个槽位并行跑，粗测。代码：637c2de = fish-opt+kexu，'
        '30a7c22 / 2359f7d / 3132230 = fish-perf 各版本 + kexu。前方已就绪距离、扇形按 codex-audit K1～K3 修好的算法从归档重算'
        '（初始视野补上、route 按航段取方向、DDA 逐 chunk），单位格。多轮的格子写中位数（最低～最高，几轮）')

rows = []
for name, code in CAMPAIGNS:
    if (HERE / name).exists():
        rows += [dict(r, code=code) for r in f.rows_recomputed(HERE / name)]
md = '# 烟花鞘翅：几套代码对比（11-09）\n\n' + f.report(rows, note=NOTE)
(Path(__file__).parent / 'firework-3arm.md').write_text(md)
print(md)
