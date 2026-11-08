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
  [[ -d "$REPO_ROOT/tests/step$i" ]] && dirs+=("tests/step$i")
done
[[ ${#dirs[@]} -gt 0 ]] || die "没有找到 tests/step0 到 tests/step$n"

prepare_heavy

cleanup() {
  if [[ "${MCD_KEEP_SERVER:-}" == 1 ]]; then
    info "MCD_KEEP_SERVER=1，测试服务器保持运行"
  else
    info "关闭测试服务器"
    tc down >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

cd "$REPO_ROOT"

# 第 2 步起有 mod 要构建。先构建（含 JUnit），再起服务器：构建用满整机，会干扰测试。
if (( n >= 2 )); then
  info "构建 mod 并跑单元测试"
  "$REPO_ROOT/scripts/gradle.sh" build collectMods collectWorkerMods
  # 服务器内测试（GameTest）：一台专门的服务器，跑完自己退出
  "$REPO_ROOT/scripts/gametest.sh"
fi

info "运行：${dirs[*]}"
python3 -m pytest "${dirs[@]}" ${marker:+-m "$marker"} "${pytest_args[@]}"
