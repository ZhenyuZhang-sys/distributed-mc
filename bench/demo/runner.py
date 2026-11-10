"""演示面板接真机的那一层：每个调用都走现有入口（scripts/rcon.sh、lib.sh 的 tc、scripts/ 下的脚本、宿主机 cgroup）。"""
from __future__ import annotations

import json
import os
import re
import subprocess
import threading

from bench.server import SCRIPTS, compose, run

_COLOR = re.compile(r'§.')


class Runner:
    def __init__(self):
        from bench.layer2_campaign import test_project
        self.project = test_project()
        self._cgroup = None
        self._lock = threading.Lock()

    def rcon(self, command: str) -> str:
        return _COLOR.sub('', run(str(SCRIPTS / 'rcon.sh'), command, timeout=30)).strip()

    def rcon_worker(self, service: str, command: str) -> str:
        """发给 donor 容器（worker、worker2……）的控制台命令，比如测试环境的 /donor boost on。"""
        return _COLOR.sub('', run(str(SCRIPTS / 'rcon.sh'), f'--{service}', command, timeout=30)).strip()

    def compose(self, *args, timeout=120, env=None) -> str:
        if not env:
            return compose(*args, timeout=timeout)
        r = subprocess.run(['bash', '-c', 'source "$1"; shift; tc "$@"', 'bash', str(SCRIPTS / 'lib.sh'), *args],
                           env={**os.environ, **env}, capture_output=True, text=True, timeout=timeout)
        if r.returncode:
            raise RuntimeError(f'compose {" ".join(args)} 退出码 {r.returncode}：{(r.stderr or r.stdout)[-400:]}')
        return r.stdout

    def slot_cpuset(self) -> str:
        """这个槽位服务器的核：环境变量 > docker/.env.local > docker/n100.env（和 lib.sh 的 test_cpuset 一样）。"""
        if os.environ.get('MC_CPUSET'):
            return os.environ['MC_CPUSET']
        for name in ('.env.local', 'n100.env'):
            p = SCRIPTS.parent / 'docker' / name
            if p.exists():
                for line in p.read_text().splitlines():
                    if line.startswith('MC_CPUSET='):
                        return line.split('=', 1)[1].strip()
        raise RuntimeError('找不到服务器的核（MC_CPUSET）')

    def local_env(self, key):
        """这个槽位 docker/.env.local 里的一项（没有是 None）。"""
        p = SCRIPTS.parent / 'docker' / '.env.local'
        if p.exists():
            for line in p.read_text().splitlines():
                if line.startswith(key + '='):
                    return line.split('=', 1)[1].strip()
        return None

    def worker_cpu(self, services) -> dict:
        """每个 donor 容器的 (time_ns, usage_usec)，飞前飞后各取一次算核数（宿主机 cgroup，同 bench/metrics.py）。"""
        from bench.metrics import Cgroup
        out = {}
        for svc in services:
            cid = compose('ps', '-q', svc).strip()
            if cid:
                s = Cgroup(cid, run).sample()
                out[svc] = (s['time_ns'], s['usage_usec'])
        return out

    def server_log_since(self, t: float) -> list:
        """服务器 t（unix 秒）以后的日志行（数 Can't keep up、moved too quickly 用）。"""
        return compose('logs', '--since', str(int(t)), '--no-log-prefix', 'minecraft', timeout=120).splitlines()

    def server_log_ts(self, t: float) -> list:
        """同上，每行带 Docker 的时间戳（UTC，到纳秒）：查 MSPT 尖峰时和 GC 日志、导入日志对时间。"""
        return compose('logs', '--since', str(int(t)), '--timestamps', '--no-log-prefix', 'minecraft', timeout=120).splitlines()

    def service_log(self, service: str, t: float) -> list:
        """某个服务 t（unix 秒）以后的日志，带时间戳（donor 崩了时存进归档）。"""
        return compose('logs', '--since', str(int(t)), '--timestamps', '--no-log-prefix', service, timeout=120).splitlines()

    def worker_jar(self) -> dict:
        """donor 容器挂的 worker jar（build/mods-worker）和刚构建的（worker/build/libs）的 md5 前 8 位；没有文件是 None。"""
        import hashlib
        root = SCRIPTS.parent

        def md5(p):
            return hashlib.md5(p.read_bytes()).hexdigest()[:8] if p.exists() else None
        return dict(mounted=md5(root / 'build/mods-worker/mcdonate-worker-0.1.0.jar'),
                    built=md5(root / 'worker/build/libs/mcdonate-worker-0.1.0.jar'))

    def services(self) -> list:
        """compose 里定义了的服务（不管起没起）。"""
        return compose('config', '--services').split()

    def script(self, name, *args, env=None, timeout=600) -> str:
        if '/' in name or not (SCRIPTS / name).is_file():
            raise ValueError(f'没有这个脚本：{name}')
        r = subprocess.run(['bash', str(SCRIPTS / name), *args], env={**os.environ, **(env or {})},
                           capture_output=True, text=True, timeout=timeout)
        if r.returncode:
            raise RuntimeError(f'{name} 退出码 {r.returncode}：{(r.stderr or r.stdout)[-400:]}')
        return r.stdout

    def cpu(self):
        """服务器容器的 (time_ns, usage_usec)。换世界会重建容器：cgroup 身份变了就重新找。"""
        from bench.metrics import Cgroup
        with self._lock:
            for _ in range(2):
                if self._cgroup is None:
                    cid = compose('ps', '-q', 'minecraft').strip()
                    if not cid:
                        raise RuntimeError('服务器容器没在跑')
                    self._cgroup = Cgroup(cid, run)
                try:
                    s = self._cgroup.sample()
                    return s['time_ns'], s['usage_usec']
                except (RuntimeError, OSError):
                    self._cgroup = None
            raise RuntimeError('读不到服务器容器的 cgroup')

    def container_env(self, service):
        cid = compose('ps', '-q', service).strip()
        if not cid:
            return None
        env = json.loads(run('docker', 'inspect', cid))[0]['Config'].get('Env') or []
        return dict(e.split('=', 1) for e in env if '=' in e)

    def netem_supported(self) -> bool:
        """fish 新版的 netem.sh 能选服务（NETEM_SERVICE）、能限速；旧版只管 worker 一个。"""
        try:
            return 'NETEM_SERVICE' in (SCRIPTS / 'netem.sh').read_text()
        except OSError:
            return False
