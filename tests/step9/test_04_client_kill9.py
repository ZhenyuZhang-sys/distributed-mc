"""第 9 步 9.4：kill -9 游戏进程，worker 几秒内自己退出（父进程 PID 不在 / 标准输入 EOF 两道保险），不留进程。"""

import pytest

from step9.headless_client import TESTHOOKS_JAR, prepare_mods, run_client, snapshots, worker_procs

pytestmark = pytest.mark.long

NAME = "kill1"
KILL_AFTER = 200   # worker 已经起来（第一次准备的下载在 9.3 里做过，卷里有缓存）


def test_worker_exits_after_game_killed(server, tmp_path_factory):
    base = server.artifact_dir or tmp_path_factory.mktemp("client")
    mods = prepare_mods(base / "client-mods-kill", extra=[TESTHOOKS_JAR])
    r = run_client(NAME, 260, mods, base / "client-kill", env={"KILL_AFTER": str(KILL_AFTER)})
    assert r["killed"], "没执行 kill -9"
    before = [body for label, body in snapshots(r["ps"])]
    assert any(worker_procs(b) for b in before), "kill 之前 worker 就没起来"
    after = snapshots(r["ps_after_kill"])
    gone = next((int(label.split("t=")[1]) for label, body in after if not worker_procs(body)), None)
    assert gone is not None and gone <= 5, f"kill -9 游戏后 worker {gone} 秒还在（要 5 秒内退出）"
