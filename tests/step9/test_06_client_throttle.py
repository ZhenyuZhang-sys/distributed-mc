"""第 9 步 9.6（无头环境只做定性）：帧时间拉长到 80 ms（测试小 mod 在渲染里睡），2 秒左右客户端给 worker 发 PAUSE，
coordinator 看到这个 worker 暂停；恢复正常帧率后（窗口里的慢帧滑出去，再过 5 秒）发 RESUME。frametimes.csv 每秒一行。
"""

import pytest

from step9.headless_client import TESTHOOKS_JAR, prepare_mods, run_client

pytestmark = pytest.mark.long

NAME = "throttle1"
SLOW_FROM = 150   # worker 起来以后（第一次准备的下载已有缓存）
SLOW_FOR = 20


def test_slow_frames_pause_then_resume(server, tmp_path_factory):
    base = server.artifact_dir or tmp_path_factory.mktemp("client")
    mods = prepare_mods(base / "client-mods-throttle", extra=[TESTHOOKS_JAR])
    r = run_client(NAME, SLOW_FROM + SLOW_FOR + 60, mods, base / "client-throttle",
                   env={"CLIENT_JVM_ARGS": f"-Dmcdonate.testhooks.slowFrom={SLOW_FROM} -Dmcdonate.testhooks.slowFor={SLOW_FOR}"
                                           " -Dmcdonate.testhooks.slowMs=80"})
    log = r["log"].splitlines()
    assert any("worker 起来了" in l for l in log), "worker 没起来，测不了限速"
    pause = [l for l in log if "给 worker 发 PAUSE" in l]
    resume = [l for l in log if "给 worker 发 RESUME" in l]
    assert pause, "帧时间拉长后没发 PAUSE"
    assert resume, "恢复后没发 RESUME"
    assert log.index(pause[0]) < log.index(resume[0])
    assert "worker" in server.logs() and "暂停（玩家那边帧率低）" in server.logs(), "coordinator 没看到 worker 暂停"
