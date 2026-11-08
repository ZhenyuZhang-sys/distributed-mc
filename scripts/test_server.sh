#!/usr/bin/env bash
# 手动管理测试服务器。
# 用法：scripts/test_server.sh up | down | status | logs [行数]
source "$(dirname "$0")/lib.sh"

case "${1:-}" in
  up)
    prepare_heavy
    tc up -d --wait --wait-timeout 600
    ;;
  down)
    assert_test_instance
    tc down
    ;;
  status)
    tc ps
    ;;
  logs)
    tc logs --no-log-prefix --tail="${2:-100}" minecraft
    ;;
  *)
    die "用法：scripts/test_server.sh up | down | status | logs [行数]"
    ;;
esac
