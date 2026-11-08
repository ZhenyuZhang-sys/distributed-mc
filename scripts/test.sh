#!/usr/bin/env bash
# 跑第 0 步到第 N 步的所有测试。退出码 0 = 全部通过。
# 用法：scripts/test.sh stepN [--slow] [--allow-skip] [其他 pytest 参数]
#   --slow        连性能测试一起跑（默认不跑）
#   --allow-skip  有测试被跳过也算通过（默认算失败）
# 环境变量 MCD_KEEP_SERVER=1：跑完不关测试服务器，方便排查。
source "$(dirname "$0")/lib.sh"

step="${1:-}"
[[ "$step" =~ ^step([0-9]+)$ ]] || die "用法：scripts/test.sh stepN [--slow] [--allow-skip]"
n="${BASH_REMATCH[1]}"
shift

marker="not slow"
pytest_args=()
for a in "$@"; do
  case "$a" in
    --slow) marker="" ;;
    *) pytest_args+=("$a") ;;
  esac
done

dirs=()
for i in $(seq 0 "$n"); do
  [[ -d "$REPO_ROOT/tests/step$i" ]] || die "缺少 tests/step$i，不能验收 step$n"
  dirs+=("tests/step$i")
done
[[ ${#dirs[@]} -gt 0 ]] || die "没有找到 tests/step0 到 tests/step$n"

prepare_heavy
export MCD_ARTIFACT_DIR="${MCD_ARTIFACT_DIR:-$REPO_ROOT/artifacts/step$n-$(date -u +%Y%m%dT%H%M%S)-$$}"
mkdir -p "$MCD_ARTIFACT_DIR"
if [[ -z "${MCD_PYTHON:-}" ]]; then
  if [[ -x "$REPO_ROOT/.venv/bin/python" ]]; then MCD_PYTHON="$REPO_ROOT/.venv/bin/python"; else MCD_PYTHON=python3; fi
fi

cleanup() {
  local status=$? level
  trap - EXIT
  if [[ "${MCD_KEEP_SERVER:-}" == 1 ]]; then
    info "MCD_KEEP_SERVER=1，测试服务器保持运行"
  else
    info "关闭测试服务器"
    if ! (assert_test_instance); then
      status=1
    elif ! tc down; then
      info "测试容器关闭失败"
      [[ "$status" != 0 ]] || status=1
    elif [[ "$status" == 0 ]]; then
      level="$(current_level)"
      if [[ -n "$level" ]]; then (delete_test_world "$level") || status=1; fi
    fi
  fi
  printf '%s\n' "$status" >"$MCD_ARTIFACT_DIR/exit-code.txt"
  info "测试证据：$MCD_ARTIFACT_DIR"
  exit "$status"
}
trap cleanup EXIT

cd "$REPO_ROOT"
info "运行：${dirs[*]}"
"$MCD_PYTHON" -m pytest "${dirs[@]}" ${marker:+-m "$marker"} --junitxml="$MCD_ARTIFACT_DIR/junit.xml" "${pytest_args[@]}"
