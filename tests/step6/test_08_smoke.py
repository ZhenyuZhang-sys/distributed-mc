"""第 6 步（6.6，降级为冒烟）：鞘翅速度飞 120 秒，0 个 donor 和 2 个 donor 各一次，只看方向对不对。

- 0 个 donor：关着找需求（donor 连着也不派活），等于原版。
- 2 个 donor：打开找需求。
- 两次在不同的、没生成过的地方飞，互不影响。
- 每秒记一次 /bench view（视距内没做好的 chunk 比例，简化的虚空率）和 MSPT；前后读容器的 cpu.stat 算服务器 CPU。
- 判定只要求方向：2 个 donor 时服务器 CPU 更低（donor 分担了生成）。虚空率只记录，不判（1～2 次的波动太大）。
  正式验收（5 次、置信区间）留到部署机器上，见 docs/step6-report.md 第 7 节。
"""

import json
import time

import pytest

from mcd import compose, donor_imports, worker_id

SPEED = 30        # 格/秒，鞘翅
DURATION = 120    # 秒
STEP_S = 0.5
Y = 200
# 两条航线，离其他测试和彼此都远
ROUTES = {"donors0": (0, -40000), "donors2": (0, 40000)}


def cpu_usec() -> int:
    for line in compose("exec", "-T", "minecraft", "cat", "/sys/fs/cgroup/cpu.stat").splitlines():
        k, v = line.split()
        if k == "usage_usec":
            return int(v)
    raise AssertionError("cpu.stat 里没有 usage_usec")


def fly(server, name: str, start: tuple[int, int]) -> dict:
    x, z = start
    server.rcon("bench reset")
    server.rcon(f"player {name} spawn at {x} {Y} {z} facing 0 0 in minecraft:overworld in spectator")
    void, mspt = [], []
    cpu0, t0 = cpu_usec(), time.monotonic()
    try:
        next_sample = t0
        while (now := time.monotonic()) - t0 < DURATION:
            x += SPEED * STEP_S
            server.rcon(f"tp {name} {x:.1f} {Y} {z}")
            if now >= next_sample:
                v = json.loads(server.rcon("bench view"))
                p = next((p for p in v["players"] if p["name"] == name), None)
                if p:
                    void.append(1 - p["ready"] / p["total"])
                mspt.append(v["mspt"])
                next_sample += 1
            time.sleep(STEP_S)  # 移动节奏，不是等条件
        cpu = (cpu_usec() - cpu0) / 1e6 / (time.monotonic() - t0)
        stats = json.loads(server.rcon("bench stats"))
    finally:
        server.rcon(f"player {name} kill")
    void.sort()
    return {
        "void_mean": sum(void) / len(void), "void_p95": void[int(len(void) * 0.95)],
        "mspt_mean": sum(mspt) / len(mspt), "mspt_max": max(mspt),
        "server_cores": cpu,
        "full_generated": stats["full_generated"], "full_resumed": stats["full_resumed"],
    }


@pytest.mark.slow
def test_elytra_smoke_0_vs_2_donors(server, worker, worker2):
    worker_id(server, "worker")
    worker_id(server, "worker2")
    server.rcon("donor demand off")
    r0 = fly(server, "smoke0", ROUTES["donors0"])
    before = len(donor_imports(server))
    server.rcon("donor demand on")
    try:
        r2 = fly(server, "smoke2", ROUTES["donors2"])
        status = json.loads(server.rcon("donor status"))
    finally:
        server.rcon("donor demand off")
    r2["imports_accepted"] = sum(1 for r in donor_imports(server)[before:] if r["accepted"])
    r2["discard_ratio"] = status["discard_ratio"]
    r2["discarded"] = status["discarded"]
    report = {"speed": SPEED, "duration_s": DURATION, "donors0": r0, "donors2": r2, "config": status["config"]}
    print(json.dumps(report, ensure_ascii=False))
    if server.artifact_dir:
        server.artifact_dir.mkdir(parents=True, exist_ok=True)
        (server.artifact_dir / "smoke.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    assert r2["imports_accepted"] > 0, "2 个 donor 时一个 tile 都没导入，donor 没起作用"
    assert r2["server_cores"] < r0["server_cores"], f"有 donor 时服务器 CPU 没有更低：{r0['server_cores']:.2f} → {r2['server_cores']:.2f} 核"
