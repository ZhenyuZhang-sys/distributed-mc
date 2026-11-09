#!/usr/bin/env bash
# 演示面板（docs/demo.md）：地图 + 实时曲线 + 控制，只绑 127.0.0.1。
# 用法：scripts/demo.sh [端口，默认 8765]
# 先用 scripts/test_server.sh up 起好本槽位的测试服务器。面板只用 rcon.sh、lib.sh 的 tc 和 scripts/ 下的脚本控制它。
source "$(dirname "$0")/lib.sh"

port="${1:-8765}"
[[ "$port" =~ ^[0-9]+$ ]] || die "端口要是数字：$port"
tc ps -q minecraft | grep -q . || die "测试服务器没在跑：先 scripts/test_server.sh up"
cd "$REPO_ROOT"
exec .venv/bin/python -m bench.map.server "$TEST_DATA/bench-runs" "$port" --demo
