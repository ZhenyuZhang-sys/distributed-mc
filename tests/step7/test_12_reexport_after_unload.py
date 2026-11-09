"""11-09 kexu k2（main f19d087 完整 step10）：worker2 被杀后重启，同一个 tile 又派给它，马上失败：
"chunk (-477, 511) 不在内存里"。(-477, 511) 是 tile (-119, 128) 外圈的角。

原因：上一次已经把这个 tile 做完存了盘。再要时 16 个 chunk 直接从盘里读（已经到 initialize_light），
原版不会去碰邻居，外圈的 chunk 有 holder（票传过去的）但从没加载。635a362 把导出循环改成纯查找以后就拿不到。
原来的 getChunk(..., false) 会顺手把它从盘里读进来。

复现：导出一个 tile，等它卸载（票去掉后几个 tick 就存盘卸载，没有能直接看的信号，就等几秒），再导出同一个。
不用重启 worker：chunk 被卸载、同一个 tile 再派回来，在正式服上也会遇到（撤了又派、失败重派）。
"""

import json
import time


def test_same_tile_again_after_unload(worker):
    tx, tz = 8000, -8000
    first = worker.rcon(f"donor export {tx} {tz}")
    assert first.startswith("{"), first
    worker.rcon("save-all flush")
    # 票去掉以后几个 tick 内存盘、卸载；没有命令能看 chunk 在不在内存里，固定等几秒
    time.sleep(5)
    again = worker.rcon(f"donor export {tx} {tz}")
    assert again.startswith("{"), f"同一个 tile 卸载后再导出失败：{again}"
    r = json.loads(again)
    print({k: r.get(k) for k in ("ms", "genMs", "serializeMs")})
