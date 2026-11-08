"""调度 11-08 要查：原版同一维度里 tp 不更新 ChunkMap 记录的玩家位置（ServerChunkCache.move 才更新）。
第 6 步的测试用 /tp 移动 Carpet 假玩家。查 Carpet 1.4.147 的 EntityPlayerMPFake.tick：每 10 tick 调一次
connection.resetPosition() 和 ServerChunkCache.move(this)，所以最多晚 0.5 秒跟上。这里实测确认：
tp 以后 ChunkMap 里的位置（/bench view 的 tracked）跟着变，新位置周围的 chunk 被加载。
"""

import json
import time

from mcd import wait_until


def view(server, name):
    return next(p for p in json.loads(server.rcon("bench view"))["players"] if p["name"] == name)


def test_tp_updates_tracked_position(server):
    a, b = (40000, 40000), (40000 + 16 * 64, 40000)   # 相隔 64 个 chunk
    server.rcon(f"player tpbot spawn at {a[0]} 200 {a[1]} facing 0 0 in minecraft:overworld in spectator")
    try:
        wait_until(lambda: view(server, "tpbot")["tracked"] == [a[0] // 16, a[1] // 16], timeout=30, interval=0.5,
                   what="出生后 ChunkMap 记录的位置")
        wait_until(lambda: (v := view(server, "tpbot"))["ready"] / v["total"] > 0.9, timeout=120, interval=1,
                   what="出生点周围加载好")
        server.rcon(f"tp tpbot {b[0]} 200 {b[1]}")
        t0 = time.monotonic()
        wait_until(lambda: view(server, "tpbot")["tracked"] == [b[0] // 16, b[1] // 16], timeout=5, interval=0.1,
                   what="tp 以后 ChunkMap 记录的位置跟上")
        lag = time.monotonic() - t0
        print(f"tp 后 {lag:.2f} 秒 ChunkMap 跟上")
        assert lag < 1.5, "Carpet 每 10 tick 调一次 move，应该 0.5 秒左右跟上"
        wait_until(lambda: (v := view(server, "tpbot"))["ready"] / v["total"] > 0.9, timeout=120, interval=1,
                   what="新位置周围加载好（服务器知道玩家到了这里）")
    finally:
        server.rcon("player tpbot kill")
