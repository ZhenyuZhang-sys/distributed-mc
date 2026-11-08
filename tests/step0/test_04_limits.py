"""第 0 步：测试服务器按 N100 的规格限资源，CPU 频率固定。

期望值来自 docker/n100.env，环境变量可以覆盖（和 compose 的规则一样）。
"""

import json

from mcd import SCRIPTS, compose, limits, run


def _inspect(server) -> dict:
    cid = compose("ps", "-q", "minecraft").strip()
    return json.loads(run("docker", "inspect", cid))[0]["HostConfig"]


def test_cpuset(server):
    assert _inspect(server)["CpusetCpus"] == limits()["MC_CPUSET"]


def test_cpu_quota(server):
    assert _inspect(server)["NanoCpus"] == int(float(limits()["MC_CPUS"]) * 1e9)


def test_container_memory(server):
    want = limits()["MC_CONTAINER_MEMORY"].lower()
    assert want.endswith("g"), f"MC_CONTAINER_MEMORY 请用 g 作单位：{want}"
    assert _inspect(server)["Memory"] == int(want[:-1]) * 1024**3


def test_java_heap(server):
    # itzg 镜像把 INIT_MEMORY / MAX_MEMORY 变成 -Xms / -Xmx
    out = compose("exec", "-T", "minecraft", "sh", "-c",
                  r"for p in /proc/[0-9]*; do tr '\0' ' ' < $p/cmdline 2>/dev/null; echo; done")
    java = [l for l in out.splitlines() if "java" in l and "-Xmx" in l]
    assert java, "容器里没找到 java 进程"
    lim = limits()
    assert f"-Xmx{lim['MC_MAX_MEMORY']}" in java[0], java[0]
    assert f"-Xms{lim['MC_INIT_MEMORY']}" in java[0], java[0]


def test_cpu_frequency_pinned():
    # 不需要服务器：直接看 sysfs。没固定就提示去跑 test_config.sh pin
    run(str(SCRIPTS / "test_config.sh"), "check")
