#!/usr/bin/env bash
# 给测试服务器发控制台命令（不带 /）。
# 例子：scripts/rcon.sh seed
#       scripts/rcon.sh player bot1 spawn
source "$(dirname "$0")/lib.sh"

[[ $# -ge 1 ]] || die "用法：scripts/rcon.sh <命令>"
[[ -f "$TEST_ENV" ]] || die "还没有 $TEST_ENV，先跑 scripts/new_world.sh"
tc exec -T minecraft rcon-cli "$@"
