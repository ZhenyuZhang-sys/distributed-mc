"""第 10 步：网络延迟代理（docs/step10-plan.md 2.5）。worker 连代理，代理每个方向延迟后再转给 coordinator。

- latency_ms：单向固定延迟；jitter_ms：抖动，延迟在 latency_ms ± jitter_ms 里均匀取（默认 0 = 关）。
- 每段数据的发出时间不早于上一段，所以开抖动也不会打乱 TCP 字节顺序。
- 已知限制：不模拟丢包、带宽限制和乱序。丢包下的正确性第 5 步用 tc netem 测过，这里只管性能。
- 不需要容器的 NET_ADMIN 权限，也不改共用的 compose 文件。

用法：python -m analysis.netdelay --listen 25600 --target 127.0.0.1:25599 --latency 50 [--jitter 10]
"""
from __future__ import annotations

import argparse
import queue
import random
import socket
import threading
import time


class _Pump:
    """一个方向：读线程收数据、记下该发出的时间；发线程到点再发。"""

    def __init__(self, src, dst, delay):
        self.src, self.dst, self.delay = src, dst, delay
        self.q = queue.Queue()
        self.done = threading.Event()
        self.last = 0.0

    def start(self):
        threading.Thread(target=self._read, daemon=True).start()
        threading.Thread(target=self._send, daemon=True).start()

    def _read(self):
        while True:
            try:
                data = self.src.recv(65536)
            except OSError:
                data = b''
            due = max(time.monotonic() + self.delay(), self.last)
            self.last = due
            self.q.put((due, data))
            if not data:
                return

    def _send(self):
        try:
            while True:
                due, data = self.q.get()
                wait = due - time.monotonic()
                if wait > 0:
                    time.sleep(wait)
                if not data:
                    try:
                        self.dst.shutdown(socket.SHUT_WR)
                    except OSError:
                        pass
                    return
                try:
                    self.dst.sendall(data)
                except OSError:
                    return
        finally:
            self.done.set()


class DelayProxy:
    def __init__(self, target, latency_ms: float, jitter_ms: float = 0, seed=None, host='127.0.0.1', port=0):
        if latency_ms < 0 or jitter_ms < 0:
            raise ValueError('延迟和抖动不能是负数')
        if jitter_ms > latency_ms:
            raise ValueError('抖动不能大于延迟（否则会出现负延迟）')
        self.target = tuple(target)
        self.latency, self.jitter = latency_ms / 1000, jitter_ms / 1000
        self.rng = random.Random(seed)
        self.rng_lock = threading.Lock()
        self.host, self._port = host, port
        self._open = 0
        self._lock = threading.Lock()
        self._sock = None
        self._stop = threading.Event()

    def _delay(self) -> float:
        if not self.jitter:
            return self.latency
        with self.rng_lock:
            return self.latency + self.rng.uniform(-self.jitter, self.jitter)

    @property
    def port(self) -> int:
        return self._sock.getsockname()[1]

    def open_connections(self) -> int:
        with self._lock:
            return self._open

    def start(self):
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._sock.bind((self.host, self._port))
        self._sock.listen(64)
        self._sock.settimeout(0.2)
        threading.Thread(target=self._accept, daemon=True).start()
        return self

    def _accept(self):
        while not self._stop.is_set():
            try:
                client, _ = self._sock.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            try:
                upstream = socket.create_connection(self.target, timeout=5)
            except OSError:
                client.close()
                continue
            upstream.settimeout(None)
            client.settimeout(None)
            for s in (client, upstream):
                s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            with self._lock:
                self._open += 1
            pumps = (_Pump(client, upstream, self._delay), _Pump(upstream, client, self._delay))
            for p in pumps:
                p.start()
            threading.Thread(target=self._reap, args=(client, upstream, pumps), daemon=True).start()

    def _reap(self, client, upstream, pumps):
        for p in pumps:
            p.done.wait()
        for s in (client, upstream):
            try:
                s.close()
            except OSError:
                pass
        with self._lock:
            self._open -= 1

    def stop(self):
        self._stop.set()
        if self._sock:
            self._sock.close()

    def __enter__(self):
        return self.start()

    def __exit__(self, *exc):
        self.stop()


def main(argv=None):
    parser = argparse.ArgumentParser(description='固定延迟（可选抖动）的 TCP 转发代理')
    parser.add_argument('--listen', type=int, required=True)
    parser.add_argument('--target', required=True, help='host:port')
    parser.add_argument('--latency', type=float, required=True, help='单向延迟，毫秒')
    parser.add_argument('--jitter', type=float, default=0, help='抖动，毫秒（默认 0）')
    parser.add_argument('--host', default='127.0.0.1')
    args = parser.parse_args(argv)
    host, port = args.target.rsplit(':', 1)
    proxy = DelayProxy((host, int(port)), args.latency, args.jitter, host=args.host, port=args.listen).start()
    print(f'代理 {args.host}:{proxy.port} → {args.target}，延迟 {args.latency} ± {args.jitter} ms', flush=True)
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        proxy.stop()


if __name__ == '__main__':
    main()
