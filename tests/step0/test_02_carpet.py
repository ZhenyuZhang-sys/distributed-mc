"""第 0 步：Carpet 假玩家能造出来，也能删掉。"""

from mcd import wait_until


def online(server) -> str:
    # Carpet 会把名字改成首字母大写（bot1 → Bot1），所以按小写比较
    return server.rcon("list").lower()


def test_fake_player(server):
    server.rcon("player bot1 spawn")
    wait_until(lambda: "bot1" in online(server), timeout=15, what="bot1 上线")
    server.rcon("player bot1 kill")
    wait_until(lambda: "bot1" not in online(server), timeout=15, what="bot1 下线")
