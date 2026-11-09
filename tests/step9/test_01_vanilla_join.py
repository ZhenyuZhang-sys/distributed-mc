"""第 9 步 9.1：没装 mod 的原版客户端照常进服（docs/step9-plan.md 5 节 9.1）。

用协议层的无头客户端 mineflayer（版本写死在 docker/mineflayer/package-lock.json，4.23.0 支持 1.21.1）连测试服务器：
能进、能收到区块，服务器日志有"joined the game"，没有被踢。服务器配了 publicHost（会给装了 mod 的玩家发邀请），
原版客户端没注册 mcdonate:invite 频道，不发给它、也不影响它。日志里的错误由每轮的日志检查管。
"""

import json

from mcd import compose


def join(name: str, stay: int = 10) -> dict:
    out = compose("run", "--rm", "mineflayer", "minecraft", "25565", name, str(stay), timeout=600)
    return json.loads(out.strip().splitlines()[-1])


def test_vanilla_client_joins(server):
    status = json.loads(server.rcon("donor status"))
    assert status["config"]["publicHost"] == "minecraft", "要在配了邀请的情况下测"
    r = join("vanilla1")
    print(r)
    assert r["spawned"] and r["chunks"] > 0 and r["kicked"] is None and r["error"] is None, r
    log = server.logs()
    assert "vanilla1 joined the game" in log
    assert "给 vanilla1 发了 donor 邀请" not in log, "原版客户端不该收到邀请"
