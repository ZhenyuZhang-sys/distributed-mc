"""多 worker 的客户端测试可与 RCON 并发，不改进程全局环境。"""
import os
from pathlib import Path

from step9 import headless_client


def test_headless_launch_uses_private_environment(monkeypatch, tmp_path):
    monkeypatch.setenv("MCD_CLIENT_MODS", "original-mods")
    monkeypatch.setenv("MCD_CLIENT_OUT", "original-out")
    mods, out = tmp_path / "mods", tmp_path / "out"

    def compose(*args, timeout, env):
        assert os.environ["MCD_CLIENT_MODS"] == "original-mods"
        assert os.environ["MCD_CLIENT_OUT"] == "original-out"
        assert env["MCD_CLIENT_MODS"] == str(mods)
        assert env["MCD_CLIENT_OUT"] == str(out)
        (out / "done").write_text("done")
        return ""

    monkeypatch.setattr(headless_client, "compose", compose)
    headless_client.run_client("fixture", 1, mods, out)
    assert os.environ["MCD_CLIENT_MODS"] == "original-mods"
