#!/usr/bin/env bash
# 第 7 步快速对比（docs/step7-plan.md 0.5）：依次用几组 JVM 配置各跑一次 tests/step7/test_90_ablation.py。
# 每组一次 test.sh --slow（拿独占锁，换全新世界）。开跑前在调度 log.md 写开始时间和预计时长。
# 用法：scripts/ablation.sh [组名...]   默认 default tile2 tile8 look16 look24
#   default  v0×2、每个开关各 1 轮、7.3 慢 donor（worker3）前后各 1 轮、全开×2（11 轮）
#   tile2/tile8    tileSize=2/8，只跑 v0 1 轮
#   look16/look24  demandLookahead=16/24，只跑 v0 1 轮
# 每组的结果在那次 test.sh 的 artifacts 目录里：ablation-<组名>.json。
set -uo pipefail
cd "$(dirname "$0")/.."

groups=("$@")
[[ ${#groups[@]} -gt 0 ]] || groups=(default tile2 tile8 look16 look24)

status=0
for g in "${groups[@]}"; do
  case "$g" in
    default) extra=""; rounds="" ;;
    tile2) extra="-Dmcdonate.tileSize=2"; rounds="v0" ;;
    tile8) extra="-Dmcdonate.tileSize=8"; rounds="v0" ;;
    look16) extra="-Dmcdonate.demandLookahead=16"; rounds="v0" ;;
    look24) extra="-Dmcdonate.demandLookahead=24"; rounds="v0" ;;
    *) echo "不认识的组：$g" >&2; exit 2 ;;
  esac
  echo "==> $(date +%H:%M) 组 $g（MCD_JVM_EXTRA='$extra'）"
  env MCD_JVM_EXTRA="$extra" MCD_ABLATION_LABEL="$g" ${rounds:+MCD_ABLATION="$rounds"} \
    scripts/test.sh step7 --slow -k ablation || { echo "组 $g 失败" >&2; status=1; }
done
exit $status
