"""worker 按需启动（compose profile "worker"）。不需要服务器。

- 平时玩的服务器（只用 compose.yaml）只有 minecraft 一个服务，行为不变。
- 测试 compose 不开 profile 时只起 minecraft：第 0～2 步和第 1 步测量不会多起一台 worker。
- test.sh step3 及以后打开 profile，worker 跟着起。
"""

import os
import subprocess

from mcd import REPO_ROOT, SCRIPTS

DOCKER = REPO_ROOT / "docker"


def services(*compose_args, env=None) -> list[str]:
    e = {**os.environ, "RCON_PASSWORD": "x", **(env or {})}
    e.pop("COMPOSE_PROFILES", None)
    e.update(env or {})
    out = subprocess.run(["docker", "compose", "--project-directory", str(DOCKER), *compose_args,
                          "config", "--services"], capture_output=True, text=True, env=e, check=True).stdout
    return sorted(out.split())


def test_play_server_unchanged():
    assert services("-f", str(DOCKER / "compose.yaml")) == ["minecraft"]


def test_test_compose_without_profile_has_no_worker():
    args = ["-f", str(DOCKER / "compose.yaml"), "-f", str(DOCKER / "compose.test.yaml")]
    assert services(*args) == ["minecraft"]
    assert services(*args, env={"COMPOSE_PROFILES": "worker"}) == ["minecraft", "worker"]


def test_test_sh_enables_worker_from_step3():
    text = (SCRIPTS / "test.sh").read_text()
    assert "COMPOSE_PROFILES" in text and "n >= 3" in text
