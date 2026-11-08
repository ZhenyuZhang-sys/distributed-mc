#!/usr/bin/env bash
# 给 worker 的出站流量加网络延迟和丢包（第 5 步测试用）。
# 用法：scripts/netem.sh on [延迟，默认 100ms] [丢包，默认 1%] | off | show
#
# 不给 worker 容器加 NET_ADMIN：临时起一个带 NET_ADMIN 的小容器，和 worker 共用网络，设完就退出。
# 只影响 worker 发往 coordinator 的方向。测试里一定要在 finally 里 off。
source "$(dirname "$0")/lib.sh"

IMAGE="nicolaka/netshoot:v0.13"

cid="$(tc ps -q worker)"
[[ -n "$cid" ]] || die "worker 没在跑（要 COMPOSE_PROFILES=worker）"

nettool() {
  docker run --rm --network "container:$cid" --cap-add NET_ADMIN "$IMAGE" "$@"
}

case "${1:-}" in
  on)
    delay="${2:-100ms}"
    loss="${3:-1%}"
    nettool tc qdisc replace dev eth0 root netem delay "$delay" loss "$loss"
    info "worker 出站：延迟 $delay，丢包 $loss"
    ;;
  off)
    # 没设过也不报错
    nettool tc qdisc del dev eth0 root 2>/dev/null || true
    info "worker 出站网络恢复正常"
    ;;
  show)
    nettool tc qdisc show dev eth0
    ;;
  *)
    die "用法：scripts/netem.sh on [延迟] [丢包] | off | show"
    ;;
esac
