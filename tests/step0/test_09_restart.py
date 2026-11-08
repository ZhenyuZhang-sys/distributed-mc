"""第 0 步：停服再启动，世界还在，日志里没有 ERROR。

文件名用 09，保证在第 0 步最后跑。
"""


def test_restart_keeps_world(server):
    server.rcon("save-all flush")
    level_dat = server.world_dir / "level.dat"
    assert level_dat.exists()

    server.restart()

    assert server.health() == "healthy"
    assert level_dat.exists()
    assert server.rcon("seed") == f"Seed: [{server.seed}]"
    assert server.error_lines() == []
