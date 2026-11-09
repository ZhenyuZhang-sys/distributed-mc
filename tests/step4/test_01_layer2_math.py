"""第 4 步 4.4：飞行基线的离线统计。纯逻辑，不起服务器。"""
import math

import pytest

from bench.layer2_stats import (
    cpu_per_second,
    percentile,
    run_summary,
    significant,
    config_summary,
    visits,
)


def ev(t_ms, cx, cz, kind):
    return dict(t_ms=t_ms, cx=cx, cz=cz, ev=kind)


# ---- 分位数：删失算无穷大，最近秩 ----

def test_percentile_nearest_rank():
    assert percentile([1, 2, 3, 4], .5) == 2
    assert percentile([1, 2, 3, 4], .95) == 4
    assert percentile([5], .99) == 5


def test_percentile_with_censored_is_upper_bound():
    values = [10.0] * 94 + [math.inf] * 6
    assert percentile(values, .5) == 10.0
    assert percentile(values, .95) == math.inf


def test_percentile_empty_rejected():
    with pytest.raises(ValueError):
        percentile([], .5)


# ---- 一个 chunk 的"一次进视野" ----

def test_ready_after_enter():
    v = visits([ev(1000, 0, 0, 'enter'), ev(1500, 0, 0, 'ready'), ev(9000, 0, 0, 'leave')], end_ms=10_000, tail_exclude_ms=0)
    assert v == [dict(cx=0, cz=0, enter_ms=1000, latency_ms=500.0, censored=False)]


def test_ready_on_entry_is_zero():
    v = visits([ev(1000, 1, 2, 'enter'), ev(1000, 1, 2, 'ready')], end_ms=10_000, tail_exclude_ms=0)
    assert v[0]['latency_ms'] == 0 and not v[0]['censored']


def test_left_before_ready_is_censored():
    v = visits([ev(1000, 0, 0, 'enter'), ev(4000, 0, 0, 'leave')], end_ms=10_000, tail_exclude_ms=0)
    assert v[0]['censored'] and v[0]['latency_ms'] == math.inf


def test_reenter_counts_twice():
    events = [ev(0, 0, 0, 'enter'), ev(100, 0, 0, 'ready'), ev(200, 0, 0, 'leave'),
              ev(300, 0, 0, 'enter'), ev(300, 0, 0, 'ready')]
    v = visits(events, end_ms=10_000, tail_exclude_ms=0)
    assert [x['latency_ms'] for x in v] == [100.0, 0.0]


def test_ready_while_out_of_view_ignored():
    v = visits([ev(50, 0, 0, 'ready'), ev(100, 0, 0, 'enter'), ev(400, 0, 0, 'ready')], end_ms=10_000, tail_exclude_ms=0)
    assert [x['latency_ms'] for x in v] == [300.0]


def test_entries_in_tail_window_dropped_both_ways():
    # 最后 10 秒才进来的，不论就绪没有都不算，避免只删未就绪的那部分造成偏乐观。
    events = [ev(1000, 0, 0, 'enter'), ev(1100, 0, 0, 'ready'),
              ev(115_000, 1, 0, 'enter'), ev(115_100, 1, 0, 'ready'),
              ev(116_000, 2, 0, 'enter')]
    v = visits(events, end_ms=120_000, tail_exclude_ms=10_000)
    assert [(x['cx'], x['latency_ms']) for x in v] == [(0, 100.0)]


def test_still_in_view_unready_at_end_is_censored():
    v = visits([ev(1000, 0, 0, 'enter')], end_ms=120_000, tail_exclude_ms=10_000)
    assert v[0]['censored']


def test_events_out_of_order_rejected():
    with pytest.raises(ValueError):
        visits([ev(200, 0, 0, 'enter'), ev(100, 0, 0, 'ready')], end_ms=1000)


def test_double_enter_rejected():
    with pytest.raises(ValueError):
        visits([ev(0, 0, 0, 'enter'), ev(10, 0, 0, 'enter')], end_ms=1000)


# ---- 每秒 CPU ----

def test_cpu_per_second_interpolates():
    # 宿主机每 0.5 秒一个采样，CPU 匀速 1.5 核。
    t0 = 1_000_000_000
    samples = [(t0 + i * 500_000_000, i * 750_000) for i in range(-2, 9)]
    cores = cpu_per_second(samples, t0, 3)
    assert cores == pytest.approx([1.5, 1.5, 1.5])


