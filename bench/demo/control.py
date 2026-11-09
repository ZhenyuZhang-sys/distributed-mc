"""演示面板的控制（docs/step10-plan.md 7.1 第 2 条）。

只认白名单里的动作，每个动作对应一串固定的命令，走现有入口（runner.rcon → scripts/rcon.sh，runner.compose → lib.sh 的 tc，
runner.script → scripts/ 下的脚本）。同一时间只做一个动作，别的请求得到 Busy（HTTP 409）。
"""
from __future__ import annotations

import contextlib
import json
import threading
import time
from pathlib import Path

from bench.server import wait_until

SPEEDS = {'walk': 4.3, 'horse': 14.0, 'elytra': 30.0}
FEATURES = ('territory', 'deadline', 'throughput', 'hedge', 'waitdonor')
DONORS = ('worker', 'worker2')
ALL_WORKERS = ('worker', 'worker2', 'worker3')
NETEM_ARGS = ('35ms', '1%', '20mbit')   # 延迟、丢包、限速（fish 的 netem.sh：on [延迟] [丢包] [限速]）
SECONDS_RANGE = (10, 600)
ACTIONS = ('donor_on', 'donor_off', 'feature', 'fly_start', 'fly_stop', 'netem')


class BadRequest(ValueError):
    """请求不合法（HTTP 400）。"""


class Busy(RuntimeError):
    """已经有动作在做（HTTP 409）。"""


def check_flight(speed, seconds):
    if speed not in SPEEDS:
        raise BadRequest(f'速度只能是 {list(SPEEDS)}')
    if type(seconds) is not int or not SECONDS_RANGE[0] <= seconds <= SECONDS_RANGE[1]:
        raise BadRequest(f'时长要是 {SECONDS_RANGE[0]}～{SECONDS_RANGE[1]} 秒的整数')


class Controller:
    def __init__(self, runner, poller, flyer, wait_timeout=600, wait_interval=2):
        self.runner, self.poller, self.flyer = runner, poller, flyer
        self.wait_timeout, self.wait_interval = wait_timeout, wait_interval
        self._lock = threading.Lock()
        self.current = None

    @contextlib.contextmanager
    def busy(self, name):
        if not self._lock.acquire(blocking=False):
            raise Busy(f'正在做：{self.current}')
        self.current = name
        try:
            yield
        finally:
            self.current = None
            self._lock.release()

    # ---- 请求入口 ----

    def do(self, body) -> dict:
        if not isinstance(body, dict) or body.get('action') not in ACTIONS:
            raise BadRequest(f'动作只能是 {list(ACTIONS)}')
        action = body['action']
        if action == 'feature':
            if body.get('name') not in FEATURES or type(body.get('on')) is not bool:
                raise BadRequest(f'开关名只能是 {list(FEATURES)}，on 是 true / false')
        if action == 'netem' and type(body.get('on')) is not bool:
            raise BadRequest('on 是 true / false')
        if action == 'fly_start':
            check_flight(body.get('speed'), body.get('seconds'))
        if action == 'netem' and not self.runner.netem_supported():
            raise BadRequest('模拟网络要等 fish 新版的 scripts/netem.sh（NETEM_SERVICE、限速参数）进 main，现在先灰着')
        with self.busy(action):
            if action == 'donor_on':
                return dict(ok=True, workers=self.donor_on())
            if action == 'donor_off':
                self.donor_off()
                return dict(ok=True)
            if action == 'feature':
                self.feature(body['name'], body['on'])
                return dict(ok=True)
            if action == 'fly_start':
                return dict(ok=True, run=self.flyer.start(body['speed'], body['seconds']))
            if action == 'fly_stop':
                return dict(ok=True, run=self.flyer.stop())
            self.netem(body['on'])
            return dict(ok=True)

    # ---- 动作本身（A/B 编排直接调，调用方自己拿 busy） ----

    def status(self) -> dict:
        return json.loads(self.runner.rcon('donor status'))

    def worker_ids(self) -> list:
        return sorted(w['id'] for w in self.status().get('workers', []))

    def _wait(self, check, what):
        wait_until(check, self.wait_timeout, self.wait_interval, what)

    def donor_on(self) -> list:
        """起 worker、worker2，等两个都连上 coordinator，再开找需求。"""
        self.runner.compose('up', '-d', '--wait', '--wait-timeout', '600', *DONORS, timeout=660)
        self._wait(lambda: len(self.status().get('workers', [])) >= len(DONORS), 'donor 都连上')
        self.runner.rcon('donor demand on')
        return self.worker_ids()

    def donor_off(self):
        """关找需求，停掉所有 worker，等 coordinator 那边一个都不剩。"""
        self.runner.rcon('donor demand off')
        self.runner.compose('stop', *ALL_WORKERS, timeout=300)
        self._wait(lambda: self.status().get('workers', []) == [], 'donor 都断开')

    def feature(self, name, on):
        self.runner.rcon(f"donor feature {name} {'on' if on else 'off'}")

    def netem(self, on):
        for s in DONORS:
            if on:
                self.runner.script('netem.sh', 'on', *NETEM_ARGS, env={'NETEM_SERVICE': s})
            else:
                self.runner.script('netem.sh', 'off', env={'NETEM_SERVICE': s})
        self.poller.netem = on


