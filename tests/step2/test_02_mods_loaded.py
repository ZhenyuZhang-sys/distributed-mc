"""第 2 步：测试服务器装上我们自己构建的 mod（build/mods/）。"""

import re

import pytest


@pytest.mark.parametrize("module", ["coordinator", "bench"])
def test_our_mod_loaded(server, module):
    # 入口类启动时打一行 "mcdonate-<模块> loaded"
    assert f"mcdonate-{module} loaded" in server.logs(), f"日志里没有 mcdonate-{module} loaded"


def test_worker_and_client_not_on_server(server):
    # Fabric 启动时列出所有 mod；worker、client 不该出现在服务器上
    listed = re.findall(r"^\s*- (mcdonate-\S+)", server.logs(), re.M)
    assert "mcdonate-coordinator" in listed and "mcdonate-bench" in listed, listed
    assert not [m for m in listed if m in ("mcdonate-worker", "mcdonate-client")], listed
