"""第 2 步（2.5）：bench mod 给每个生成阶段计时。

/bench stages 输出 {"timing": bool, "stages": {阶段: {count, cpu_ns, wall_ns, max_wall_ns}}}。
计时默认关闭，/bench timing on|off 切换。
"""

import csv
import json
import re

import pytest

from mcd import SCRIPTS, chunky, compose, run

# ChunkStatusTasks 里的各阶段，加上光照线程的实际工作
STAGES = ["structure_starts", "structure_references", "biomes", "noise", "surface",
          "carvers", "features", "initialize_light", "light", "spawn", "full", "light_engine"]


def stages(server) -> dict:
    return json.loads(server.rcon("bench stages"))


def stats(server) -> dict:
    return json.loads(server.rcon("bench stats"))


def container_cpu_usec() -> int:
    """测试服务器容器累计用掉的 CPU 时间（cgroup v2，微秒）。"""
    out = compose("exec", "-T", "minecraft", "cat", "/sys/fs/cgroup/cpu.stat")
    return int(re.search(r"^usage_usec (\d+)", out, re.M).group(1))


@pytest.fixture(scope="module")
def timed(server):
    """计时打开，在 (-5000, 5000) 生成一块地。返回 (chunk 数, 计数, 阶段, 容器 CPU 微秒)。

    先在别处生成一块地预热 JVM：刚启动时即时编译能占到容器 CPU 的 15% 以上，
    它不属于任何生成阶段，会把覆盖率拉低。
    """
    chunky(server, 0, -5000, 96)
    server.rcon("bench reset")
    server.rcon("bench timing on")
    cpu0 = container_cpu_usec()
    count = chunky(server, -5000, 5000, 128)
    cpu = container_cpu_usec() - cpu0
    result = (count, stats(server), stages(server), cpu)
    server.rcon("bench timing off")
    return result


def test_timing_off_by_default(server):
    s = stages(server)
    assert s["timing"] is False, s


def test_every_stage_counted(timed):
    _, _, s, _ = timed
    assert list(s["stages"]) == STAGES, list(s["stages"])
    for name, st in s["stages"].items():
        assert st["count"] > 0, (name, st)
        assert st["cpu_ns"] > 0 and st["wall_ns"] > 0, (name, st)
        assert 0 < st["max_wall_ns"] <= st["wall_ns"], (name, st)


def test_noise_count_matches_counter(timed):
    _, counts, s, _ = timed
    assert s["stages"]["noise"]["count"] == counts["noise"], (s["stages"]["noise"], counts)


def test_stages_csv(timed, tmp_path):
    # 给第 1 步用的导出脚本；要在下一个测试清零之前跑
    out = tmp_path / "stages.csv"
    run(str(SCRIPTS / "bench_stages.py"), str(out))
    rows = list(csv.DictReader(out.open()))
    assert [r["stage"] for r in rows] == STAGES
    assert abs(sum(float(r["cpu_share"]) for r in rows) - 1) < 0.01, rows


def test_timing_off_records_nothing(server, timed):
    server.rcon("bench reset")
    chunky(server, -5000, -5000, 32)
    s = stages(server)
    assert s["timing"] is False
    assert all(st["count"] == 0 for st in s["stages"].values()), s
    assert stats(server)["full_generated"] > 0  # 计数照常


@pytest.mark.slow
def test_stages_cover_container_cpu(timed):
    # 剩下的是主线程、存盘和 JVM 自己（即时编译、GC）。
    # 排查时按线程统计过：生成线程上的 CPU 几乎全部计到了各阶段里。
    _, _, s, cpu_usec = timed
    staged = sum(st["cpu_ns"] for st in s["stages"].values()) / 1000
    share = staged / cpu_usec
    print(f"各阶段 CPU 合计占容器 CPU 的 {share:.1%}")
    assert share >= 0.70, f"只占 {share:.1%}"
