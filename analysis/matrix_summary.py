"""第 10 步：矩阵结果汇总（docs/step10-plan.md 第 3、5 节）。只读封存好的每轮结果，不起服务器、不画图。

输出（都在 campaign 目录里）：
- matrix-runs.csv：一轮一行；
- matrix-summary.csv：一个配置一行，均值、标准差、95% 置信区间（n < 2 时为空）；
- hypotheses.csv：研究假设逐条的数值、区间和判定（成立 / 不成立 / 数据不足 / 已定），判定规则事先定好；
- x-by-n.csv：X 随 donor 数 N 变化（图 11）。

用法：python -m analysis.matrix_summary --campaign analysis/results/runs/matrix-smoke
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import random
import statistics
from pathlib import Path

from bench.archive import verify

from .run_matrix import SCHEMA

PARAM_KEYS = set().union(*(s['dims'] | s['fixed'] for s in SCHEMA.values())) | {'cheat'}
META_KEYS = {'cell_id', 'experiment', 'repeat', 'concurrent'}
X_TARGET = 2.0      # README 的候选目标：2 个 donor 时 p95 改善 2 倍
X_DONORS = 2
DETECT_TARGET = 0.99
MSPT_LIMIT = 50.0
MIN_RUNS = 5

# t 分布 0.975 分位，自由度 1～30；更大用 1.96。
_T975 = [12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228, 2.201, 2.179, 2.160, 2.145, 2.131,
         2.120, 2.110, 2.101, 2.093, 2.086, 2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042]


def t_interval(values):
    v = [float(x) for x in values]
    if len(v) < 2:
        return None, None
    m, sd = statistics.mean(v), statistics.stdev(v)
    t = _T975[len(v) - 2] if len(v) - 1 <= len(_T975) else 1.96
    half = t * sd / math.sqrt(len(v))
    return m - half, m + half


def bootstrap_ratio_ci(numerator, denominator, n_boot=10000, seed=0):
    """mean(numerator) / mean(denominator) 和自助法 95% 区间。有删失（无穷大）或分母为 0 就不算，返回三个 None。"""
    a, b = [float(x) for x in numerator], [float(x) for x in denominator]
    if not a or not b or not all(map(math.isfinite, a + b)) or statistics.mean(b) <= 0:
        return None, None, None
    est = statistics.mean(a) / statistics.mean(b)
    rng = random.Random(seed)
    ratios = []
    for _ in range(n_boot):
        mb = statistics.mean(rng.choices(b, k=len(b)))
        if mb > 0:
            ratios.append(statistics.mean(rng.choices(a, k=len(a))) / mb)
    ratios.sort()
    lo, hi = ratios[int(0.025 * (len(ratios) - 1))], ratios[int(math.ceil(0.975 * (len(ratios) - 1)))]
    return est, min(lo, est), max(hi, est)


def _binom_cdf(k, n, p):
    """P(X ≤ k)，X ~ Binomial(n, p)。"""
    if p <= 0:
        return 1.0
    if p >= 1:
        return 1.0 if k >= n else 0.0
    total = 0.0
    for i in range(0, k + 1):
        total += math.exp(math.lgamma(n + 1) - math.lgamma(i + 1) - math.lgamma(n - i + 1) + i * math.log(p) + (n - i) * math.log1p(-p))
    return min(1.0, total)


def _bisect(f, lo=0.0, hi=1.0):
    for _ in range(100):
        mid = (lo + hi) / 2
        if f(mid):
            hi = mid
        else:
            lo = mid
    return (lo + hi) / 2


def clopper_pearson(k, n, alpha=0.05):
    if n <= 0:
        return None, None
    lower = 0.0 if k == 0 else _bisect(lambda p: 1 - _binom_cdf(k - 1, n, p) >= alpha / 2)
    upper = 1.0 if k == n else _bisect(lambda p: _binom_cdf(k, n, p) <= alpha / 2)
    return lower, upper


# ---- 收集 ----


def collect(campaign) -> list[dict]:
    rows = []
    for cdir in sorted(p for p in Path(campaign).iterdir() if p.is_dir()):
        for a in sorted(cdir.glob('attempt-*')):
            try:
                m = verify(a, cdir.name)
            except (ValueError, FileNotFoundError, KeyError, json.JSONDecodeError):
                continue
            r = json.loads((a / 'result.json').read_text())
            # 删失的延迟在 JSON 里写成 'inf'（封存不许有无穷大），读回来变成无穷大
            out = {k: math.inf if v == 'inf' else v for k, v in r['output'].items() if k != 'interference'}
            clash = set(out) & (PARAM_KEYS | META_KEYS)
            if clash:
                raise ValueError(f'{cdir.name}：指标和参数同名 {sorted(clash)}，汇总会把它当成参数，驱动要改名')
            rows.append(dict(cell_id=r['cell_id'], experiment=r['experiment'], repeat=r['repeat'],
                             concurrent=m.get('concurrent', False), **r['params'], **out))
            break
    return rows


def _number(v):
    return type(v) in (int, float) and type(v) is not bool


def summarize(rows) -> list[dict]:
    groups = {}
    for r in rows:
        params = tuple(sorted((k, v) for k, v in r.items() if k in PARAM_KEYS))
        groups.setdefault((r['experiment'], params), []).append(r)
    out = []
    for (exp, params), rs in sorted(groups.items(), key=lambda g: json.dumps(g[0], sort_keys=True, default=str)):
        row = dict(experiment=exp, **dict(params), n=len(rs), concurrent_runs=sum(bool(r.get('concurrent')) for r in rs))
        metrics = sorted({k for r in rs for k, v in r.items() if k not in PARAM_KEYS | META_KEYS and (_number(v) or type(v) is bool)})
        for m in metrics:
            vals = [r[m] for r in rs if m in r and r[m] is not None]
            if vals and all(type(v) is bool for v in vals):
                row[m + '.rate'] = sum(vals) / len(vals)
                continue
            finite = [float(v) for v in vals if math.isfinite(float(v))]
            row[m + '.infinite'] = len(vals) - len(finite)
            row[m + '.mean'] = statistics.mean(finite) if finite and len(finite) == len(vals) else (math.inf if vals else None)
            row[m + '.std'] = statistics.stdev(finite) if len(finite) >= 2 else None
            lo, hi = t_interval(finite) if len(finite) == len(vals) else (None, None)
            row[m + '.ci_low'], row[m + '.ci_high'] = lo, hi
        out.append(row)
    return out


# ---- 假设检验 ----


def _flight_x_groups(rows):
    """X 用的那组 flight 轮：2 核、鞘翅、1 个假玩家、不加延迟。返回 (0 donor 的轮, {N: N donor 的轮}, 开关)。"""
    f = [r for r in rows if r['experiment'] == 'flight' and r.get('server_cpus') == 2 and r.get('speed') == 'elytra'
         and r.get('bots', 1) == 1 and r.get('latency_ms', 0) == 0]
    # 有 donor 的那组用哪种开关：有全开用全开；只有一种就用它（演示矩阵只有 deadline）；否则 v0
    seen = {r.get('features', 'v0') for r in f if r['donors'] > 0}
    features = 'all' if 'all' in seen else seen.pop() if len(seen) == 1 else 'v0'
    base = [r for r in f if r['donors'] == 0]
    by_n = {}
    for r in f:
        if r['donors'] > 0 and r.get('features', 'v0') == features:
            by_n.setdefault(r['donors'], []).append(r)
    return base, by_n, features


def _flight_x_rows(rows):
    base, by_n, features = _flight_x_groups(rows)
    return ([r['latency_p95_ms'] for r in base], {n: [r['latency_p95_ms'] for r in rs] for n, rs in by_n.items()}, features)


def _mean_of(rs, key):
    """有这个指标的轮的均值；一轮都没有是 None。"""
    vals = [float(r[key]) for r in rs if _number(r.get(key))]
    return statistics.mean(vals) if vals else None


def _donor_share(rs):
    """donor 分担的比例（fish 11-09 的三类）：导入后服务器接着做完的 ÷（服务器从头生成 + 接着做完 + 读盘），按轮汇总后相除。"""
    keys = ('full_generated', 'full_resumed', 'full_loaded')
    rs = [r for r in rs if all(_number(r.get(k)) for k in keys)]
    total = sum(r[k] for r in rs for k in keys)
    return (sum(r['full_resumed'] for r in rs) / total if total else None), {k: sum(r[k] for r in rs) for k in keys}


def x_by_n(rows) -> list[dict]:
    base, by_n, features = _flight_x_groups(rows)
    out = []
    for n, rs in sorted(by_n.items()):
        est, lo, hi = bootstrap_ratio_ci([r['latency_p95_ms'] for r in base], [r['latency_p95_ms'] for r in rs])
        out.append(dict(donors=n, features=features, n_base=len(base), n_donor=len(rs), x=est, ci_low=lo, ci_high=hi,
                        stall_fraction_base=_mean_of(base, 'stall_fraction'), stall_fraction_donor=_mean_of(rs, 'stall_fraction'),
                        distance_ratio_base=_mean_of(base, 'distance_ratio'), distance_ratio_donor=_mean_of(rs, 'distance_ratio'),
                        donor_share=_donor_share(rs)[0]))
    return out


def _verdict(lo, hi, target, enough):
    if not enough or lo is None:
        return '数据不足'
    if lo >= target:
        return '成立'
    if hi < target:
        return '不成立'
    return '数据不足'


# 用户 11-09 决定第 8 步（作弊检测）暂停：没有 verify 结果时，检出率和 Y 照样列出，判这个
STEP8_PAUSED = '数据不足（第 8 步暂停）'
Z_NO_GPU = '数据不足（要真显卡）'


def hypotheses(rows) -> list[dict]:
    out = []
    base, by_n, features = _flight_x_rows(rows)
    donor = by_n.get(X_DONORS, [])
    est, lo, hi = bootstrap_ratio_ci(base, donor)
    note = f'2 核、鞘翅、1 个假玩家、开关 {features}；0 donor {len(base)} 轮，{X_DONORS} donor {len(donor)} 轮'
    if base and donor and est is None:
        note += '；有删失（p95 为无穷大），比值不硬算'
    out.append(dict(item='X', value=est, ci_low=lo, ci_high=hi,
                    verdict=_verdict(lo, hi, X_TARGET, min(len(base), len(donor)) >= MIN_RUNS and est is not None),
                    rule=f'自助法 95% 区间下限 ≥ {X_TARGET} 成立；上限 < {X_TARGET} 不成立；跨过或 n < {MIN_RUNS} 数据不足', note=note))
    out += _stall_and_share(*_flight_x_groups(rows))
    mspt = [r for r in rows if r['experiment'] == 'flight' and r.get('server_cpus') == 2 and r.get('mspt_p95') is not None]
    worst = max((r['mspt_p95'] for r in mspt), default=None)
    out.append(dict(item='MSPT<50ms', value=worst, ci_low=None, ci_high=None,
                    verdict='数据不足' if worst is None else ('成立' if worst < MSPT_LIMIT else '不成立'),
                    rule='2 核所有 flight 轮里"每秒 MSPT 均值的 p95"取最差一轮，< 50 成立',
                    note=f'{len(mspt)} 轮；每秒最大值 {max((r.get("mspt_max") or 0 for r in mspt), default=None)}'))
    if not any(r['experiment'] == 'verify' for r in rows):
        out.append(dict(item='detect', value=None, ci_low=None, ci_high=None, verdict=STEP8_PAUSED,
                        rule='Clopper–Pearson 95% 下限 ≥ 99% 成立（第 8 步恢复后再测）', note='用户 11-09 决定第 8 步暂停，没有 verify 实验'))
        out.append(dict(item='Y', value=None, ci_low=None, ci_high=None, verdict=STEP8_PAUSED,
                        rule='诚实 donor 的 verify 至少 5 轮即给出数值（第 8 步恢复后再测）', note='用户 11-09 决定第 8 步暂停，没有 verify 实验'))
    cheats = sorted({r['cheat'] for r in rows if r['experiment'] == 'verify' and r.get('cheat') not in (None, 'none')})
    for c in cheats:
        rs = [r for r in rows if r['experiment'] == 'verify' and r.get('cheat') == c]
        k, n = sum(bool(r.get('detected')) for r in rs), len(rs)
        lo, hi = clopper_pearson(k, n)
        v = '成立' if lo >= DETECT_TARGET else '不成立' if hi < DETECT_TARGET else '数据不足'
        out.append(dict(item=f'detect:{c}', value=k / n, ci_low=lo, ci_high=hi, verdict=v,
                        rule='Clopper–Pearson 95% 下限 ≥ 99% 成立；上限 < 99% 不成立；其余数据不足',
                        note=f'{k}/{n} 次抓到，默认作弊强度；少量塞矿的下限见第 8 步报告'))
    ys = [r['y'] for r in rows if r['experiment'] == 'verify' and r.get('cheat') == 'none' and r.get('y') is not None]
    lo, hi = t_interval(ys)
    if any(r['experiment'] == 'verify' for r in rows):
        out.append(dict(item='Y', value=statistics.mean(ys) if ys else None, ci_low=lo, ci_high=hi,
                        verdict='已定' if len(ys) >= MIN_RUNS else '数据不足',
                        rule=f'诚实 donor 的 verify 至少 {MIN_RUNS} 轮即给出数值（Y 是待定的数，不是门槛）', note=f'{len(ys)} 轮'))
    zs = [r['z'] for r in rows if r['experiment'] == 'frametime' and r.get('z') is not None]
    lo, hi = t_interval(zs)
    out.append(dict(item='Z', value=statistics.mean(zs) if zs else None, ci_low=lo, ci_high=hi,
                    verdict='已定' if len(zs) >= MIN_RUNS else Z_NO_GPU if not zs else '数据不足',
                    rule=f'真实电脑上至少 {MIN_RUNS} 段挂机即给出数值', note='这台机器测不了（软件渲染），要有显卡的电脑' if not zs else f'{len(zs)} 段'))
    return out


def _pct(v):
    return '—' if v is None else f'{v:.0%}'


def _stall_and_share(base, by_n, features):
    """和 X 并列的两条描述性数字（11-09）：卡住占比、donor 分担的比例。不是事先定的门槛，判定写"记录"，没有数据写"数据不足"。"""
    donor = by_n.get(X_DONORS, [])
    stall = _mean_of(donor, 'stall_fraction')
    note = (f'{X_DONORS} donor 卡住 {_pct(stall)}（{_mean_of(donor, "stall_s") or 0:.1f} 秒），0 donor 卡住 {_pct(_mean_of(base, "stall_fraction"))}'
            f'（{_mean_of(base, "stall_s") or 0:.1f} 秒）；实际 / 理想距离 {_pct(_mean_of(base, "distance_ratio"))} → {_pct(_mean_of(donor, "distance_ratio"))}；'
            f'开关 {features}')
    share, sums = _donor_share(donor)
    return [dict(item='stall', value=stall, ci_low=None, ci_high=None, verdict='数据不足' if stall is None else '记录',
                 rule='卡住占比 = 假玩家停在没就绪 chunk 前面的时间 ÷ 飞行时长（第 4 步），和 X 并列，不判成立', note=note),
            dict(item='donor_share', value=share, ci_low=None, ci_high=None, verdict='数据不足' if share is None else '记录',
                 rule='导入后服务器接着做完（full_resumed）÷ 三类之和，不把 resumed 算成服务器自己生成的',
                 note=f'{X_DONORS} donor 的轮合计：服务器自己生成 {sums["full_generated"]}，导入后接着做完 {sums["full_resumed"]}，'
                      f'读盘 {sums["full_loaded"]}')]


# ---- 写表 ----


def _write(path, rows):
    keys = []
    for r in rows:
        keys += [k for k in r if k not in keys]
    with Path(path).open('w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=keys or ['empty'])
        w.writeheader()
        for r in rows:
            w.writerow({k: '' if r.get(k) is None else r.get(k) for k in keys})


def write_tables(campaign) -> dict:
    campaign = Path(campaign)
    rows = collect(campaign)
    tables = {'matrix-runs.csv': rows, 'matrix-summary.csv': summarize(rows),
              'hypotheses.csv': hypotheses(rows), 'x-by-n.csv': x_by_n(rows)}
    for name, t in tables.items():
        _write(campaign / name, t)
    return tables


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--campaign', required=True)
    args = parser.parse_args()
    write_tables(args.campaign)
