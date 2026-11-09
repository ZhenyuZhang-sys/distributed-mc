"""演示面板的每秒采样（docs/step10-plan.md 7.1 第 1 条）。

每次 sample() 发两条 RCON（`bench tick`、`donor status`），读一次宿主机 cgroup，正在飞时再读这一轮的
seconds.jsonl 和 provenance.jsonl，拼成一行存进环形缓冲。某个来源出错时那几项是 None，错误记进 errors，其他照常。
"""
from __future__ import annotations

import collections
import json
import threading
import time
from pathlib import Path

from bench import layer2_stats

WORKER_SERVICES = ('worker', 'worker2', 'worker3')
DISCARD_REASONS = ('late', 'other_world', 'server')
NETEM_LABEL = '模拟网络：35ms / 20mbit / 1% 丢包'


def _complete_jsonl(path: Path) -> list[dict]:
    """只要写完整的行（最后一行没写完换行就不算），和地图服务器一样。"""
    try:
        data = path.read_bytes()
    except FileNotFoundError:
        return []
    return [json.loads(x) for x in data.split(b'\n')[:-1] if x.strip()]


class Poller:
    def __init__(self, runner, runs_dir, clock=time.monotonic, capacity=1800, env_every=1):
        self.runner = runner
        self.runs_dir = Path(runs_dir)
        self.clock = clock
        self.rows = collections.deque(maxlen=capacity)
        self.count = 0
        self.lock = threading.Lock()
        self.flight = None   # 正在飞：dict(run, start_x, speed)；由 Flyer 设置
        self.netem = False   # 模拟网络开着没有；由 Controller 设置
        self.env_every = env_every
        self._prev = None
        self._t0 = None
        self._warn_env = []

    # ---- 各个来源 ----

    def _rcon_json(self, source, command, errors):
        try:
            text = self.runner.rcon(command)
            return json.loads(text)
        except (ValueError, RuntimeError, OSError) as e:
            errors[source] = f'{command}：{e}' if not isinstance(e, ValueError) else f'{command} 回的不是 JSON：{text[:80]}'
            return None

    def _cpu(self, errors):
        try:
            return self.runner.cpu()
        except (RuntimeError, OSError, ValueError, KeyError, StopIteration) as e:
            errors['cpu'] = str(e)
            return None

    def _watchdog_warnings(self):
        if self.count % self.env_every:
            return self._warn_env
        out = []
        for service in WORKER_SERVICES:
            try:
                env = self.runner.container_env(service)
            except (RuntimeError, OSError, ValueError):
                continue
            if env is not None and env.get('MAX_TICK_TIME') != '-1':
                out.append(f'{service} 的看门狗没关（没有 MAX_TICK_TIME=-1），一个 tick 卡满 60 秒会被杀掉')
        self._warn_env = out
        return out

    def _flight(self):
        f = self.flight
        if not f:
            return None
        run = self.runs_dir / f['run']
        seconds = _complete_jsonl(run / 'seconds.jsonl')
        out = dict(run=f['run'], t_s=None, void_rate=None, stall_fraction=None, stall_fraction_total=None,
                   distance_ratio=None, donor_chunks=0, ahead_s=None, ahead_total=None)
        if not seconds:
            return out
        last = seconds[-1]
        ticks = sum(s.get('ticks', 0) for s in seconds)
        out.update(t_s=last['t_s'], void_rate=last.get('void_rate'),
                   stall_fraction=last['stalled_ticks'] / last['ticks'] if last.get('ticks') else None,
                   stall_fraction_total=sum(s.get('stalled_ticks', 0) for s in seconds) / ticks if ticks else None)
        if f.get('speed') and last['t_s']:
            out['distance_ratio'] = (last['x'] - f['start_x']) / (f['speed'] * last['t_s'])
        prov = _complete_jsonl(run / 'provenance.jsonl')
        leads = layer2_stats.donor_leads(prov, seconds)
        donor_t = [e['t_ms'] for e in prov if e.get('kind') == 'donor']
        recent = [lead for lead, t in zip(leads, donor_t) if t > (last['t_s'] - 1) * 1000]
        out.update(donor_chunks=len(leads), ahead_total=sum(x >= 0 for x in leads) / len(leads) if leads else None,
                   ahead_s=sum(x >= 0 for x in recent) / len(recent) if recent else None)
        return out

    # ---- 一行 ----

    def sample(self) -> dict:
        now = self.clock()
        if self._t0 is None:
            self._t0 = now
        errors = {}
        tick = self._rcon_json('tick', 'bench tick', errors)
        status = self._rcon_json('donor', 'donor status', errors)
        cpu = self._cpu(errors)
        prev = self._prev or {}
        row = dict(i=self.count, t_s=now - self._t0, time=time.time(), errors=errors,
                   mspt_mean=tick and tick.get('mspt_recent_mean'), mspt_max=tick and tick.get('mspt_recent_max'),
                   mspt_100=tick and tick.get('mspt_100'), tps=None, cpu_cores=None,
                   donors_online=None, imports=None, discards=None, features=None, demand=None)
        if tick and prev.get('tick') is not None and now > prev['now']:
            row['tps'] = (tick['tick'] - prev['tick']) / (now - prev['now'])
        if cpu and prev.get('cpu') and cpu[0] > prev['cpu'][0]:
            row['cpu_cores'] = (cpu[1] - prev['cpu'][1]) / 1e6 / ((cpu[0] - prev['cpu'][0]) / 1e9)
        if status:
            row.update(donors_online=len(status.get('workers', [])), features=status.get('features'), demand=status.get('demand'))
            if prev.get('status'):
                p = prev['status']
                row['imports'] = status.get('accepted', 0) - p.get('accepted', 0)
                row['discards'] = {k: status.get('discarded', {}).get(k, 0) - p.get('discarded', {}).get(k, 0) for k in DISCARD_REASONS}
        warnings = list(self._watchdog_warnings())
        if self.netem:
            warnings.append(NETEM_LABEL)
        row['warnings'] = warnings
        row['flight'] = self._flight()
        self._prev = dict(now=now, tick=tick['tick'] if tick else prev.get('tick'), cpu=cpu or prev.get('cpu'),
                          status=status or prev.get('status'))
        with self.lock:
            self.rows.append(row)
            self.count += 1
        return row

    def series(self, since: int) -> dict:
        with self.lock:
            return dict(next=self.count, rows=[r for r in self.rows if r['i'] >= since])

    def latest(self):
        with self.lock:
            return self.rows[-1] if self.rows else None

    def loop(self, stop: threading.Event, interval=1.0):
        """后台线程：每秒采一次，直到 stop。"""
        while not stop.is_set():
            t = time.monotonic()
            try:
                self.sample()
            except Exception as e:  # 面板不能因为一次采样出错就停
                with self.lock:
                    self.rows.append(dict(i=self.count, errors=dict(poll=repr(e)), warnings=[], flight=None))
                    self.count += 1
            stop.wait(max(0.0, interval - (time.monotonic() - t)))
