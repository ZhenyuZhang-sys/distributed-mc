"""pytest 的公共设置。

- server：整轮测试共用一台测试服务器，开始时换一个全新世界。
- 结束时检查日志，有 ERROR 或异常堆栈就判失败。
- 有测试被跳过，默认也算失败；加 --allow-skip 才放过。
"""

from __future__ import annotations

import time
import json
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path

import pytest

from mcd import REPO_ROOT, SCRIPTS, Server, compose, limits, run, wait_until
from bench.server import quarantine_chunky_tasks, quarantine_worker_chunky_tasks
from analysis.mca import read_chunk, read_world_metadata

DEFAULT_SEED = 424242


def pytest_addoption(parser):
    parser.addoption("--allow-skip", action="store_true", help="有测试被跳过也算通过")


def prepare_step0():
    # benchmark（--slow）持独占锁（"1"），普通测试持共享锁（"shared"），见 scripts/lib.sh
    if os.environ.get("MCD_LOCK_HELD") not in ("1", "shared"):
        pytest.fail("服务器测试请通过 scripts/test.sh 运行，保证整轮持锁。")
    profile_path = REPO_ROOT / "tests/fixtures/mca/spawn_profile.json"
    if not profile_path.exists():
        pytest.fail("缺少出生区参考 profile；先运行 scripts/calibrate_step0.sh。")
    profile = json.loads(profile_path.read_text())
    evidence = Path(os.environ["MCD_ARTIFACT_DIR"])
    run_metadata = {
        "git_commit": run("git", "rev-parse", "HEAD").strip(),
        "working_tree": run("git", "status", "--short"),
        "limits": limits(), "seed": DEFAULT_SEED,
    }
    (evidence / "run.json").write_text(json.dumps(run_metadata, ensure_ascii=False, indent=2) + "\n")
    (evidence / "requirements.txt").write_text(subprocess.check_output([os.sys.executable, "-m", "pip", "freeze"], text=True))
    active = None
    try:
        def create(prefix):
            nonlocal active
            level = f"{prefix}{time.time_ns()}"
            started = datetime.now(timezone.utc).isoformat()
            active = Server(level, DEFAULT_SEED, started, artifact_dir=evidence / level)
            if not compose("ps", "-q", "minecraft").strip():
                quarantine_chunky_tasks(evidence / level)
                quarantine_worker_chunky_tasks(evidence / level)
            run(str(SCRIPTS / "new_world.sh"), level, str(DEFAULT_SEED), timeout=900)
            wait_until(lambda: "Done (" in active.logs(), timeout=30, what="启动完成")
            return active

        a = create("a")
        initial_a = a.snapshot(evidence / "a-initial")
        meta = read_world_metadata(initial_a)
        bounds = profile["allowed_relative_bounds"]
        sentinel = (meta.spawn[0] // 16 + max(abs(v) for v in bounds) + 128, meta.spawn[2] // 16)
        a.chunky_square(*sentinel, 2)
        snapshot_a = a.snapshot(evidence / "a-dirty")
        chunk = read_chunk(snapshot_a, *sentinel)
        assert chunk is not None and chunk.status == "minecraft:full"
        compose("stop", timeout=180)
        a.archive_logs(evidence / "a-logs")
        assert not a.error_lines(), a.error_lines()

        b = create("b")
        b.initial_snapshot = b.snapshot(evidence / "b-initial")
        b.predecessor = {"level": a.level, "sentinel": sentinel, "snapshot": snapshot_a}
        run_metadata.update(world_a=a.level, world_b=b.level, sentinel=sentinel)
        (evidence / "run.json").write_text(json.dumps(run_metadata, ensure_ascii=False, indent=2) + "\n")
        yield b
    finally:
        if active is not None and not active.retired:
            active.finish()


def _donor(server, service: str):
    """donor 服务器（第 3 步起 worker，第 6 步起 worker2）：和测试服务器一起起来，
    同一个 seed、同名的全新世界，存档目录分开。跟着 server 走：每一步换世界时一起换。"""
    w = Server(level=server.level, seed=server.seed, started_at=server.started_at,
               artifact_dir=(server.artifact_dir / service) if server.artifact_dir else None, service=service)
    yield w
    # 在测试服务器收尾（停容器）之前：日志存进证据目录，再查错误
    if server.retired:
        return
    if w.artifact_dir:
        w.archive_logs(w.artifact_dir / "logs")
    bad = w.error_lines()
    if bad:
        pytest.fail(f"{service} 日志里有错误：\n" + "\n".join(bad[:30]), pytrace=False)


@pytest.fixture(scope="module")
def worker(server):
    yield from _donor(server, "worker")


@pytest.fixture(scope="module")
def worker2(server, worker):
    yield from _donor(server, "worker2")


_skipped: list[str] = []


def pytest_runtest_logreport(report):
    if report.skipped:
        _skipped.append(report.nodeid)


def pytest_sessionfinish(session, exitstatus):
    if _skipped and exitstatus == 0 and not session.config.getoption("--allow-skip"):
        tr = session.config.pluginmanager.get_plugin("terminalreporter")
        if tr:
            tr.write_line(f"有 {len(_skipped)} 个测试被跳过，这一步还没完成，按失败处理：", red=True)
            for nodeid in _skipped:
                tr.write_line(f"  {nodeid}")
        session.exitstatus = 1


@pytest.fixture(scope="session")
def server_manager():
    from bench.session import ServerManager
    manager = ServerManager(prepare_step0)
    try:
        yield manager
    finally:
        manager.finish()


@pytest.fixture(scope="module")
def server(request, server_manager):
    import re
    match = re.search(r"step(\d+)", str(request.node.path))
    if not match:
        pytest.fail("服务器 fixture 缺少测试阶段")
    return server_manager.acquire(int(match.group(1)))


@pytest.fixture(scope="session", autouse=True)
def managed_termination():
    import signal
    previous = signal.getsignal(signal.SIGTERM)
    def interrupt(signum, frame):
        raise KeyboardInterrupt(f"收到信号 {signum}")
    signal.signal(signal.SIGTERM, interrupt)
    try:
        yield
    finally:
        signal.signal(signal.SIGTERM, previous)
