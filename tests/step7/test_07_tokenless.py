"""第 9 步的安全开关 mcdonate.allowTokenless（没带 token 的 worker 也放进来）：只能出现在测试的 compose 叠加文件里。
正式部署用的基础 compose.yaml 不能有；测试服务器开着时 coordinator 打一条 WARN。
正式默认关、没有 token 被拒绝，由单元测试 HandshakeTest.tokenlessIsOffByDefault 检查。
"""

import json

from mcd import REPO_ROOT, compose


def test_only_in_test_overlay():
    assert "allowTokenless" not in (REPO_ROOT / "docker/compose.yaml").read_text()
    svcs = json.loads(compose("config", "--format", "json"))["services"]
    assert "-Dmcdonate.allowTokenless=true" in svcs["minecraft"]["environment"]["JVM_OPTS"]
    for name, svc in svcs.items():
        if name != "minecraft":
            assert "allowTokenless" not in svc["environment"].get("JVM_OPTS", ""), name


def test_test_server_warns(server):
    assert json.loads(server.rcon("donor status"))["config"]["allowTokenless"] is True
    assert "mcdonate.allowTokenless 开着" in server.logs()
