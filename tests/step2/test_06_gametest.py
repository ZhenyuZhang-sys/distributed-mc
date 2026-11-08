"""第 2 步（2.6）：GameTest 能跑，结果能被读出来。

GameTest 由 scripts/test.sh 在起正常测试服务器之前跑（scripts/gametest.sh），
报告写在 docker/test-data/gametest/report.xml。这里只检查报告。
"""

import xml.etree.ElementTree as ET

from mcd import TEST_DATA

REPORT = TEST_DATA / "gametest" / "report.xml"


def test_report_exists():
    assert REPORT.exists(), f"没有 GameTest 报告：{REPORT}"


def test_bench_gametests_passed():
    root = ET.parse(REPORT).getroot()
    cases = root.iter("testcase")
    names = {c.get("name", "").lower(): c for c in cases}
    # 一个空测试，一个真测试（放方块再断言它在）
    for want in ("emptytest", "placeblock"):
        hit = [n for n in names if want in n]
        assert hit, f"报告里没有 {want}：{sorted(names)}"
    failed = [n for n, c in names.items() if c.find("failure") is not None or c.find("error") is not None]
    assert not failed, f"GameTest 失败：{failed}"
