"""实时地图的小服务器：读 bench mod 写的 provenance.jsonl，按"谁生成的"给 chunk 上色。

只绑 127.0.0.1，只用标准库。用法：python -m bench.map.server <bench-runs 目录> [端口] [--demo]
加 --demo 时多一个演示面板 /demo（docs/step10-plan.md 7.1，后端在 bench/demo/）。
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
DEMO_PAGE = Path(__file__).with_name('demo.html')
LOCAL_HOSTS = {'127.0.0.1', 'localhost'}


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


def replay(path):
    """一轮归档的回放：按时间排好的来源事件（带颜色）、各类计数、逐秒轨迹。"""
    events = [decorate(e) for e in complete_lines(path / 'provenance.jsonl')[0]]
    events.sort(key=lambda e: e['t_ms'])  # sort 是稳定的：同一时刻保持文件顺序
    track = complete_lines(path / 'seconds.jsonl')[0] if (path / 'seconds.jsonl').exists() else []
    counts = {k: sum(e['kind'] == k for e in events) for k in KINDS}
    return dict(events=events, counts=counts, track=[dict(t_s=s['t_s'], x=s['x'], z=s['z']) for s in track])


class Handler(BaseHTTPRequestHandler):
    root = None
    demo = None   # dict(poller, controller, ab)；没开演示面板时是 None

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

    def latest_run(self):
        """最新的一轮：provenance.jsonl 最新改过的那个（新开的一轮一开始就会建这个文件）。没有就是 None。"""
        best = None
        for p in self.root.iterdir():
            f = p / 'provenance.jsonl'
            if p.is_dir() and RUN_NAME.fullmatch(p.name) and f.is_file():
                t = f.stat().st_mtime
                if best is None or t > best[0]:
                    best = (t, p)
        return best[1] if best else None

    def latest_events(self, query, raw):
        """实时模式跟最新一轮（用户 11-09 报"地图更新一次之后就不再重新着色"：原来页面只在打开时读一次 run 列表）。
        have = 页面正在显示的那一轮。最新一轮变了就从头发，reset = true，页面先清空。一轮都没有（飞完被归档删掉了）回空的，页面保留最后的样子。"""
        have = query.get('have', [''])[0]
        latest = self.latest_run()
        if latest is None:
            return self.send(200, dict(run=None, next=0, events=[], player=None, reset=False))
        reset = latest.name != have
        since = 0 if reset else int(raw)
        events, nxt = complete_lines(latest / 'provenance.jsonl', since)
        track = complete_lines(latest / 'seconds.jsonl')[0] if (latest / 'seconds.jsonl').exists() else []
        return self.send(200, dict(run=latest.name, reset=reset, next=nxt, events=[decorate(e) for e in events],
                                   player=track[-1] if track else None))

    def run_dir(self, query):
        name = query.get('run', [''])[0]
        if not RUN_NAME.fullmatch(name):
            raise LookupError(400, 'run 名不合法')
        path = (self.root / name).resolve()
        if path.parent != self.root or not (path / 'provenance.jsonl').is_file():
            raise LookupError(404, '没有这个 run')
        return path

    def host_ok(self):
        """防 DNS 重绑定：只认本机名字。"""
        host = (self.headers.get('Host') or '').rsplit(':', 1)[0].strip('[]')
        return host in LOCAL_HOSTS

    def do_POST(self):
        from bench.demo.control import BadRequest, Busy
        url = urlparse(self.path)
        if not self.host_ok():
            return self.send(403, dict(error='Host 不是本机'))
        if self.demo is None or url.path not in ('/api/control', '/api/ab'):
            return self.send(404, dict(error='没有这个路径'))
        # 跨站表单只能发 form / text 类型，也带不了自定义头：两条都要
        if not (self.headers.get('Content-Type') or '').startswith('application/json') or self.headers.get('X-Demo') != '1':
            return self.send(403, dict(error='控制请求要 Content-Type: application/json 和 X-Demo: 1'))
        try:
            n = int(self.headers.get('Content-Length') or 0)
            body = json.loads(self.rfile.read(min(n, 65536)) or b'{}')
            if url.path == '/api/control':
                return self.send(200, self.demo['controller'].do(body))
            if not isinstance(body, dict):
                raise BadRequest('要一个 JSON 对象')
            self.demo['ab'].start(body.get('speed', 'elytra'), body.get('seconds', 120), body.get('server_cpus', 2))
            return self.send(202, self.demo['ab'].status())
        except (BadRequest, json.JSONDecodeError) as e:
            return self.send(400, dict(error=str(e)))
        except Busy as e:
            return self.send(409, dict(error=str(e)))
        except Exception as e:  # 命令失败（超时、compose 报错）：如实回给页面
            return self.send(500, dict(error=f'{type(e).__name__}: {e}'))

    def demo_get(self, url, query):
        d = self.demo
        if url.path == '/demo':
            return self.send(200, DEMO_PAGE.read_bytes(), 'text/html; charset=utf-8')
        if url.path == '/api/live':
            c = d['controller']
            return self.send(200, dict(row=d['poller'].latest(), busy=c.current, flyer=c.flyer.state(), tracker=c.tracker.state(),
                                       ab=d['ab'].status() if d.get('ab') else None,
                                       netem_supported=bool(c.runner.netem_supported())))
        if url.path == '/api/players':
            return self.send(200, dict(players=d['controller'].players()))
        if url.path == '/api/series':
            raw = query.get('since', ['0'])[0]
            if not raw.isdigit():
                raise LookupError(400, 'since 必须是非负整数')
            return self.send(200, d['poller'].series(int(raw)))
        if url.path == '/api/ab':
            return self.send(200, d['ab'].status() if d.get('ab') else dict(state='idle'))
        if url.path in ('/api/ab/replay', '/api/ab/seconds'):
            which = query.get('which', [''])[0]
            st = d['ab'].status() if d.get('ab') else {}
            if which not in ('A', 'B') or not st.get('result'):
                raise LookupError(404, '没有这一轮')
            raw = Path(st['result'][which]['dir']) / 'raw'
            if url.path == '/api/ab/seconds':
                return self.send(200, dict(rows=complete_lines(raw / 'seconds.jsonl')[0]))
            return self.send(200, replay(raw))
        raise LookupError(404, '没有这个路径')

    def do_GET(self):
        url = urlparse(self.path)
        query = parse_qs(url.query)
        if not self.host_ok():
            return self.send(403, dict(error='Host 不是本机'))
        try:
            if self.demo is not None and (url.path == '/demo' or url.path.startswith('/api/')):
                return self.demo_get(url, query)
            if url.path == '/':
                return self.send(200, PAGE.read_bytes(), 'text/html; charset=utf-8')
            if url.path == '/runs':
                return self.send(200, sorted(p.name for p in self.root.iterdir()
                                             if p.is_dir() and (p / 'provenance.jsonl').is_file()))
            if url.path == '/events':
                raw = query.get('since', ['0'])[0]
                if not raw.isdigit():
                    raise LookupError(400, 'since 必须是非负整数')
                if query.get('run', [''])[0] == 'latest':
                    return self.latest_events(query, raw)
                path = self.run_dir(query)
                events, nxt = complete_lines(path / 'provenance.jsonl', int(raw))
                track = complete_lines(path / 'seconds.jsonl')[0] if (path / 'seconds.jsonl').exists() else []
                return self.send(200, dict(next=nxt, events=[decorate(e) for e in events],
                                           player=track[-1] if track else None))
            if url.path == '/replay':
                return self.send(200, replay(self.run_dir(query)))
            raise LookupError(404, '没有这个路径')
        except LookupError as e:
            code, message = e.args
            self.send(code, dict(error=message))
        except (ValueError, OSError) as e:
            self.send(500, dict(error=str(e)))


def make_server(root, port=8765, demo=None):
    handler = type('BoundHandler', (Handler,), dict(root=Path(root).resolve(), demo=demo))
    return ThreadingHTTPServer(('127.0.0.1', port), handler)


if __name__ == '__main__':
    args = [a for a in sys.argv[1:] if a != '--demo']
    demo = None
    if '--demo' in sys.argv:
        from bench.demo.app import build
        demo = build(Path(args[0]))
    server = make_server(args[0], int(args[1]) if len(args) > 1 else 8765, demo=demo)
    print(f'地图：http://127.0.0.1:{server.server_address[1]}/', flush=True)
    if demo:
        print(f'演示面板：http://127.0.0.1:{server.server_address[1]}/demo', flush=True)
    server.serve_forever()
