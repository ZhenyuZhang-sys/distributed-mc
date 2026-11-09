#!/usr/bin/env bash
# 给测试服务器和 worker 都换一个全新世界（同名、同 seed，存档目录分开），并等到 healthy。
# 用法：scripts/new_world.sh <名字> [seed，默认 424242]
# 上一个测试世界会被删掉。只在 docker/test-data/ 和 docker/test-data-worker/ 里操作。
source "$(dirname "$0")/lib.sh"

name="${1:-}"
seed="${2:-424242}"
validate_world_name "$name"
[[ "$seed" =~ ^-?[0-9]+$ ]] || die "seed 必须是整数：$seed"

prepare_heavy
old_level="$(current_level)"
[[ -z "$old_level" ]] || validate_world_name "$old_level"

info "停止测试服务器"
tc stop
archive_test_logs

# 删掉旧的测试世界和同名世界：测试服务器和 worker 各一份，路径都在 test-data* 下面。
for old in "$old_level" "$name"; do
  for dir in "$TEST_DATA" "$WORKER_DATA" "$WORKER2_DATA" "$WORKER3_DATA"; do
    if [[ -n "$old" && -d "$dir/$old" ]]; then
      info "删除旧世界 $dir/$old"
      delete_test_world "$old" "$dir"
    fi
  done
done
# 交换目录里上一轮导出的文件也清掉
find "${EXCHANGE:?}" -mindepth 1 -delete

printf 'MC_LEVEL=%s\nMC_SEED=%s\n' "$name" "$seed" >"$WORLD_ENV"

info "启动测试服务器：世界 $name，seed $seed（第一次要下载 mod，可能要几分钟）"
tc up -d --wait --wait-timeout 600
info "就绪"
