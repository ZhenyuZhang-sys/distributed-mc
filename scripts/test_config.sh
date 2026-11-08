#!/usr/bin/env bash
# 固定测试服务器所用 CPU 的频率，让 benchmark 数字稳定。
# 用法：scripts/test_config.sh pin | unpin | status | check
#   pin     关睿频，把测试用的核和它们的超线程兄弟固定在基础频率（要 sudo）
#   unpin   恢复 pin 之前的设置（要 sudo）
#   status  显示当前设置
#   check   已固定就退出 0，否则退出 1（test.sh 和 benchmark 用）
#
# 用哪些核看 docker/n100.env 的 MC_CPUSET（环境变量可覆盖）。
# 注意：睿频开关是整机共用的，pin 期间整台机器都不睿频。用完记得 unpin。
source "$(dirname "$0")/lib.sh"

CPUFREQ=/sys/devices/system/cpu
BOOST="$CPUFREQ/cpufreq/boost"
# pin 之前的设置存在这里，几个 worktree 共用。
SAVED="${MCD_CPUFREQ_SAVED:-$HOME/.cache/mc-donate-cpufreq.saved}"

# 测试用的核，加上它们的超线程兄弟（兄弟核不分给容器，但频率一起固定）。
pinned_cpus() {
  local c
  for c in $(expand_cpus "$(test_cpuset)"); do
    expand_cpus "$(cat "$CPUFREQ/cpu$c/topology/thread_siblings_list")"
  done | sort -n | uniq
}

# 写 sysfs 要 root。
put() { echo "$2" | sudo tee "$1" >/dev/null; }

do_pin() {
  [[ -f "$BOOST" ]] || die "找不到 $BOOST，这台机器的频率驱动不支持关睿频"
  if [[ ! -f "$SAVED" ]]; then
    mkdir -p "$(dirname "$SAVED")"
    {
      echo "boost $(cat "$BOOST")"
      for c in $(pinned_cpus); do
        local d="$CPUFREQ/cpu$c/cpufreq"
        echo "cpu $c $(cat "$d/scaling_governor") $(cat "$d/scaling_min_freq") $(cat "$d/scaling_max_freq")"
      done
    } >"$SAVED"
    info "原来的设置已存到 $SAVED"
  else
    info "$SAVED 已存在，保留最早的原设置"
  fi
  put "$BOOST" 0
  for c in $(pinned_cpus); do
    local d="$CPUFREQ/cpu$c/cpufreq" f
    f="$(cat "$d/cpuinfo_max_freq")"
    put "$d/scaling_governor" performance
    # 先抬上限再抬下限，避免下限暂时高于上限被拒绝
    put "$d/scaling_max_freq" "$f"
    put "$d/scaling_min_freq" "$f"
  done
  info "已固定：CPU $(pinned_cpus | paste -sd,)，$(( $(cat "$CPUFREQ/cpu$(pinned_cpus | head -1)/cpufreq/cpuinfo_max_freq") / 1000 )) MHz，睿频关"
}

do_unpin() {
  [[ -f "$SAVED" ]] || die "没有 $SAVED，不知道原来的设置。用 status 看现状，必要时手动改。"
  local kind a b c d
  while read -r kind a b c d; do
    case "$kind" in
      boost) put "$BOOST" "$a" ;;
      cpu)
        local dir="$CPUFREQ/cpu$a/cpufreq"
        put "$dir/scaling_min_freq" "$c"
        put "$dir/scaling_max_freq" "$d"
        put "$dir/scaling_governor" "$b"
        ;;
    esac
  done <"$SAVED"
  rm -f "$SAVED"
  info "已恢复 pin 之前的设置"
}

do_status() {
  echo "睿频：$(cat "$BOOST")（0 = 关）"
  echo "测试用的核：$(test_cpuset)；一起固定频率的：$(pinned_cpus | paste -sd,)"
  local c
  for c in $(pinned_cpus); do
    local d="$CPUFREQ/cpu$c/cpufreq"
    echo "cpu$c  $(cat "$d/scaling_governor")  min=$(cat "$d/scaling_min_freq")  max=$(cat "$d/scaling_max_freq")  硬件上限=$(cat "$d/cpuinfo_max_freq")"
  done
}

# 固定了 = 睿频关，每个核都是 performance，且 min = max = 硬件基础频率。
do_check() {
  local bad=() c
  [[ "$(cat "$BOOST")" == 0 ]] || bad+=("睿频没关")
  for c in $(pinned_cpus); do
    local d="$CPUFREQ/cpu$c/cpufreq" f
    f="$(cat "$d/cpuinfo_max_freq")"
    [[ "$(cat "$d/scaling_governor")" == performance &&
       "$(cat "$d/scaling_min_freq")" == "$f" &&
       "$(cat "$d/scaling_max_freq")" == "$f" ]] || bad+=("cpu$c 没固定")
  done
  if (( ${#bad[@]} )); then
    echo "CPU 频率没固定：${bad[*]}。先跑 scripts/test_config.sh pin" >&2
    return 1
  fi
}

case "${1:-}" in
  pin) do_pin ;;
  unpin) do_unpin ;;
  status) do_status ;;
  check) do_check ;;
  *) die "用法：scripts/test_config.sh pin | unpin | status | check" ;;
esac
