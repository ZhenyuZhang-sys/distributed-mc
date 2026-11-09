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

    def compose(self, *args, timeout=120) -> str:
        return compose(*args, timeout=timeout)

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
