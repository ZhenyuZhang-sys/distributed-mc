"""pytest 的公共设置。

- server：整轮测试共用一台测试服务器，开始时换一个全新世界。
- 结束时检查日志，有 ERROR 或异常堆栈就判失败。
- 有测试被跳过，默认也算失败；加 --allow-skip 才放过。
"""

from __future__ import annotations

import time
from datetime import datetime, timezone

import pytest

from mcd import SCRIPTS, Server, run

DEFAULT_SEED = 424242


def pytest_addoption(parser):
    parser.addoption("--allow-skip", action="store_true", help="有测试被跳过也算通过")


@pytest.fixture(scope="session")
def server():
    started_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    level = f"t{int(time.time())}"
    run(str(SCRIPTS / "new_world.sh"), level, str(DEFAULT_SEED), timeout=900)
    srv = Server(level=level, seed=DEFAULT_SEED, started_at=started_at)
    yield srv
    bad = srv.error_lines()
    if bad:
        pytest.fail("服务器日志里有错误：\n" + "\n".join(bad[:30]), pytrace=False)


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