def test_cpu_per_second_needs_coverage():
    t0 = 0
    with pytest.raises(ValueError):
        cpu_per_second([(0, 0), (1_000_000_000, 10)], t0, 3)


def test_cpu_counter_backwards_rejected():
    with pytest.raises(ValueError):
        cpu_per_second([(0, 100), (1_000_000_000, 50), (2_000_000_000, 60)], 0, 1)


# ---- 单轮汇总 ----

def seconds(n, void):
    return [dict(t_s=i + 1, x=i * 30.0, z=0.0, view_chunks=385, void_rate=void[i], void_rate_max=void[i],
                 mspt_mean=20.0 + i, mspt_max=40.0, ticks=20) for i in range(n)]


def test_run_summary_values():
    sec = seconds(4, [0.0, 0.1, 0.2, 0.3])
    events = [ev(0, 0, 0, 'enter'), ev(0, 1, 0, 'enter'), ev(0, 1, 0, 'ready'), ev(200, 0, 0, 'ready')]
    s = run_summary(sec, visits(events, end_ms=4000, tail_exclude_ms=0), cpu=[1.0, 2.0, 1.0, 2.0])
    assert s['void_mean'] == pytest.approx(0.15)
    assert s['void_p95'] == pytest.approx(0.3)
    assert s['latency_p50_ms'] == 0.0 and s['latency_p95_ms'] == 200.0
    assert s['visits'] == 2 and s['censored_fraction'] == 0
    assert s['mspt_mean'] == pytest.approx(21.5) and s['mspt_max'] == 40.0
    assert s['cpu_cores_mean'] == pytest.approx(1.5)
    assert s['ticks_per_s'] == 20


def test_run_summary_flags_heavy_censoring():
    events = [ev(0, i, 0, 'enter') for i in range(10)] + [ev(100, i, 0, 'ready') for i in range(9)]
    s = run_summary(seconds(2, [0, 0]), visits(events, end_ms=2000, tail_exclude_ms=0), cpu=[1, 1])
    assert s['censored_fraction'] == pytest.approx(0.1)
    assert s['latency_p95_ms'] == math.inf


def test_run_summary_rejects_gaps_in_seconds():
    sec = seconds(3, [0, 0, 0])
    del sec[1]
    with pytest.raises(ValueError):
        run_summary(sec, [], cpu=[1, 1])


# ---- 每配置 5 次 ----

def test_config_summary_cv_and_absolute_std():
    runs = [dict(void_mean=v, latency_p95_ms=p) for v, p in
            [(.10, 900), (.11, 1000), (.09, 1100), (.10, 1000), (.10, 1000)]]
    s = config_summary(runs, ['void_mean', 'latency_p95_ms'])
    assert s['void_mean']['n'] == 5
    assert s['void_mean']['mean'] == pytest.approx(.10)
    assert s['void_mean']['cv'] == pytest.approx(s['void_mean']['std'] / .10)
    assert s['latency_p95_ms']['range'] == 200


def test_config_summary_near_zero_has_no_cv():
    runs = [dict(void_mean=v) for v in (0, 0, 0.001, 0, 0)]
    s = config_summary(runs, ['void_mean'])
    assert s['void_mean']['cv'] is None and s['void_mean']['std'] > 0


def test_config_summary_infinite_values_kept_honest():
    runs = [dict(latency_p95_ms=v) for v in (100, 120, math.inf, 110, 100)]
    s = config_summary(runs, ['latency_p95_ms'])
    assert s['latency_p95_ms']['mean'] == math.inf and s['latency_p95_ms']['median'] == 110
    assert s['latency_p95_ms']['infinite_runs'] == 1


def test_config_summary_needs_five():
    with pytest.raises(ValueError):
        config_summary([dict(void_mean=0)] * 4, ['void_mean'])


def test_significance_two_std():
    assert significant(0.30, base_mean=0.10, base_std=0.05)
    assert not significant(0.19, base_mean=0.10, base_std=0.05)
    assert significant(0.0, base_mean=0.10, base_std=0.0)
