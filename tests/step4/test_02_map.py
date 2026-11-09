"""第 4 步 4.6：实时地图服务器（合成数据）。真实 run 的回放验收在 test_11_map_replay.py。"""
import json
import threading
import urllib.error
import urllib.request

import pytest

from bench.map.server import color, make_server

EVENTS = [
    dict(t_ms=30, cx=1, cz=0, kind='vanilla', donor=-1),
    dict(t_ms=10, cx=0, cz=0, kind='vanilla', donor=-1),
    dict(t_ms=20, cx=0, cz=1, kind='donor', donor=3),
    dict(t_ms=20, cx=2, cz=1, kind='rejected', donor=3),
    dict(t_ms=40, cx=5, cz=5, kind='loaded', donor=-1),
    dict(t_ms=50, cx=0, cz=1, kind='resumed', donor=-1),
]


@pytest.fixture
def runs(tmp_path):
    run = tmp_path / 'flight-a'
    run.mkdir()
    (run / 'provenance.jsonl').write_text(''.join(json.dumps(e) + '\n' for e in EVENTS[:3]))
    (run / 'seconds.jsonl').write_text(json.dumps(dict(t_s=1, x=16.0, z=8.0)) + '\n')
    (tmp_path / 'not-a-run').mkdir()
    return tmp_path


@pytest.fixture
def base(runs):
    server = make_server(runs, port=0)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield f'http://127.0.0.1:{server.server_address[1]}'
    server.shutdown()
    server.server_close()


def get(url):
    with urllib.request.urlopen(url, timeout=5) as r:
        assert r.status == 200
        body = r.read()
        return json.loads(body) if r.headers['Content-Type'].startswith('application/json') else body.decode()


def test_binds_localhost_only(runs):
    server = make_server(runs, port=0)
    try:
        assert server.server_address[0] == '127.0.0.1'
    finally:
        server.server_close()


def test_lists_only_runs_with_provenance(base):
    assert get(base + '/runs') == ['flight-a']


def test_page_served(base):
    page = get(base + '/')
    assert '<canvas' in page and '/events' in page and '/replay' in page


def test_events_incremental(base, runs):
    first = get(base + '/events?run=flight-a&since=0')
    assert first['next'] == 3 and [e['t_ms'] for e in first['events']] == [30, 10, 20]
    again = get(base + '/events?run=flight-a&since=3')
    assert again['next'] == 3 and again['events'] == [] and again['player'] == dict(t_s=1, x=16.0, z=8.0)
    with (runs / 'flight-a' / 'provenance.jsonl').open('a') as f:
        f.write(json.dumps(EVENTS[3]) + '\n' + json.dumps(EVENTS[4])[:10])  # 最后一行还没写完
    more = get(base + '/events?run=flight-a&since=3')
    assert more['next'] == 4 and more['events'][0]['kind'] == 'rejected'


def test_events_carry_colors(base):
    events = get(base + '/events?run=flight-a&since=0')['events']
    assert all(e['color'] == color(e['kind'], e['donor']) for e in events)


def test_replay_sorted_by_time_stable(base, runs):
    with (runs / 'flight-a' / 'provenance.jsonl').open('a') as f:
        f.write(json.dumps(EVENTS[3]) + '\n')
    replay = get(base + '/replay?run=flight-a')
    assert [(e['t_ms'], e['cx'], e['cz']) for e in replay['events']] == [(10, 0, 0), (20, 0, 1), (20, 2, 1), (30, 1, 0)]
    assert replay['track'] == [dict(t_s=1, x=16.0, z=8.0)]
    assert replay['counts'] == dict(vanilla=2, resumed=0, donor=1, rejected=1, loaded=0)


@pytest.mark.parametrize('query', ['run=../etc&since=0', 'run=nope&since=0', 'run=flight-a&since=-1',
                                   'run=flight-a&since=x', 'since=0'])
def test_bad_requests_rejected(base, query):
    with pytest.raises(urllib.error.HTTPError) as e:
        get(base + '/events?' + query)
    assert e.value.code in (400, 404)


def test_unknown_path_404(base):
    with pytest.raises(urllib.error.HTTPError) as e:
        get(base + '/secret')
    assert e.value.code == 404


