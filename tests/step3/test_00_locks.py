"""不同目录的测试和构建不再排队；benchmark 照旧独占。不需要服务器。

- 全局锁（/tmp/mc-donate-heavy.lock）：只有 benchmark（take_heavy_lock）拿。
- 本目录的锁：测试、构建拿，同一个目录里不会两个同时跑（共用 compose 项目和存档目录）。
- 本目录用哪组核：docker/.env.local（不进 git），叠在 n100.env 之后。
- Gradle 构建不碰 benchmark 所在的 NUMA 节点。
- 普通测试用的核和 benchmark 不在同一个 NUMA 节点上才不等它；否则照旧排队。--slow 和 benchmark 一样独占。
"""

import subprocess
import time

from mcd import SCRIPTS


def sh(script, env=None, timeout=20):
    import os
    e = {**os.environ, **(env or {})}
    e.pop("MCD_LOCK_HELD", None)
    return subprocess.run(["bash", "-c", f'source "{SCRIPTS}/lib.sh"; {script}'], capture_output=True,
                          text=True, env=e, timeout=timeout)


def hold(lock_file):
    """另起一个进程拿住锁文件。"""
    return subprocess.Popen(["flock", lock_file, "sleep", "30"])


def test_checkout_lock_ignores_global_lock(tmp_path):
    g = tmp_path / "global.lock"
    g.touch()
    p = hold(str(g))
    try:
        time.sleep(0.5)
        t0 = time.monotonic()
        r = sh("take_checkout_lock; echo ok", env={"MCD_HEAVY_LOCK": str(g)})
        assert r.stdout.strip() == "ok", r.stderr
        assert time.monotonic() - t0 < 5
    finally:
        p.kill()


def test_benchmark_lock_still_exclusive(tmp_path):
    g = tmp_path / "global.lock"
    g.touch()
    p = hold(str(g))
    try:
        time.sleep(0.5)
        r = subprocess.run(["bash", "-c", f'source "{SCRIPTS}/lib.sh"; HEAVY_LOCK="{g}"; '
                                          'timeout 3 bash -c "source \\"$0\\"; HEAVY_LOCK=\\"$1\\"; take_heavy_lock; echo got" '
                                          f'"{SCRIPTS}/lib.sh" "{g}"'],
                           capture_output=True, text=True, timeout=20)
        assert "got" not in r.stdout, "benchmark 锁被占着时不该拿到"
    finally:
        p.kill()


def test_local_env_overrides_cpuset(tmp_path):
    local = tmp_path / ".env.local"
    local.write_text("MC_CPUSET=16-19\nWORKER_CPUSET=20-23\n")
    r = sh("test_cpuset", env={"MCD_LOCAL_ENV": str(local)})
    assert r.stdout.strip() == "16-19", r.stderr


def test_gradle_avoids_benchmark_numa_node():
    # 构建不碰 benchmark 所在的 NUMA 节点（不抢内存带宽）
    r = sh("bench_nodes | paste -sd,; build_cpus; numa_nodes_of $(expand_cpus \"$(build_cpus)\") | paste -sd,")
    bench_nodes, build, build_nodes = r.stdout.strip().splitlines()
    assert build, r.stderr
    assert not set(bench_nodes.split(",")) & set(build_nodes.split(",")), (bench_nodes, build_nodes)


def _prepare_like(env, lock):
    # prepare_heavy 里决定要不要等 benchmark 的那一段（不碰 docker）
    script = ('[[ "$(conflicts_with_bench)" == 1 ]] && take_heavy_lock; echo ran')
    return sh(f"timeout 3 bash -c 'source \"{SCRIPTS}/lib.sh\"; {script}'",
              env={**env, "MCD_HEAVY_LOCK": lock}, timeout=20)


def test_benchmark_running_default_cores_wait(tmp_path):
    # 没配 .env.local：测试服务器用 n100.env 的 12-15，和 benchmark 同一个 NUMA 节点 → 等它
    g = tmp_path / "global.lock"
    g.touch()
    p = hold(str(g))
    try:
        time.sleep(0.5)
        r = _prepare_like({"MCD_LOCAL_ENV": str(tmp_path / "none")}, str(g))
        assert "ran" not in r.stdout
    finally:
        p.kill()


def test_benchmark_running_other_node_proceeds(tmp_path):
    # 配到别的 NUMA 节点（测试服务器和两个 worker 都在节点 2）→ 不等 benchmark
    local = tmp_path / ".env.local"
    local.write_text("MC_CPUSET=32-35\nWORKER_CPUSET=36-39\nWORKER2_CPUSET=40-43\n")
    g = tmp_path / "global.lock"
    g.touch()
    p = hold(str(g))
    try:
        time.sleep(0.5)
        r = _prepare_like({"MCD_LOCAL_ENV": str(local), "COMPOSE_PROFILES": "worker,worker2"}, str(g))
        assert r.stdout.strip() == "ran", r.stderr
    finally:
        p.kill()


def test_worker_on_benchmark_node_waits(tmp_path):
    # 测试服务器不冲突，但 worker 落在 benchmark 的节点上 → 照样等
    local = tmp_path / ".env.local"
    local.write_text("MC_CPUSET=32-35\nWORKER_CPUSET=8-11\n")
    g = tmp_path / "global.lock"
    g.touch()
    p = hold(str(g))
    try:
        time.sleep(0.5)
        r = _prepare_like({"MCD_LOCAL_ENV": str(local), "COMPOSE_PROFILES": "worker"}, str(g))
        assert "ran" not in r.stdout
    finally:
        p.kill()


def test_slow_tests_take_benchmark_lock():
    # --slow 的性能测试和 benchmark 一样独占
    text = (SCRIPTS / "test.sh").read_text()
    assert 'if [[ -z "$marker" ]]; then\n  take_heavy_lock' in text
