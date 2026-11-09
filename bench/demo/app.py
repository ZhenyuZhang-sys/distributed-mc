"""把演示面板的几块拼起来：真机 runner、每秒采样的后台线程、控制、飞行、A/B。"""
from __future__ import annotations

import threading
from pathlib import Path

from bench.demo.ab import Job, run_ab
from bench.demo.control import Controller, Flyer
from bench.demo.poll import Poller
from bench.demo.runner import Runner
from bench.server import REPO_ROOT

OUT_ROOT = REPO_ROOT / 'artifacts' / 'demo'


def build(runs_dir: Path, out_root: Path = OUT_ROOT) -> dict:
    runner = Runner()
    poller = Poller(runner, runs_dir=runs_dir, env_every=10)   # docker inspect 每 10 秒一次就够
    flyer = Flyer(runner, poller, out_root)
    controller = Controller(runner, poller, flyer)
    job = Job(lambda **kw: run_ab(runner, controller, flyer, out_root, **kw), controller=controller)
    stop = threading.Event()
    threading.Thread(target=poller.loop, args=(stop,), daemon=True).start()
    return dict(poller=poller, controller=controller, ab=job, stop=stop)
