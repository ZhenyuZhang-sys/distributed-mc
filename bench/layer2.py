"""第 4 步：一轮飞行测量的入口（docs/step4-plan.md 3.2）。

- fly()：在已经起好的测试服务器上放一个 Carpet 假玩家，等初始视野全部就绪，宿主机开始采 cgroup，
  `/bench fly start`，等 done.json，踢掉假玩家，把 bench-runs/<run>/ 归档到 out_dir/raw，再 compose()。
- compose()：只读归档，合成 layer2.csv（逐秒）、layer2-chunks.csv（逐 chunk）和 summary.json（一轮汇总 + 配置）。

时间对齐：bench mod 在 done.json 里写第一个 tick 的 System.nanoTime（Linux 上就是 CLOCK_MONOTONIC），
宿主机 Cgroup.sample() 用 time.monotonic_ns()，两边是同一个时钟，不用猜偏移。
"""
from __future__ import annotations

import csv
import json
import math
import shutil
import time
from pathlib import Path

from bench import layer2_stats as stats
from bench.server import TEST_DATA, wait_until

RUNS = TEST_DATA / 'bench-runs'
SECOND_COLUMNS = ('t_s', 'x', 'z', 'view_chunks', 'void_rate', 'void_rate_max', 'mspt_mean', 'mspt_max', 'ticks', 'cpu_cores')
CHUNK_COLUMNS = ('cx', 'cz', 'enter_ms', 'latency_ms', 'censored')
# done.json 里原样带进 summary.json 的配置（第 7 步预留的两项没有 coordinator 时是 null）
CONFIG_KEYS = ('run', 'player', 'mode', 'speed', 'y', 'start_x', 'start_z', 'view_distance', 'requested_view_distance',
               'available_processors', 'coordinator_features', 'demand_lookahead', 'scan_ms_total')


def _finite(value):
    """JSON 没有无穷大：删失导致的 inf 写成字符串 "inf"。"""
    if isinstance(value, float) and math.isinf(value):
        return 'inf'
    return value


def compose(run_dir, samples, out_dir, initial_load_s=None, tail_exclude_ms=10_000):
    """一轮的归档 → CSV 和汇总。samples 是宿主机 cgroup 采样 [(monotonic_ns, usage_usec)]。返回 out_dir。"""
    run_dir, out_dir = Path(run_dir), Path(out_dir)
    done = json.loads((run_dir / 'done.json').read_text())
    if done['stopped']:
        raise ValueError(f"{done['run']} 是 stopped（被提前停下），不能当测量结果")
    if done['aborted']:
        raise ValueError(f"{done['run']} aborted：{done['aborted']}")
    seconds = stats.load_jsonl(run_dir / 'seconds.jsonl')
    if len(seconds) != done['rows']:
        raise ValueError(f"seconds.jsonl 行数 {len(seconds)} 和 done.json 的 {done['rows']} 不符")
    cpu = stats.cpu_per_second(samples, done['t0_nanotime'], len(seconds))
    visits = stats.visits(stats.load_jsonl(run_dir / 'chunks.jsonl'), done['end_ms'], tail_exclude_ms)
    summary = stats.run_summary(seconds, visits, cpu)
    summary.update({k: done[k] for k in CONFIG_KEYS})
    summary.update(flight_seconds=done['seconds'], ticks_total=done['ticks'], initial_load_s=initial_load_s,
                   initial_view=done['initial']['view'], initial_not_ready=done['initial']['not_ready'],
                   tail_exclude_ms=tail_exclude_ms)

    out_dir.mkdir(parents=True, exist_ok=True)
    if (out_dir / 'layer2.csv').exists():
        raise FileExistsError(f'{out_dir} 里已经有结果，不覆盖')
    with (out_dir / 'layer2.csv').open('w', newline='') as f:
        w = csv.DictWriter(f, SECOND_COLUMNS)
        w.writeheader()
        for row, cores in zip(seconds, cpu):
            w.writerow({**{k: row[k] for k in SECOND_COLUMNS[:-1]}, 'cpu_cores': cores})
    with (out_dir / 'layer2-chunks.csv').open('w', newline='') as f:
        w = csv.DictWriter(f, CHUNK_COLUMNS)
        w.writeheader()
        for v in visits:
            w.writerow({k: v[k] for k in CHUNK_COLUMNS})
    (out_dir / 'summary.json').write_text(json.dumps({k: _finite(v) for k, v in summary.items()}, ensure_ascii=False, indent=1) + '\n')
    return out_dir


def _ready(server, player):
    try:
        return json.loads(server.rcon(f'bench fly ready {player}'))['not_ready'] == 0
    except (ValueError, KeyError):
        return False  # 玩家还没上线时命令报错，输出不是 JSON


def fly(server, run, player, start, speed, seconds, out_dir, mode='wall', y=200, ready_timeout=300, done_slack=120):
    """在 server（bench.server.Server，已经在跑）上飞一轮，结果写进 out_dir。返回 out_dir。

    假玩家用旁观模式（和 fish 第 3、6 步的测试一样）：不受重力，原版默认 spectatorsGenerateChunks=true，加载和生成与创造模式相同。
    """
    from bench.metrics import Cgroup, Sampler
    from bench.server import compose as docker_compose
    from bench.server import run as host_run

    out_dir = Path(out_dir)
    if out_dir.exists():
        raise FileExistsError(f'{out_dir} 已存在')
    x, z = start
    server.rcon(f'player {player} spawn at {x} {y} {z} facing 0 0 in minecraft:overworld in spectator')
    try:
        t_spawn = time.monotonic()
        wait_until(lambda: _ready(server, player), ready_timeout, .5, f'{player} 的初始视野全部就绪')
        initial_load_s = time.monotonic() - t_spawn
        cgroup = Cgroup(docker_compose('ps', '-q', 'minecraft').strip(), host_run)
        with Sampler(cgroup) as sampler:
            reply = server.rcon(f'bench fly start {player} {speed} {seconds} {run} {mode}')
            if 'started' not in reply:
                raise RuntimeError(f'bench fly start 失败：{reply}')
            done_file = RUNS / run / 'done.json'
            wait_until(done_file.exists, seconds + done_slack, .5, f'{run} 的 done.json')
            done = json.loads(done_file.read_text())
            end_ns = done['t0_nanotime'] + done['rows'] * 1_000_000_000
            # 采样要覆盖到最后一秒结束
            wait_until(lambda: sampler.rows and sampler.rows[-1]['time_ns'] >= end_ns, 10, .1, 'cgroup 采样覆盖整个飞行')
        samples = [(r['time_ns'], r['usage_usec']) for r in sampler.rows]
    finally:
        server.rcon(f'player {player} kill')
    raw = out_dir / 'raw'
    shutil.copytree(RUNS / run, raw)
    with (out_dir / 'cpu-samples.jsonl').open('w') as f:
        for t, u in samples:
            f.write(json.dumps(dict(time_ns=t, usage_usec=u)) + '\n')
    compose(raw, samples, out_dir, initial_load_s=initial_load_s)
    shutil.rmtree(RUNS / run)
    return out_dir
