"""第 4 步 4.5：单轮端到端冒烟（非 slow）。新世界里走路速度飞 15 秒，出 CSV。

起点放在某个 chunk 的最东一格，起飞马上跨进下一个 chunk，保证有"进视野"事件。
离出生点、第 1 步区域和 fish 第 6 步的航线（0, ±40000）都远。
"""
import csv
import json
import os
import time
from pathlib import Path

from bench import layer2

START = (23999.5, -23991.5)


def test_walk_flight_writes_csv(server):
    out = Path(os.environ['MCD_ARTIFACT_DIR']) / 'step4-smoke'
    result = layer2.fly(server, run=f'smoke-{time.time_ns()}', player='walkbot', start=START, speed=4.3, seconds=15,
                        out_dir=out)
    with (result / 'layer2.csv').open() as f:
        rows = list(csv.DictReader(f))
    assert [int(r['t_s']) for r in rows] == list(range(1, 16))
    assert all(int(r['view_chunks']) == 453 for r in rows), {r['view_chunks'] for r in rows}
    assert all(0 <= float(r['void_rate']) <= float(r['void_rate_max']) <= 1 for r in rows)
    assert all(int(r['ticks']) > 0 and float(r['cpu_cores']) > 0 for r in rows)
    xs = [float(r['x']) for r in rows]
    assert abs(xs[-1] - START[0] - 4.3 * 15) < 1.0 and xs == sorted(xs)
    summary = json.loads((result / 'summary.json').read_text())
    assert summary['initial_load_s'] > 0 and summary['visits'] > 0
    assert summary['view_distance'] == 10 and summary['available_processors'] >= 1
    assert (result / 'raw' / 'done.json').is_file() and (result / 'raw' / 'chunks.jsonl').is_file()
    # 归档后服务器目录里的原始输出删掉，不在 test-data 里越积越多
    assert not (layer2.RUNS / summary['run']).exists()
    assert 'walkbot' not in server.rcon('list')