def test_resumed_counted_and_painted_on_its_own(base, runs):
    # fish 11-09：donor 导入的半成品被服务器接着做完，单独一类、地图上一种颜色，不算进原版
    with (runs / 'flight-a' / 'provenance.jsonl').open('a') as f:
        f.write(json.dumps(EVENTS[3]) + '\n' + json.dumps(EVENTS[4]) + '\n' + json.dumps(EVENTS[5]) + '\n')
    replay = get(base + '/replay?run=flight-a')
    assert replay['counts'] == dict(vanilla=2, resumed=1, donor=1, rejected=1, loaded=1)
    last = replay['events'][-1]
    assert last['kind'] == 'resumed' and last['color'] == color('resumed', -1)
    assert '#' + color('resumed', -1).lstrip('#') in get(base + '/')  # 图例里有这种颜色


def test_colors():
    assert color('vanilla', -1) != color('rejected', 3) != color('donor', 3)
    others = {color(k, -1) for k in ('vanilla', 'rejected', 'loaded')} | {color('donor', d) for d in range(16)}
    assert color('resumed', -1) not in others
    assert color('donor', 1) != color('donor', 2)
    assert color('donor', 7) == color('donor', 7)
    with pytest.raises(ValueError):
        color('alien', 0)


def test_live_follows_latest_run_across_batches(runs):
    # 用户 11-09 报："map 更新一次之后就不会再重新着色了"。原因：网页只在打开时读一次 run 列表，之后一直拉那一轮；
    # 新开一轮（新的飞行、跟踪真人玩家）出不来。现在实时模式默认"跟最新一轮"：run=latest，带上 have=页面正在显示的那一轮
    import os
    import time as _time
    server = make_server(runs, port=0)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = f'http://127.0.0.1:{server.server_address[1]}'
    try:
        a = runs / 'flight-a'
        os.utime(a / 'provenance.jsonl', (1_000, 1_000))
        first = get(base + '/events?run=latest&have=&since=0')
        assert first['run'] == 'flight-a' and first['reset'] and first['next'] == 3
        # 第 2 批：同一轮追加，增量，不重置
        with (a / 'provenance.jsonl').open('a') as f:
            f.write(json.dumps(EVENTS[3]) + '\n')
        second = get(base + f"/events?run=latest&have=flight-a&since={first['next']}")
        assert second['run'] == 'flight-a' and not second['reset'] and [e['kind'] for e in second['events']] == ['rejected']
        # 第 3 批：新开一轮，页面要清空、从头拉新的一轮
        b = runs / 'flight-b'
        b.mkdir()
        (b / 'provenance.jsonl').write_text(json.dumps(dict(t_ms=5, cx=7, cz=7, kind='vanilla', donor=-1)) + '\n')
        os.utime(b / 'provenance.jsonl', (_time.time(), _time.time()))
        third = get(base + f"/events?run=latest&have=flight-a&since={second['next']}")
        assert third['run'] == 'flight-b' and third['reset'] and third['next'] == 1
        assert [(e['cx'], e['cz']) for e in third['events']] == [(7, 7)]
        # 第 4 批：新的一轮里接着追加
        with (b / 'provenance.jsonl').open('a') as f:
            f.write(json.dumps(dict(t_ms=9, cx=8, cz=7, kind='donor', donor=2)) + '\n')
        fourth = get(base + f"/events?run=latest&have=flight-b&since={third['next']}")
        assert not fourth['reset'] and [(e['cx'], e['cz']) for e in fourth['events']] == [(8, 7)]
    finally:
        server.shutdown()
        server.server_close()


def test_live_run_gone_keeps_page_alive(runs):
    # 飞完以后 layer2.fly 把这一轮归档并删掉 bench-runs 里的目录：实时模式不该报错停住，回"这一轮没了"，页面保留最后的样子
    server = make_server(runs, port=0)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = f'http://127.0.0.1:{server.server_address[1]}'
    try:
        gone = get(base + '/events?run=latest&have=flight-zzz&since=12')
        assert gone['run'] == 'flight-a' and gone['reset']        # 有别的轮就换过去
        import shutil
        shutil.rmtree(runs / 'flight-a')
        none = get(base + '/events?run=latest&have=flight-a&since=3')
        assert none['run'] is None and none['events'] == [] and not none['reset']
    finally:
        server.shutdown()
        server.server_close()


def test_page_follows_latest_by_default(base):
    page = get(base + '/')
    assert 'run=latest' in page and '跟最新一轮' in page and 'data.reset' in page
