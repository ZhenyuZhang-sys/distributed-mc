"""第 9 步 9.3：同意后 worker 启动并出现在 coordinator 的注册表里；退出世界后 worker 停止。

真客户端（HeadlessMC）装正式的客户端 mod + Fabric API + 只给测试的小 mod（mcdonate-client-testhooks）。小 mod 进服后像玩家那样
打开设置、按开关，在同意界面勾选 EULA、按同意（走和点击一样的代码路径）；进服 LEAVE 秒后退出世界。
worker 第一次要从 Mojang 下载服务器 jar、从 Fabric 下载库（容器里联网），所以给足时间。
"""

import pytest

from step9.headless_client import TESTHOOKS_JAR, prepare_mods, run_client, snapshots, worker_procs

pytestmark = pytest.mark.long

NAME = "consent1"
LEAVE = 240     # 进服多少秒后退出世界（第一次准备要下载服务器和 Fabric 的库）
STAY = 300      # 一共挂多久（退出后再看 60 秒）


@pytest.fixture(scope="module")
def run(server, tmp_path_factory):
    base = server.artifact_dir or tmp_path_factory.mktemp("client")
    mods = prepare_mods(base / "client-mods-consent", extra=[TESTHOOKS_JAR])
    return run_client(NAME, STAY, mods, base / "client-consent",
                      env={"CLIENT_JVM_ARGS": f"-Dmcdonate.testhooks.leaveAfter={LEAVE}"})


def test_consent_given_through_ui(run):
    assert "测试钩子：同意界面" in run["log"], "测试钩子没碰到同意界面"
    assert "玩家同意了（含 Minecraft EULA）" in run["log"]


def test_worker_started_and_registered(server, run):
    assert "worker 起来了" in run["log"], [l for l in run["log"].splitlines() if "mcdonate" in l][-20:]
    snaps = snapshots(run["ps"])
    assert any(worker_procs(body) for _, body in snaps), "进程快照里从没出现过 worker"
    logs = server.logs()
    assert f"给 {NAME} 发了 donor 邀请" in logs
    # 客户端起的 worker 带着 token 连上（coordinator 的"worker N（名字）连上"），日志里不出现 token 本身
    assert any("连上" in l for l in logs.splitlines()), "coordinator 没看到 worker 连上"


def test_worker_low_priority_and_memory(run):
    procs = [p for _, body in snapshots(run["ps"]) for p in worker_procs(body)]
    assert procs
    assert any("-Xmx2048M" in p for p in procs), procs[:1]
    assert any("nice" in p or "-XX:ActiveProcessorCount=3" in p for p in procs), procs[:1]


def test_worker_stops_after_leaving_world(run):
    snaps = snapshots(run["ps"])
    left_at = LEAVE + 15  # 退出世界后 15 秒内
    after = [body for label, body in snaps if label.startswith("t=") and int(label[2:]) >= left_at]
    assert after, "没有退出世界以后的进程快照"
    assert not worker_procs(after[-1]), "退出世界后 worker 还在：\n" + after[-1]
