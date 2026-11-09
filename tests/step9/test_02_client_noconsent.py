"""第 9 步 9.2：装了客户端 mod 但没同意：不启动任何进程，不下载任何东西（docs/step9-plan.md 5 节）。

真客户端（HeadlessMC，只装正式的客户端 mod 和 Fabric API，不装测试小 mod）用离线账号进服挂 40 秒：
- 客户端 mod 确实加载了，而且停在"等待同意"（日志里固定的一行）。这条是正向检查：mod 没加载或什么都不做时，下面几条会白过。
- 收到了服务器的邀请（服务器配了 publicHost），但没同意，所以：
    - 容器里只有客户端自己一个 java 进程，没有 worker 子进程；
    - 游戏目录里没有 mc-donate/（服务器 jar、worker 都放那里）；
    - coordinator 没有新的 worker 连上来。
"""

import pytest

from step9.headless_client import java_processes, prepare_mods, run_client, worker_names

NAME = "noconsent1"


@pytest.fixture(scope="module")
def run(server, tmp_path_factory):
    base = server.artifact_dir or tmp_path_factory.mktemp("client")
    before = worker_names(server)
    r = run_client(NAME, 40, prepare_mods(base / "client-mods-noconsent"), base / "client-noconsent")
    r["new_workers"] = worker_names(server) - before
    return r


def test_client_joined(server, run):
    assert f"{NAME} joined the game" in server.logs(), run["launcher"][-2000:]


def test_mod_loaded_and_waiting_for_consent(run):
    # Minecraft 的日志格式不带 logger 名字，这一行就是 ClientMod 启动时打的固定内容
    lines = [l for l in run["log"].splitlines() if "等待同意：mc-donate 默认关闭" in l]
    assert lines, "客户端 mod 没有报'等待同意'：\n" + "\n".join(l for l in run["log"].splitlines() if "mcdonate" in l)


def test_invite_received_but_ignored(server, run):
    assert f"给 {NAME} 发了 donor 邀请" in server.logs(), "服务器应该给装了 mod 的客户端发邀请"
    assert any("收到 donor 邀请" in l for l in run["log"].splitlines()), "客户端应该记下收到邀请（没同意，不用）"


def test_no_worker_process(run):
    procs = java_processes(run["ps"])
    assert not [p for p in procs if "fabric-server-launch" in p or "mcdonate.coordinator" in p], procs
    assert run["ps"], "没有进程快照"


def test_nothing_downloaded_or_created(run):
    assert "mc-donate" not in run["gamedir"], run["gamedir"]


def test_no_new_worker_registered(run):
    assert not run["new_workers"], run["new_workers"]


def test_no_mojang_download_without_consent():
    pytest.skip("等客户端容器的网络记录（只放行测试服务器和一个记 DNS 请求的小代理）：确认没同意时没访问 piston-meta")
