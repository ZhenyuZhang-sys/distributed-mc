#!/usr/bin/env bash
# 给测试服务器发控制台命令（不带 /）。加 --worker 发给 worker。
# 例子：scripts/rcon.sh seed
#       scripts/rcon.sh player bot1 spawn
source "$(dirname "$0")/lib.sh"

service=minecraft
case "${1:-}" in
  --worker) service=worker; shift ;;
  --worker2) service=worker2; shift ;;
esac
[[ $# -ge 1 ]] || die "用法：scripts/rcon.sh [--worker] <命令>"
[[ -f "$TEST_ENV" ]] || die "还没有 $TEST_ENV，先跑 scripts/new_world.sh"
# -- 之后都当命令内容，否则负坐标（-5000）会被当成选项
tc exec -T "$service" rcon-cli -- "$@"
