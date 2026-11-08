"""第 4 步 4.5：一轮飞行的归档 → CSV 和汇总（bench/layer2.py 的 compose）。纯逻辑，用合成数据，不起服务器。"""
import csv
import json
import math

import pytest

from bench import layer2

T0 = 5_000_000_000_000  # 服务器里第一个 tick 的 System.nanoTime（和宿主机 monotonic 同一个时钟）


def write_run(d, rows=3, done=None, events=None):
    d.mkdir(parents=True)
    with (d / 'seconds.jsonl').open('w') as f:
        for t in range(1, rows + 1):
            f.write(json.dumps(dict(t_s=t, x=100.0 + 4.3 * t, z=8.5, view_chunks=453, void_rate=0.1 / t, void_rate_max=0.2 / t,
                                    mspt_mean=12.0, mspt_max=20.0, ticks=20)) + '\n')
    with (d / 'chunks.jsonl').open('w') as f:
        for e in events if events is not None else [dict(t_ms=100, cx=7, cz=0, ev='enter'), dict(t_ms=200, cx=8, cz=0, ev='enter'),
                                                    dict(t_ms=600, cx=7, cz=0, ev='ready')]:
            f.write(json.dumps(e) + '\n')
    base = dict(version=1, run=d.name, player='walkbot', mode='wall', speed=4.3, seconds=float(rows), y=200.0,
                start_x=100.0, start_z=8.5, end_x=100 + 4.3 * rows, end_z=8.5, view_distance=10, requested_view_distance=2,
                available_processors=2, ticks=20 * rows, rows=rows, end_ms=rows * 1000, t0_nanotime=T0,
                events=dict(enter=2, ready=1, leave=0), initial=dict(view=453, not_ready=0), scan_ms_total=1.5,
                stopped=False, aborted=None, coordinator_features=None, demand_lookahead=None)
    base.update(done or {})
    (d / 'done.json').write_text(json.dumps(base))
    return d


def samples(cores=2.0, start=-1.0, end=None, seconds=3):
    end = seconds + 1.0 if end is None else end
    out, t = [], start
    while t <= end + 1e-9:
        out.append((T0 + int(t * 1e9), int((t - start) * cores * 1e6)))
        t += .25
    return out


def read_csv(p):
    with p.open() as f:
        return list(csv.DictReader(f))


def test_compose_writes_seconds_chunks_and_summary(tmp_path):
    run = write_run(tmp_path / 'run-a')
    out = layer2.compose(run, samples(), tmp_path / 'out', initial_load_s=4.2)
    rows = read_csv(out / 'layer2.csv')
    assert list(rows[0]) == list(layer2.SECOND_COLUMNS)
    assert [int(r['t_s']) for r in rows] == [1, 2, 3]
    assert all(abs(float(r['cpu_cores']) - 2.0) < 1e-6 for r in rows)  # 按 t0_nanotime 和宿主机采样对齐
    # 默认 tail_exclude 10 秒（协议值），3 秒的合成数据一条也留不下，只有表头
    assert (out / 'layer2-chunks.csv').read_text().splitlines() == [','.join(layer2.CHUNK_COLUMNS)]
    summary = json.loads((out / 'summary.json').read_text())
    assert summary['run'] == 'run-a' and summary['speed'] == 4.3 and summary['mode'] == 'wall'
    assert summary['seconds'] == 3 and summary['initial_load_s'] == 4.2
    assert math.isclose(summary['void_mean'], (0.1 + 0.05 + 0.1 / 3) / 3)
    assert summary['available_processors'] == 2 and summary['requested_view_distance'] == 2
    assert summary['coordinator_features'] is None and summary['scan_ms_total'] == 1.5


def test_compose_latency_and_censoring(tmp_path):
    run = write_run(tmp_path / 'run-b')
    out = layer2.compose(run, samples(), tmp_path / 'out', tail_exclude_ms=0)
    chunks = {(int(r['cx']), int(r['cz'])): r for r in read_csv(out / 'layer2-chunks.csv')}
    assert float(chunks[7, 0]['latency_ms']) == 500 and chunks[7, 0]['censored'] == 'False'
    assert chunks[8, 0]['latency_ms'] == 'inf' and chunks[8, 0]['censored'] == 'True'
    summary = json.loads((out / 'summary.json').read_text())
    assert summary['visits'] == 2 and summary['censored_fraction'] == .5
    assert summary['latency_p95_ms'] == 'inf'  # JSON 里没有无穷大，写成字符串


def test_compose_refuses_unfinished_or_inconsistent_runs(tmp_path):
    with pytest.raises(ValueError, match='stopped'):
        layer2.compose(write_run(tmp_path / 's', done=dict(stopped=True)), samples(), tmp_path / 'o1')
    with pytest.raises(ValueError, match='aborted'):
        layer2.compose(write_run(tmp_path / 'a', done=dict(aborted='player_left')), samples(), tmp_path / 'o2')
    with pytest.raises(ValueError, match='行数'):
        layer2.compose(write_run(tmp_path / 'r', done=dict(rows=4)), samples(), tmp_path / 'o3')
    with pytest.raises(ValueError, match='采样'):
        layer2.compose(write_run(tmp_path / 'c'), samples(start=0.5), tmp_path / 'o4')  # 采样没覆盖起飞时刻


def test_compose_does_not_overwrite(tmp_path):
    run = write_run(tmp_path / 'run-c')
    layer2.compose(run, samples(), tmp_path / 'out')
    with pytest.raises(FileExistsError):
        layer2.compose(run, samples(), tmp_path / 'out')
