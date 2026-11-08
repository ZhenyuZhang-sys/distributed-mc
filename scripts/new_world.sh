#!/usr/bin/env bash
# 给测试服务器换一个全新世界，并等到 healthy。
# 用法：scripts/new_world.sh <名字> [seed，默认 424242]
# 上一个测试世界会被删掉。只在 docker/test-data/ 里操作。
source "$(dirname "$0")/lib.sh"

name="${1:-}"
seed="${2:-424242}"
[[ "$name" =~ ^[a-z0-9_-]{1,32}$ ]] || die "用法：scripts/new_world.sh <名字> [seed]；名字只能用小写字母、数字、_ 和 -"
[[ "$name" != "world" ]] || die "不能叫 world，那是平时玩的存档。"
[[ "$seed" =~ ^-?[0-9]+$ ]] || die "seed 必须是整数：$seed"

prepare_heavy

info "停止测试服务器"
tc stop >/dev/null 2>&1 || true

# 删掉旧的测试世界和同名世界。路径都在 test-data 下面。
for old in "$(current_level)" "$name"; do
  if [[ -n "$old" && -d "$TEST_DATA/$old" ]]; then
    info "删除旧世界 $old"
    rm -rf -- "${TEST_DATA:?}/$old"
  fi
done

printf 'MC_LEVEL=%s\nMC_SEED=%s\n' "$name" "$seed" >"$WORLD_ENV"

info "启动测试服务器：世界 $name，seed $seed（第一次要下载 mod，可能要几分钟）"
tc up -d --wait --wait-timeout 600
info "就绪"
