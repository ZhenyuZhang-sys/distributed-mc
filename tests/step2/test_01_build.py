"""第 2 步：Gradle 多模块构建。

构建本身由 scripts/test.sh 在起服务器之前跑（内存不够两个同时开）。
这里只检查构建产物。
"""

import xml.etree.ElementTree as ET

import pytest

from mcd import REPO_ROOT

MODULES = ["common", "coordinator", "worker", "client", "bench"]


@pytest.mark.parametrize("module", MODULES)
def test_module_jar_built(module):
    jars = [j for j in (REPO_ROOT / module / "build" / "libs").glob("*.jar")
            if not j.name.endswith("-sources.jar")]
    assert jars, f"{module} 没有构建出 jar"


@pytest.mark.parametrize("module", ["coordinator", "bench"])
def test_collect_mods(module):
    found = list((REPO_ROOT / "build" / "mods").glob(f"mcdonate-{module}-*.jar"))
    assert len(found) == 1, f"build/mods 里应该正好有一个 {module} 的 jar：{found}"


def test_worker_and_client_not_collected():
    # 服务器上只装 coordinator 和 bench；worker 跑在别处，client 只给客户端
    names = [j.name for j in (REPO_ROOT / "build" / "mods").glob("*.jar")]
    assert not [n for n in names if "worker" in n or "client" in n], names


def test_unit_tests_ran():
    results = list((REPO_ROOT / "common" / "build" / "test-results" / "test").glob("*.xml"))
    assert results, "common 没有 JUnit 结果"
    total = failures = 0
    for r in results:
        suite = ET.parse(r).getroot()
        total += int(suite.get("tests", 0))
        failures += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
    assert total > 0 and failures == 0, f"JUnit：{total} 个，失败 {failures} 个"
