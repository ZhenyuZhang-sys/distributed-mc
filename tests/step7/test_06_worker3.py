"""7.3 的慢 donor：第三个 worker（worker3），只在 compose.test.yaml 里，单独的 profile。
平时的 test.sh 和别的步骤不起它；打开 profile worker3 时是 0.5 个核、绑 WORKER3_CPUSET、自己的存档目录。
"""

import json
import os

from mcd import REPO_ROOT, compose, limits


def config(profiles: str) -> dict:
    env = os.environ.copy()
    try:
        os.environ["COMPOSE_PROFILES"] = profiles
        return json.loads(compose("config", "--format", "json"))["services"]
    finally:
        os.environ.clear()
        os.environ.update(env)


def data_mount(svc: dict) -> str:
    return next(v["source"] for v in svc["volumes"] if v["target"] == "/data")


def test_not_in_normal_profiles():
    # test.sh 第 6 步及以后用 worker,worker2；--quick 和第 3～5 步只用 worker
    for profiles in ("worker,worker2", "worker", ""):
        assert "worker3" not in config(profiles), profiles


def test_worker3_profile_is_slow_and_separate():
    svcs = config("worker,worker2,worker3")
    w3 = svcs["worker3"]
    assert float(w3["cpus"]) == 0.5
    want = limits().get("WORKER3_CPUSET")
    if want:
        assert w3["cpuset"] == want
    assert data_mount(w3) == str(REPO_ROOT / "docker/test-data-worker3")
    assert len({data_mount(svcs[s]) for s in ("worker", "worker2", "worker3")}) == 3
    assert w3["environment"]["JVM_OPTS"].startswith("-Dmcdonate.coordinator=minecraft:25580")
    assert not w3.get("ports"), "donor 不对外开端口"


def test_not_running_during_normal_tests(server):
    running = compose("ps", "--format", "{{.Service}}").split()
    assert "worker3" not in running, running
