#!/usr/bin/env bash
# 跑第 0 步到第 N 步的所有测试。退出码 0 = 全部通过。
# 用法：scripts/test.sh stepN [--slow|--quick] [--allow-skip] [其他 pytest 参数]
#   --slow        连性能测试一起跑（默认不跑）
#   --quick       快速回归（约 10 分钟）：不跑标了 long 的端到端测试，不起第二个 donor。
#                 改代码时自查用，验收不能用
#   --allow-skip  有测试被跳过也算通过（默认算失败）
# 环境变量 MCD_KEEP_SERVER=1：跑完不关测试服务器，方便排查。
source "$(dirname "$0")/lib.sh"

step="${1:-}"
[[ "$step" =~ ^step([0-9]+)$ ]] || die "用法：scripts/test.sh stepN [--slow] [--allow-skip]"
n="${BASH_REMATCH[1]}"
shift

marker="not slow"
quick=0
pytest_args=()
for a in "$@"; do
  case "$a" in
    --slow) marker="" ;;
    --quick) quick=1; marker="not slow and not long" ;;
    *) pytest_args+=("$a") ;;
  esac
done

dirs=()
for i in $(seq 0 "$n"); do
  [[ -d "$REPO_ROOT/tests/step$i" ]] || die "缺少 tests/step$i，不能验收 step$n"
  dirs+=("tests/step$i")
done
[[ ${#dirs[@]} -gt 0 ]] || die "没有找到 tests/step0 到 tests/step$n"

# 性能测试（--slow）和 benchmark 一样要独占：测量期间不能有别的重活
if [[ -z "$marker" ]]; then
  take_heavy_lock
fi
prepare_heavy
export MCD_ARTIFACT_DIR="${MCD_ARTIFACT_DIR:-$REPO_ROOT/artifacts/step$n$( ((quick)) && echo -quick)-$(date -u +%Y%m%dT%H%M%S)-$$}"
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
      # 测试服务器和两个 worker 各有一份同名世界
      if [[ -n "$level" ]]; then
        (delete_test_world "$level" "$TEST_DATA" && delete_test_world "$level" "$WORKER_DATA" \
          && delete_test_world "$level" "$WORKER2_DATA" && delete_test_world "$level" "$WORKER3_DATA") || status=1
      fi
    fi
  fi
  printf '%s\n' "$status" >"$MCD_ARTIFACT_DIR/exit-code.txt"
  info "测试证据：$MCD_ARTIFACT_DIR"
  exit "$status"
}
trap cleanup EXIT

cd "$REPO_ROOT"

# 第 1 步起需要现有测量 mod。先构建（含 JUnit），再起服务器：构建用满整机，会干扰测试。
# 第 3 步起要 worker 服务器，第 6 步起再加第二个（按需启动的 compose profile；--quick 不起第二个）
if (( n >= 6 && quick == 0 )); then
  export COMPOSE_PROFILES=worker,worker2
elif (( n >= 3 )); then
  export COMPOSE_PROFILES=worker
fi

if (( n >= 1 )); then
  info "构建 mod 并跑单元测试"
  "$REPO_ROOT/scripts/gradle.sh" build collectMods collectWorkerMods
  "$MCD_PYTHON" -m bench.cli stamp
  # 服务器内测试（GameTest）从第 2 步开始：一台专门的服务器，跑完自己退出
  if (( n >= 2 )); then "$REPO_ROOT/scripts/gametest.sh"; fi
fi

info "运行：${dirs[*]}"
"$MCD_PYTHON" -m pytest "${dirs[@]}" ${marker:+-m "$marker"} --junitxml="$MCD_ARTIFACT_DIR/junit.xml" "${pytest_args[@]}"
