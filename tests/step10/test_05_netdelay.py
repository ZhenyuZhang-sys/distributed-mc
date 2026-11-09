"""第 10 步 10.5：网络延迟代理。本地回环，不起服务器。"""
import socket
import socketserver
import threading
import time

import pytest

from analysis.netdelay import DelayProxy


class Echo(socketserver.BaseRequestHandler):
    def handle(self):
        while True:
            data = self.request.recv(65536)
            if not data:
                return
            self.request.sendall(data)


@pytest.fixture
def echo():
    server = socketserver.ThreadingTCPServer(('127.0.0.1', 0), Echo)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield server.server_address
    server.shutdown()
    server.server_close()


def rtt(port, payload=b'x'):
    with socket.create_connection(('127.0.0.1', port), timeout=5) as s:
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        times = []
        for _ in range(5):
            t = time.monotonic()
            s.sendall(payload)
            got = b''
            while len(got) < len(payload):
                got += s.recv(65536)
            times.append(time.monotonic() - t)
        return times


def test_fixed_latency_round_trip(echo):
    with DelayProxy(echo, latency_ms=50) as p:
        times = rtt(p.port)
    assert all(0.095 <= t < 0.25 for t in times), times      # 来回各 50ms


def test_zero_latency_is_fast(echo):
    with DelayProxy(echo, latency_ms=0) as p:
        assert max(rtt(p.port)) < 0.05


def test_jitter_within_bounds_and_order_kept(echo):
    with DelayProxy(echo, latency_ms=30, jitter_ms=20, seed=3) as p:
        times = rtt(p.port)
        assert all(0.019 <= t < 0.3 for t in times), times
        with socket.create_connection(('127.0.0.1', p.port), timeout=5) as s:
            sent = b''.join(f'{i:06d};'.encode() for i in range(2000))
            for i in range(0, len(sent), 700):
                s.sendall(sent[i:i + 700])
            got = b''
            while len(got) < len(sent):
                got += s.recv(65536)
    assert got == sent                                        # 抖动不能打乱字节顺序


def test_client_close_closes_upstream(echo):
    with DelayProxy(echo, latency_ms=10) as p:
        s = socket.create_connection(('127.0.0.1', p.port), timeout=5)
        s.sendall(b'a')
        assert s.recv(10) == b'a'
        s.close()
        time.sleep(0.2)
        assert p.open_connections() == 0


@pytest.mark.parametrize('kw', [dict(latency_ms=-1), dict(latency_ms=10, jitter_ms=-1), dict(latency_ms=10, jitter_ms=11)])
def test_bad_parameters(echo, kw):
    with pytest.raises(ValueError):
        DelayProxy(echo, **kw)