class _ServerShim:
    """bench.layer2.fly 只用 server.rcon。"""

    def __init__(self, runner):
        self.rcon = runner.rcon


class Flyer:
    """放一架假玩家飞一轮（第 4 步的 bench.layer2.fly），飞的时候把这一轮告诉采样，让面板画飞行曲线。"""

    PLAYER = 'demobot'

    def __init__(self, runner, poller, out_root, fly=None):
        self.runner, self.poller, self.out_root = runner, poller, Path(out_root)
        if fly is None:
            from bench.layer2 import fly
        self.fly = fly
        self.thread = None
        self.run = None
        self.last = None

    def route(self):
        """面板里手动飞：每次一条没生成过的航线，z 方向隔 8000 格，计数存在 artifacts 里，重启面板也接着数。"""
        self.out_root.mkdir(parents=True, exist_ok=True)
        f = self.out_root / 'demo-routes.json'
        k = json.loads(f.read_text())['next'] if f.exists() else 1
        f.write_text(json.dumps(dict(next=k + 1)) + '\n')
        return (0.5, 8000.0 * k + 8.5)

    def fly_sync(self, run, start, speed, seconds, out_dir):
        self.poller.flight = dict(run=run, start_x=start[0], speed=speed)
        self.run = run
        try:
            return self.fly(_ServerShim(self.runner), run=run, player=self.PLAYER, start=start, speed=speed,
                            seconds=seconds, out_dir=Path(out_dir))
        finally:
            self.poller.flight = None
            self.run = None

    def start(self, speed_name, seconds) -> str:
        check_flight(speed_name, seconds)
        if self.thread and self.thread.is_alive():
            raise Busy(f'正在飞：{self.run}')
        run = 'demo-fly-' + time.strftime('%Y%m%dT%H%M%S', time.gmtime())
        start = self.route()

        def go():
            try:
                self.fly_sync(run, start, SPEEDS[speed_name], seconds, self.out_root / run)
                self.last = dict(run=run, ok=True)
            except Exception as e:  # 提前停下时 compose 会报"stopped"，归档照留
                self.last = dict(run=run, ok=False, error=str(e))

        self.thread = threading.Thread(target=go, daemon=True)
        self.thread.start()
        self.run = run
        return run

    def stop(self):
        if not self.run:
            raise BadRequest('现在没有在飞')
        self.runner.rcon(f'bench fly stop {self.run}')
        return self.run

    def state(self):
        return dict(run=self.run, last=self.last)
