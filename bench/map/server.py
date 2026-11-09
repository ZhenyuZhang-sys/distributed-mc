"""实时地图的小服务器：读 bench mod 写的 provenance.jsonl，按"谁生成的"给 chunk 上色。

只绑 127.0.0.1，只用标准库。用法：python -m bench.map.server <bench-runs 目录> [端口]
provenance.jsonl 每行：{"t_ms": 起飞后毫秒, "cx", "cz", "kind": vanilla|resumed|donor|rejected|loaded, "donor": 编号或 -1}
"""
import colorsys
import json
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

KINDS = ('vanilla', 'resumed', 'donor', 'rejected', 'loaded')
RESUMED_COLOR = '#7d6b9e'  # 灰紫：donor 的颜色都是高饱和的，这个和它们、和原版灰绿都分得开
RUN_NAME = re.compile(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}')
PAGE = Path(__file__).with_name('index.html')


def color(kind, donor):
    """原版灰绿、接着做完灰紫、打回红、读盘浅灰；donor 按编号用黄金角分色，同一编号颜色固定。

    导入的 tile 先有一条 donor（导入时），服务器把它做完时再有一条 resumed；地图按最后一条上色，
    所以导入了还没做完的是 donor 的颜色，做完了是 resumed 的颜色（fish 11-09：三类各一种颜色）。donor 编号在 layer2-chunks.csv 的 donor 列。
    """
    if kind == 'vanilla':
        return '#5b8c5a'
    if kind == 'resumed':
        return RESUMED_COLOR
    if kind == 'rejected':
        return '#d62728'
    if kind == 'loaded':
        return '#c7c7c7'
    if kind == 'donor':
        r, g, b = colorsys.hls_to_rgb((donor * 0.618033988749895) % 1, 0.55, 0.75)
        return '#%02x%02x%02x' % (round(r * 255), round(g * 255), round(b * 255))
    raise ValueError(f'未知来源：{kind}')


def complete_lines(path, since=0):
    """返回第 since 行起的完整行（最后一行没写完换行就不算），以及下一次的 since。"""
    with Path(path).open('rb') as f:
        data = f.read()
    lines = data.split(b'\n')[:-1]
    return [json.loads(x) for x in lines[since:] if x.strip()], max(since, len(lines))


def decorate(event):
    if event.get('kind') not in KINDS:
        raise ValueError(f'未知来源：{event.get("kind")}')
    return dict(event, color=color(event['kind'], event.get('donor', -1)))


class Handler(BaseHTTPRequestHandler):
    root = None

    def log_message(self, *args):
        pass

    def send(self, code, body, kind='application/json; charset=utf-8'):
        data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header('Content-Type', kind)
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(data)

    def run_dir(self, query):
        name = query.get('run', [''])[0]
        if not RUN_NAME.fullmatch(name):
            raise LookupError(400, 'run 名不合法')
        path = (self.root / name).resolve()
        if path.parent != self.root or not (path / 'provenance.jsonl').is_file():
            raise LookupError(404, '没有这个 run')
        return path

    def do_GET(self):
        url = urlparse(self.path)
        query = parse_qs(url.query)
        try:
            if url.path == '/':
                return self.send(200, PAGE.read_bytes(), 'text/html; charset=utf-8')
            if url.path == '/runs':
                return self.send(200, sorted(p.name for p in self.root.iterdir()
                                             if p.is_dir() and (p / 'provenance.jsonl').is_file()))
            if url.path == '/events':
                path = self.run_dir(query)
                raw = query.get('since', ['0'])[0]
                if not raw.isdigit():
                    raise LookupError(400, 'since 必须是非负整数')
                events, nxt = complete_lines(path / 'provenance.jsonl', int(raw))
                track = complete_lines(path / 'seconds.jsonl')[0] if (path / 'seconds.jsonl').exists() else []
                return self.send(200, dict(next=nxt, events=[decorate(e) for e in events],
                                           player=track[-1] if track else None))
            if url.path == '/replay':
                path = self.run_dir(query)
                events = [decorate(e) for e in complete_lines(path / 'provenance.jsonl')[0]]
                events.sort(key=lambda e: e['t_ms'])  # sort 是稳定的：同一时刻保持文件顺序
                track = complete_lines(path / 'seconds.jsonl')[0] if (path / 'seconds.jsonl').exists() else []
                counts = {k: sum(e['kind'] == k for e in events) for k in KINDS}
                return self.send(200, dict(events=events, counts=counts,
                                           track=[dict(t_s=s['t_s'], x=s['x'], z=s['z']) for s in track]))
            raise LookupError(404, '没有这个路径')
        except LookupError as e:
            code, message = e.args
            self.send(code, dict(error=message))
        except (ValueError, OSError) as e:
            self.send(500, dict(error=str(e)))


def make_server(root, port=8765):
    handler = type('BoundHandler', (Handler,), dict(root=Path(root).resolve()))
    return ThreadingHTTPServer(('127.0.0.1', port), handler)


if __name__ == '__main__':
    server = make_server(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 8765)
    print(f'地图：http://127.0.0.1:{server.server_address[1]}/', flush=True)
    server.serve_forever()
