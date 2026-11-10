#!/usr/bin/env bash
# mc-donate 演示服务器（开发机，只绑 Tailscale）。用法见 ./demo.sh help
set -euo pipefail
cd "$(dirname "$0")"
DC="docker compose"
rcon() { $DC exec -T minecraft rcon-cli "$@"; }
prov() { rcon donor provenance stats | python3 -c 'import sys,json;d=json.loads(sys.stdin.read().strip().splitlines()[0]);print(" ".join(f"{k}={v["count"]}" for k,v in d.items() if isinstance(v,dict)))'; }
case "${1:-help}" in
  up)     $DC up -d --wait --wait-timeout 600; rcon donor status | head -c 300; echo ;;
  down)   $DC stop ;;
  status) $DC ps; rcon list; rcon "tick query"; rcon donor status | python3 -c 'import sys,json;d=json.loads(sys.stdin.read().strip().splitlines()[0]);print("demand",d["demand"],"features",d["features"]);print("workers",d["workers"]);print("tasks",d["tasks"],"accepted",d["accepted"],"stale",d["stale"])' ;;
  reset)  # 清空演示世界，同一个 seed 重新开（只删 ./data/world*）
          $DC stop
          [ -d data ] && [ "$(realpath data)" = "$(realpath .)/data" ] || { echo "data 目录不对"; exit 1; }
          rm -rf data/world data/world_nether data/world_the_end
          $DC up -d --wait --wait-timeout 600; echo "已重开全新世界" ;;
  donor)  rcon donor demand on;  echo "donor 派活：开" ;;
  server) rcon donor demand off; echo "donor 派活：关（只有服务器自己算）" ;;
  cpu)    # ./demo.sh cpu 0.5 ：把服务器 CPU 配额改成 0.5 核（会重启）
          sed -i "s/^DEMO_CPUS=.*/DEMO_CPUS=${2:?核数}/" .env; $DC up -d --wait --wait-timeout 600; echo "服务器 CPU 配额 ${2} 核" ;;
  stats)  # ./demo.sh stats 30 ：30 秒里服务器做完、可以发给玩家的 chunk/秒（resumed+vanilla 的增长）
          n=${2:-30}; a=$(prov); sleep "$n"; b=$(prov)
          python3 - "$a" "$b" "$n" <<'PY'
import sys
def p(s): return {k:int(v) for k,v in (x.split("=") for x in s.split())}
a,b,n=p(sys.argv[1]),p(sys.argv[2]),int(sys.argv[3]); g=lambda k:b.get(k,0)-a.get(k,0)
print(f"{n} s: donor imported {g('donor')} chunks, server finished {g('resumed')} donor chunks, server generated {g('vanilla')} itself")
print(f"Delivered to player: {(g('resumed')+g('vanilla'))/n:.1f} chunks/s ({g('resumed')/max(1,g('resumed')+g('vanilla')):.0%} from donor)")
PY
          rcon "tick query" ;;
  fly)    shift; ./fly.py "$@" ;;
  bot)    [ -f bot.pid ] && kill -0 "$(cat bot.pid)" 2>/dev/null && { echo "聊天命令已经在跑（pid $(cat bot.pid)）"; exit 0; }
          nohup setsid ./bot.py > bot.log 2>&1 & echo $! > bot.pid; echo "聊天命令启动（pid $(cat bot.pid)），游戏里输入 !help" ;;
  ab)     shift; python3 -c "import bot,sys; bot.ab(*sys.argv[1:])" "$@" ;;
  bot-stop) kill "$(cat bot.pid)" 2>/dev/null && rm -f bot.pid && echo 已停 ;;
  logs)   $DC logs --tail="${2:-50}" minecraft ;;
  *) cat <<'H'
./demo.sh up | down | status | logs [n]
./demo.sh reset          清空世界、同一个 seed 重开（地图回到全空）
./demo.sh donor|server   服务器这边开/关给 donor 派活（游戏里也可以 /donor demand on|off）
./demo.sh cpu 0.5        改服务器 CPU 配额（重启）
./demo.sh stats 30       量 30 秒的交付速度和 MSPT
./demo.sh ab [轮数] [秒] [速度]  换路线对比（玩家在线、开着 donor）
./demo.sh fly --secs 60 --speed 33.5 --tag off   匀速直线飞（旁观模式，从 (10000,10000) 往东），结果记 runs.jsonl
Mac 上清 worker 缓存（先在游戏里关 donor）：
  pgrep -f fabric-server-launch || rm -rf ~/Desktop/mc/.minecraft/versions/1.21.1-Fabric/mc-donate/worker/world
H
  ;;
esac
