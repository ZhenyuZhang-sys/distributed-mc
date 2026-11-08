"""重活锁（不需要服务器）。

- benchmark（bench.sh、test.sh --slow）拿全局锁的独占模式：它跑的时候，别的重活全部排队。
- 普通测试服务器和 Gradle 拿同一把锁的共享模式：几个目录的测试之间可以同时跑，
  但只要 benchmark 拿着独占锁就都要等；benchmark 也要等手上的共享持有者都结束。
- 不再按 NUMA 节点放行：分核只隔开 CPU，隔不开共用的磁盘和 Docker 守护进程
  （第 1 步测量时实测被测试的容器创建、删除和世界读写拖慢，详见 .claude/rules/development.md）。
- 本目录的锁：同一个目录里的测试、构建排队（共用 compose 项目和存档目录）。
"""

import os
import subprocess
import time

from mcd import SCRIPTS


def sh(script, env=None, timeout=20):
    e = {**os.environ, **(env or {})}
    e.pop("MCD_LOCK_HELD", None)
    e.pop("MCD_CHECKOUT_LOCK_HELD", None)
    return subprocess.run(["bash", "-c", f'source "{SCRIPTS}/lib.sh"; {script}'], capture_output=True,
                          text=True, env=e, timeout=timeout)


def hold(lock_file, shared=False):
    """另起一个进程拿住锁（独占或共享）30 秒。"""
    return subprocess.Popen(["flock", "-s" if shared else "-x", lock_file, "sleep", "30"])


def _try(fn, lock):
    # 3 秒内拿到锁就输出 got
    e = {**os.environ, "MCD_HEAVY_LOCK": lock}
    e.pop("MCD_LOCK_HELD", None)
    e.pop("MCD_CHECKOUT_LOCK_HELD", None)
    return subprocess.run(["timeout", "3", "bash", "-c", f'source "{SCRIPTS}/lib.sh"; {fn}; echo got'],
                          capture_output=True, text=True, env=e, timeout=20)


def test_benchmark_blocks_tests(tmp_path):
    # benchmark 拿着独占锁：普通测试（共享）拿不到，不管它用哪组核
    g = tmp_path / "global.lock"
    g.touch()
    p = hold(str(g))
    try:
        time.sleep(0.5)
        assert "got" not in _try("take_shared_lock", str(g)).stdout
    finally:
        p.kill()


def test_tests_run_side_by_side(tmp_path):
    # 另一个目录的测试拿着共享锁：普通测试照样拿得到
    g = tmp_path / "global.lock"
    g.touch()
    p = hold(str(g), shared=True)
    try:
        time.sleep(0.5)
        assert "got" in _try("take_shared_lock", str(g)).stdout
    finally:
        p.kill()


def test_benchmark_waits_for_tests(tmp_path):
    # 有测试拿着共享锁：benchmark 要等它们结束
    g = tmp_path / "global.lock"
    g.touch()
    p = hold(str(g), shared=True)
    try:
        time.sleep(0.5)
        assert "got" not in _try("take_heavy_lock", str(g)).stdout
    finally:
        p.kill()


def test_prepare_heavy_and_gradle_take_shared_lock():
    lib = (SCRIPTS / "lib.sh").read_text()
    prepare = lib[lib.index("prepare_heavy() {"):]
    prepare = prepare[:prepare.index("\n}")]
    assert "take_shared_lock" in prepare and "conflicts_with_bench" not in prepare, prepare
    assert "take_shared_lock" in (SCRIPTS / "gradle.sh").read_text()


def test_slow_tests_take_benchmark_lock():
    text = (SCRIPTS / "test.sh").read_text()
    assert 'if [[ -z "$marker" ]]; then\n  take_heavy_lock' in text


def test_local_env_overrides_cpuset(tmp_path):
    local = tmp_path / ".env.local"
    local.write_text("MC_CPUSET=32-35\nWORKER_CPUSET=36-39\n")
    r = sh("test_cpuset", env={"MCD_LOCAL_ENV": str(local)})
    assert r.stdout.strip() == "32-35", r.stderr


def test_gradle_avoids_benchmark_numa_node():
    # 构建仍然不碰 benchmark 所在的 NUMA 节点（锁之外多一层保险）
    r = sh('bench_nodes | paste -sd,; numa_nodes_of $(expand_cpus "$(build_cpus)") | paste -sd,')
    bench_nodes, build_nodes = r.stdout.strip().splitlines()
    assert not set(bench_nodes.split(",")) & set(build_nodes.split(",")), (bench_nodes, build_nodes)
