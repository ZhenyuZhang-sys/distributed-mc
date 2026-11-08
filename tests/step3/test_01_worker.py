"""第 3 步（3.1）：worker 服务器和测试服务器一起跑。

worker 用同一个镜像、同一个 seed，装 worker + bench，不装 coordinator；
端口不对外，绑另一组核，存档在 docker/test-data-worker/。
"""

import json
import re

from mcd import EXCHANGE, compose, run


def _listed_mods(srv) -> list[str]:
    return re.findall(r"^\s*- (mcdonate-\S+)", srv.logs(), re.M)


def test_worker_healthy(worker):
    assert worker.health() == "healthy"


def test_worker_mods(worker):
    assert "mcdonate-worker loaded" in worker.logs()
    mods = _listed_mods(worker)
    assert "mcdonate-worker" in mods and "mcdonate-bench" in mods, mods
    assert "mcdonate-coordinator" not in mods, mods


def test_server_has_no_worker_mod(server):
    assert "mcdonate-worker" not in _listed_mods(server)


def test_same_seed(server, worker):
    assert worker.rcon("seed") == server.rcon("seed") == f"Seed: [{server.seed}]"


def test_worker_has_own_world(worker):
    assert (worker.world_dir / "level.dat").exists()


def test_worker_not_published(worker):
    cid = compose("ps", "-q", "worker").strip()
    ports = json.loads(run("docker", "inspect", cid))[0]["NetworkSettings"]["Ports"]
    assert all(not v for v in ports.values()), f"worker 不该对外开端口：{ports}"


def test_worker_cpuset_separate(server, worker):
    def cpuset(service):
        cid = compose("ps", "-q", service).strip()
        return json.loads(run("docker", "inspect", cid))[0]["HostConfig"]["CpusetCpus"]

    a, b = cpuset("minecraft"), cpuset("worker")
    assert a and b and a != b, (a, b)


def test_exchange_dir_shared(server, worker):
    # worker 写、服务器读的交换目录：两边看到的是同一个
    for svc in ("worker", "minecraft"):
        out = compose("exec", "-T", svc, "sh", "-c", "ls -d /exchange")
        assert "/exchange" in out
    assert EXCHANGE.is_dir()
