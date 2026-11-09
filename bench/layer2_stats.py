"""第 4 步：飞行基线的离线统计。定义见 docs/step4-plan.md 第 2、5.2 节。

输入是 bench mod 写的 seconds.jsonl、chunks.jsonl 和宿主机 cgroup 采样；只读归档，不连服务器。
chunks.jsonl 每行：{"t_ms": 起飞后墙钟毫秒, "cx": int, "cz": int, "ev": "enter" | "ready" | "leave"}，按时间顺序。
"""
import json
import math
import statistics
from pathlib import Path

EVENTS = ('enter', 'ready', 'leave')


def load_jsonl(path):
    with Path(path).open() as stream:
        return [json.loads(line) for line in stream if line.strip()]


def percentile(values, q):
    """最近秩分位数。删失值传 math.inf，结果因此是保守的上界。"""
    if not values:
        raise ValueError('没有数据，不能算分位数')
    if not 0 < q <= 1:
        raise ValueError('分位数要在 (0, 1] 内')
    ordered = sorted(values)
    return ordered[max(1, math.ceil(q * len(ordered))) - 1]


def visits(events, end_ms, tail_exclude_ms=10_000):
    """把事件流拆成"一次进视野"。

    - 进视野时已就绪：延迟 0（mod 会在同一时刻连发 enter 和 ready）。
    - 出视野或飞行结束时还没就绪：删失，延迟记 inf。
    - 最后 tail_exclude_ms 内才进来的，不论就绪与否都丢掉。
    - 不在视野里时的 ready 事件忽略。
    """
    open_visits = {}
    done = []
    last = -math.inf
    for e in events:
        t, key, kind = e['t_ms'], (e['cx'], e['cz']), e['ev']
        if kind not in EVENTS:
            raise ValueError(f'未知事件：{kind}')
        if t < last:
            raise ValueError(f'事件时间倒退：{t} < {last}')
        last = t
        visit = open_visits.get(key)
        if kind == 'enter':
            if visit is not None:
                raise ValueError(f'chunk {key} 重复进入视野')
            open_visits[key] = dict(cx=key[0], cz=key[1], enter_ms=t, ready_ms=None)
        elif kind == 'ready':
            if visit is not None and visit['ready_ms'] is None:
                visit['ready_ms'] = t
        else:
            if visit is None:
                raise ValueError(f'chunk {key} 没进视野就离开')
            done.append(open_visits.pop(key))
    if last > end_ms:
        raise ValueError('事件晚于飞行结束时间')
    done.extend(open_visits.values())
    result = []
    for v in sorted(done, key=lambda v: (v['enter_ms'], v['cx'], v['cz'])):
        if v['enter_ms'] > end_ms - tail_exclude_ms:
            continue
        ready = v['ready_ms']
        result.append(dict(cx=v['cx'], cz=v['cz'], enter_ms=v['enter_ms'],
                           latency_ms=math.inf if ready is None else float(ready - v['enter_ms']),
                           censored=ready is None))
    return result


def _usage_at(samples, t_ns):
    for (ta, ua), (tb, ub) in zip(samples, samples[1:]):
        if ta <= t_ns <= tb:
            return ua if tb == ta else ua + (ub - ua) * (t_ns - ta) / (tb - ta)
    raise ValueError('采样没有覆盖这个时间点')


def cpu_per_second(samples, t0_ns, n_seconds):
    """宿主机 cgroup 采样 [(monotonic_ns, usage_usec)] → 起飞后每秒平均用了几个核（线性插值）。"""
    samples = sorted(samples)
    for (ta, ua), (tb, ub) in zip(samples, samples[1:]):
        if ub < ua:
            raise ValueError('CPU 计数倒退')
    if not samples or samples[0][0] > t0_ns or samples[-1][0] < t0_ns + n_seconds * 1_000_000_000:
        raise ValueError('采样没有覆盖整个飞行')
    out = []
    for i in range(n_seconds):
        a = _usage_at(samples, t0_ns + i * 1_000_000_000)
        b = _usage_at(samples, t0_ns + (i + 1) * 1_000_000_000)
        out.append((b - a) / 1_000_000)
    return out


def run_summary(seconds, chunk_visits, cpu):
    """一轮飞行 → 一行汇总。"""
    if [s['t_s'] for s in seconds] != list(range(1, len(seconds) + 1)):
        raise ValueError('逐秒数据不连续')
    if len(cpu) != len(seconds):
        raise ValueError('CPU 采样和逐秒数据的长度不一致')
    void = [s['void_rate'] for s in seconds]
    lat = [v['latency_ms'] for v in chunk_visits]
    censored = sum(v['censored'] for v in chunk_visits)
    return dict(
        seconds=len(seconds),
        void_mean=statistics.fmean(void),
        void_p95=percentile(void, .95),
        void_max=max(s['void_rate_max'] for s in seconds),
        visits=len(lat),
        censored_fraction=censored / len(lat) if lat else None,
        latency_p50_ms=percentile(lat, .50) if lat else None,
        latency_p95_ms=percentile(lat, .95) if lat else None,
        latency_p99_ms=percentile(lat, .99) if lat else None,
        mspt_mean=statistics.fmean(s['mspt_mean'] for s in seconds),
        mspt_max=max(s['mspt_max'] for s in seconds),
        ticks_per_s=statistics.fmean(s['ticks'] for s in seconds),
        cpu_cores_mean=statistics.fmean(cpu),
    )


def config_summary(runs, keys, near_zero=0.005):
    """同一配置的 5 轮。均值接近 0 时不报 CV，只报绝对标准差。有 inf 时如实传下去。"""
    if len(runs) != 5:
        raise ValueError('每个配置需要 5 个有效轮次')
    out = {}
    for key in keys:
        values = [r[key] for r in runs]
        if any(v is None for v in values):
            raise ValueError(f'{key} 有缺失值')
        finite = all(math.isfinite(v) for v in values)
        mean = statistics.fmean(values) if finite else math.inf
        std = statistics.stdev(values) if finite else None
        out[key] = dict(
            n=len(values), mean=mean, median=statistics.median(values), std=std,
            cv=std / mean if finite and abs(mean) >= near_zero else None,
            range=max(values) - min(values) if finite else math.inf,
            min=min(values), max=max(values), infinite_runs=sum(not math.isfinite(v) for v in values),
        )
    return out


def significant(value, base_mean, base_std):
    """和基线比有没有显著变化：差值超过基线 5 次标准差的 2 倍（第 4 步计划 5.2）。"""
    return abs(value - base_mean) > 2 * base_std
