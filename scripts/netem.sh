#!/usr/bin/env bash
# 给 worker 的出站流量加网络延迟和丢包（第 5 步测试用；第 7 步对比的"有网络"条件也用）。
# 用法：scripts/netem.sh on [延迟，默认 100ms] [丢包，默认 1%] [限速，比如 20mbit，默认不限] | off | show
# 环境变量 NETEM_SERVICE：给哪个 compose 服务加（默认 worker；可以是 worker2、worker3、minecraft）
#
# 不给 worker 容器加 NET_ADMIN：临时起一个带 NET_ADMIN 的小容器，和 worker 共用网络，设完就退出。
# 只影响 worker 发往 coordinator 的方向。测试里一定要在 finally 里 off。
source "$(dirname "$0")/lib.sh"

IMAGE="nicolaka/netshoot:v0.13"

svc="${NETEM_SERVICE:-worker}"
[[ "$svc" =~ ^(worker|worker2|worker3|minecraft)$ ]] || die "NETEM_SERVICE 只能是 worker、worker2、worker3、minecraft"
cid="$(tc ps -q "$svc")"
[[ -n "$cid" ]] || die "$svc 没在跑"

nettool() {
  docker run --rm --network "container:$cid" --cap-add NET_ADMIN "$IMAGE" "$@"
}

case "${1:-}" in
  on)
    delay="${2:-100ms}"
    loss="${3:-1%}"
    rate="${4:-}"
    nettool tc qdisc replace dev eth0 root netem delay "$delay" loss "$loss" ${rate:+rate "$rate"}
    info "$svc 出站：延迟 $delay，丢包 $loss${rate:+，限速 $rate}"
    ;;
  off)
    # 没设过也不报错
    nettool tc qdisc del dev eth0 root 2>/dev/null || true
    info "$svc 出站网络恢复正常"
    ;;
  show)
    nettool tc qdisc show dev eth0
    ;;
  *)
    die "用法：scripts/netem.sh on [延迟] [丢包] | off | show"
    ;;
esac
