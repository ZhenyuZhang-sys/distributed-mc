"""11-09 调度（用户真机上"开不开 donor 差别不大"，distributed-mc-75 查到）：worker 一个 tile 里的 16 个 chunk 是一个一个同步要的
（getChunk(..., true)：主线程等一个做完才要下一个），世界生成线程池大多闲着，worker 实际只用约 1 个核。
修法：先把 16 个都排上（不等），再等；收到几个任务时先全部排上。

11-09 实测（f2，worker 4 核，并行跑）：原来一个一个要时 worker 已经用约 1.4～2.5 个核（每个 chunk 的依赖本来就并行），
不是"只用 1 个核"；一起排上后一个 tile 快 1.4～1.5 倍（1.9 → 1.3～1.5 秒），8 个一起排上时用约 2.2～3.4 个核，每个约 1.8 秒，
吞吐被 CPU 卡住（一个 4×4 的 tile 连依赖要做约 5 核·秒）。原来定的"至少快 2 倍"做不到，判据按实测改成：一个 tile 快 1.3 倍以上；
几个一起排上不比一个一个慢。计时测试，标 slow（并行跑时噪声大）。

/donor export <tx> <tz> serial 走原来一个一个要的路，做对照。每种做 4 个没生成过的 tile（离别的测试远、两种隔开），比中位数。
"""

import json
import statistics

import pytest

pytestmark = [pytest.mark.long, pytest.mark.slow]


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
    assert p * 1.3 <= s, (serial, parallel)


def test_several_tiles_at_once(worker):
    # 收到几个任务时先全部排上（加票、不等），再一个一个导出：生成线程池同时做几个 tile。
    # 对照：同样位置旁边的 4 个 tile 一个一个同步要（原来的办法）。CPU 卡住时快不了多少，至少不能更慢
    serial_ms = sum(export(worker, 5000 + 3 * i, -5000, serial=True) for i in range(4))
    r = json.loads(worker.rcon("donor export 5000 -5060 batch 4"))
    print(f"4 个 tile：一个一个要共 {serial_ms} ms；一起排上共 {r['ms']} ms，快 {serial_ms / r['ms']:.2f} 倍")
    assert r["ms"] <= serial_ms, (serial_ms, r)


@pytest.mark.slow
def test_adjacent_tiles_export_safely(worker):
    """11-09 用户真机（9008deb）：任务在 worker 上失败，"Accessing PalettedContainer from multiple threads"——主线程序列化
    tile A 外圈的 chunk 时，生成线程正在写它（相邻 tile B 先排上了生成，B 的地物写进 A 的外圈）。
    几个紧挨着的 tile（外圈互相重叠）一起排上再一个一个导出，跑几遍，都要成功。"""
    # 11-09 kexu k2（3132230 转弯）又出了一次，异常在生成线程上：多跑几遍、每遍多几个（竞态，不一定每次都撞上）
    for row in range(6):
        r = worker.rcon(f"donor export 6000 {-6000 - 10 * row} batch 10 1")
        assert r.startswith("{"), f"第 {row} 遍失败：{r}"

