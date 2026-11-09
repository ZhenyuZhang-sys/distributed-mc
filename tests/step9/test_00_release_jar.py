"""第 9 步 9.0：测的是正式 jar。正式的客户端 mod jar 里没有测试钩子（测试小 mod 的类、mcdonate:consent_driver 的实现）。
不起服务器，只看构建产物（test.sh 先构建）。
"""

import json
import zipfile

from step9.headless_client import CLIENT_JAR


def test_release_client_jar_has_no_test_hooks():
    assert CLIENT_JAR.exists(), CLIENT_JAR
    with zipfile.ZipFile(CLIENT_JAR) as z:
        names = z.namelist()
        meta = json.loads(z.read("fabric.mod.json"))
    assert not [n for n in names if "testhooks" in n.lower()], "正式 jar 里有测试小 mod 的类"
    assert "mcdonate:consent_driver" not in meta.get("entrypoints", {}), "正式 jar 自己提供了自动同意的入口"
    assert "test" not in meta["id"]
