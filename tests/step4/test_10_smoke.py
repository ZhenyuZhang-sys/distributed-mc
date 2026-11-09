"""第 4 步 4.5、4.6：单轮端到端冒烟（非 slow）。新世界里走路速度飞 15 秒，出 CSV；再用地图服务器回放这一轮。

计划里 4.6 的真实回放原定放在 test_11_map_replay.py；它要用同一次飞行，所以和 4.5 放在一个文件里，共用一个模块级 fixture。

起点放在某个 chunk 的最东一格，起飞马上跨进下一个 chunk，保证有"进视野"事件。
离出生点、第 1 步区域和 fish 第 6 步的航线（0, ±40000）都远。
"""
import csv
import json
import os
import threading
import time
import urllib.request
from pathlib import Path

import pytest

from bench import layer2
from bench.map.server import make_server

START = (23999.5, -23991.5)


@pytest.fixture(scope='module')
def smoke(server):
    out = Path(os.environ['MCD_ARTIFACT_DIR']) / 'step4-smoke'
    return layer2.fly(server, run=f'smoke-{time.time_ns()}', player='walkbot', start=START, speed=4.3, seconds=15,
                      out_dir=out)


def test_walk_flight_writes_csv(server, smoke):
    result = smoke
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
    assert summary['scan_every_ticks'] == 1
    assert (result / 'raw' / 'done.json').is_file() and (result / 'raw' / 'chunks.jsonl').is_file()
    # 归档后服务器目录里的原始输出删掉，不在 test-data 里越积越多
    assert not (layer2.RUNS / summary['run']).exists()
    assert 'walkbot' not in server.rcon('list')


def test_map_replays_the_smoke_run(smoke):
    # DEV_PLAN"能回放生成顺序"的验收：回放接口按时间顺序返回这一轮的来源事件；原版事件数等于这一轮的生成计数增量
    raw = smoke / 'raw'
    in_file = [json.loads(line) for line in (raw / 'provenance.jsonl').read_text().splitlines() if line.strip()]
    expected = sorted(in_file, key=lambda e: e['t_ms'])
    done = json.loads((raw / 'done.json').read_text())
    srv = make_server(smoke, port=0)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        with urllib.request.urlopen(f'http://127.0.0.1:{srv.server_address[1]}/replay?run=raw', timeout=10) as r:
            replay = json.load(r)
    finally:
        srv.shutdown()
        srv.server_close()
    key = lambda e: (e['t_ms'], e['cx'], e['cz'], e['kind'], e['donor'])
    assert [key(e) for e in replay['events']] == [key(e) for e in expected]
    vanilla = replay['counts']['vanilla']
    # 用 done.json 里飞行期间的计数增量，不用全局 bench stats：后者还含起飞前生成初始视野的那些
    assert vanilla > 0 and vanilla == done['counters']['full_generated'] + done['counters']['full_resumed']
    assert done['provenance']['vanilla'] == vanilla
    assert [t['t_s'] for t in replay['track']] == list(range(1, 16))
