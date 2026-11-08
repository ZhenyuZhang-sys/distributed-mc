#!/usr/bin/env bash
# 在独立参考世界中生成 step0 的已知样本和出生区 profile。
source "$(dirname "$0")/lib.sh"
prepare_heavy
cd "$REPO_ROOT"
export MCD_ARTIFACT_DIR="$REPO_ROOT/artifacts/calibration-$(date -u +%Y%m%dT%H%M%S)-$$"
mkdir -p "$MCD_ARTIFACT_DIR"
python="${MCD_PYTHON:-$REPO_ROOT/.venv/bin/python}"
cleanup() {
  local status=$?
  trap - EXIT
  if ! tc down; then status=1; fi
  if [[ "$status" == 0 ]]; then
    level="$(current_level)"
    [[ -z "$level" ]] || delete_test_world "$level"
  fi
  exit "$status"
}
trap cleanup EXIT
PYTHONPATH="$REPO_ROOT:$REPO_ROOT/tests" "$python" "$REPO_ROOT/tests/calibrate_step0.py"
