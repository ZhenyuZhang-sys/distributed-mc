"""11-09 调度（用户真机上"开不开 donor 差别不大"，distributed-mc-75 查到）：worker 一个 tile 里的 16 个 chunk 是一个一个同步要的
（getChunk(..., true)：主线程等一个做完才要下一个），世界生成线程池大多闲着，worker 实际只用约 1 个核。
修法：先把 16 个都排上（不等），再等。worker 默认 4 核（生成线程 3 个），一个 tile 要比一个一个要快至少 2 倍。

/donor export <tx> <tz> serial 走原来一个一个要的路，做对照。每种做 4 个没生成过的 tile（离别的测试远、两种隔开），比中位数。
"""

import json
import statistics

import pytest

pytestmark = pytest.mark.long


def export(worker, tx, tz, serial):
    r = json.loads(worker.rcon(f"donor export {tx} {tz}" + (" serial" if serial else "")))
    print("serial" if serial else "parallel", tx, tz, {k: r.get(k) for k in ("ms", "genMs", "serializeMs")})
    return r["ms"]


def test_parallel_tile_is_faster(worker):
    # 两种交替做，排除"后面的更快（缓存热了）"；tile 隔开 3 个（外圈加依赖不重叠）
    serial, parallel = [], []
    for i in range(4):
        serial.append(export(worker, 4000 + 6 * i, -4000, serial=True))
        parallel.append(export(worker, 4000 + 6 * i, -4060, serial=False))
    s, p = statistics.median(serial), statistics.median(parallel)
    print(f"一个一个要：{serial} ms（中位 {s}）；一起排上：{parallel} ms（中位 {p}），快 {s / p:.2f} 倍")
    assert p * 2 <= s, (serial, parallel)


def test_several_tiles_at_once(worker):
    # 收到几个任务时先全部排上（加票、不等），再一个一个导出：生成线程池同时做几个 tile。
    # 对照：同样位置旁边的 4 个 tile 一个一个同步要（原来的办法）。吞吐至少 2 倍
    serial_ms = sum(export(worker, 5000 + 3 * i, -5000, serial=True) for i in range(4))
    r = json.loads(worker.rcon("donor export 5000 -5060 batch 4"))
    print(f"4 个 tile：一个一个要共 {serial_ms} ms；一起排上共 {r['ms']} ms，快 {serial_ms / r['ms']:.2f} 倍")
    assert r["ms"] * 2 <= serial_ms, (serial_ms, r)

