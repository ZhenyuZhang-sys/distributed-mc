#!/usr/bin/env bash
# 跑服务器内测试（Fabric GameTest）。退出码 0 = 全部通过。
# 用法：scripts/gametest.sh
#
# 用和测试服务器同一套配置（同样的 mod、资源限制）起一个一次性容器，
# 带上 GameTest 参数：服务器跑完所有测试就自己退出，退出码是失败的个数。
# 报告写在 docker/test-data/gametest/report.xml（JUnit XML）。
# 先跑 scripts/gradle.sh build collectMods，测试类在 bench mod 里。
source "$(dirname "$0")/lib.sh"

prepare_heavy

LEVEL="gametest-world"
REPORT_DIR="$TEST_DATA/gametest"
REPORT="$REPORT_DIR/report.xml"

# 正常的测试服务器要先停，两台不同时开
tc stop >/dev/null 2>&1 || true
rm -rf -- "${TEST_DATA:?}/$LEVEL" "$REPORT_DIR"
mkdir -p "$REPORT_DIR"

info "运行 GameTest"
set +e
MC_LEVEL="$LEVEL" timeout 900 \
  bash -c "source '$REPO_ROOT/scripts/lib.sh'; tc run --rm --no-deps -T \
    -e JVM_OPTS='-Dfabric-api.gametest -Dfabric-api.gametest.report-file=/data/gametest/report.xml' \
    minecraft" >"$REPORT_DIR/server.log" 2>&1
code=$?
set -e

[[ -f "$REPORT" ]] || die "GameTest 没有生成报告（退出码 $code）。日志：$REPORT_DIR/server.log"

# 退出码和报告都要看：报告里至少有一个测试，而且没有失败
python3 - "$REPORT" <<'PY' || die "GameTest 有失败。报告：$REPORT"
import sys, xml.etree.ElementTree as ET
cases = list(ET.parse(sys.argv[1]).getroot().iter("testcase"))
failed = [c.get("name") for c in cases if c.find("failure") is not None or c.find("error") is not None]
print(f"GameTest：{len(cases)} 个，失败 {len(failed)} 个", *failed, sep="\n  ", file=sys.stderr)
sys.exit(1 if failed or not cases else 0)
PY
(( code == 0 )) || die "GameTest 服务器退出码 $code。日志：$REPORT_DIR/server.log"
info "GameTest 全部通过"
