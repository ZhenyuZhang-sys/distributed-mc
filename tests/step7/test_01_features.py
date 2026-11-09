"""第 7 步 7.0：开关。默认全关（就是第 6 步的 v0）；/donor feature 切换，/donor status 列出。"""

import json

FEATURES = ["deadline", "hedge", "territory", "throughput", "waitdonor"]


def status(server) -> dict:
    return json.loads(server.rcon("donor status"))


def test_all_off_by_default(server):
    assert status(server)["features"] == []


def test_toggle_and_list(server):
    try:
        for f in FEATURES:
            assert server.rcon(f"donor feature {f} on").startswith("features")
        assert status(server)["features"] == FEATURES
        server.rcon("donor feature hedge off")
        assert "hedge" not in status(server)["features"]
    finally:
        for f in FEATURES:
            server.rcon(f"donor feature {f} off")
    assert status(server)["features"] == []


def test_unknown_feature_rejected(server):
    out = server.rcon("donor feature nosuch on")
    assert "不认识的开关" in out, out
    assert status(server)["features"] == []


def test_status_has_ledger_and_hedge(server):
    s = status(server)
    assert "ledger" in s and "hedge" in s, s
