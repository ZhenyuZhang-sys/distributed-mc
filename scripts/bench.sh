#!/usr/bin/env bash
# 基线实验入口，整轮持有独占锁（测试和 Gradle 拿共享锁，benchmark 跑时它们排队）。
source "$(dirname "$0")/lib.sh"
take_heavy_lock
require_play_server_stopped
[[ -f "$TEST_ENV" ]] || die "缺少 $TEST_ENV；请先配置测试服并自行同意 EULA"
ensure_test_env
assert_test_data_mount
assert_test_instance
cleanup() {
  local status=$?
  trap - EXIT
  if ! (assert_test_instance); then status=1
  elif ! tc down; then [[ "$status" != 0 ]] || status=1
  fi
  exit "$status"
}
if [[ "${1:-}" != preflight ]]; then trap cleanup EXIT; fi
cd "$REPO_ROOT"
python="${MCD_PYTHON:-$REPO_ROOT/.venv/bin/python}"
if [[ "${1:-}" == prepare ]]; then
  "$REPO_ROOT/scripts/gradle.sh" build collectMods
  "$python" -m bench.cli stamp
else
  "$python" -m bench.cli "$@"
fi
