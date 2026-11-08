"""调度 11-08 要查（结论：不受影响）：原版同一维度里 tp 不更新 ChunkMap 记录的玩家位置（ServerChunkCache.move 才更新）。
第 6 步的测试用 /tp 移动 Carpet 假玩家。查 Carpet 1.4.147 的 EntityPlayerMPFake.tick：每 10 tick 调一次
connection.resetPosition() 和 ServerChunkCache.move(this)，所以最多晚 0.5 秒跟上。这里实测确认：
tp 以后 ChunkMap 里的位置（/bench view 的 tracked）跟着变，新位置周围的 chunk 被加载。
按 tick 判（不按秒）：tp 到没生成过的地方，紧接着那个 tick 要同步加载目的地，墙钟上会慢好几秒（实测一次 2.7 秒，
平均 MSPT 从 4 跳到 38），但按 tick 是 5 个。
"""

import json
import time

from mcd import wait_until


def view(server, name):
    # Carpet 会把假玩家名字改成首字母大写（tpbot → TpBot）
    return next(p for p in json.loads(server.rcon("bench view"))["players"] if p["name"].lower() == name.lower())


def test_tp_updates_tracked_position(server):
    a, b = (40000, 40000), (40000 + 16 * 64, 40000)   # 相隔 64 个 chunk
    server.rcon(f"player tpbot spawn at {a[0]} 200 {a[1]} facing 0 0 in minecraft:overworld in spectator")
    try:
        wait_until(lambda: view(server, "tpbot")["tracked"] == [a[0] // 16, a[1] // 16], timeout=30, interval=0.5,
                   what="出生后 ChunkMap 记录的位置")
        wait_until(lambda: (v := view(server, "tpbot"))["ready"] / v["total"] > 0.9, timeout=120, interval=1,
                   what="出生点周围加载好")
        tick0 = json.loads(server.rcon("bench view"))["tick"]
        server.rcon(f"tp tpbot {b[0]} 200 {b[1]}")
        t0 = time.monotonic()
        trace = []

        def caught_up():
            v = json.loads(server.rcon("bench view"))
            p = next(p for p in v["players"] if p["name"].lower() == "tpbot")
            trace.append((round(time.monotonic() - t0, 2), v["tick"] - tick0, p["chunk"], p["tracked"], round(v["mspt"], 1)))
            return p["tracked"] == [b[0] // 16, b[1] // 16]

        wait_until(caught_up, timeout=10, interval=0.1, what="tp 以后 ChunkMap 记录的位置跟上")
        print("tp 以后 [秒, tick, 实体所在 chunk, ChunkMap 记录, mspt]：", trace)
        ticks = trace[-1][1]
        # Carpet 每 10 tick 调一次 move：按 tick 算最多 10 个（加上命令本身的 1～2 个）
        assert ticks <= 12, trace
        wait_until(lambda: (v := view(server, "tpbot"))["ready"] / v["total"] > 0.9, timeout=120, interval=1,
                   what="新位置周围加载好（服务器知道玩家到了这里）")
    finally:
        server.rcon("player tpbot kill")
