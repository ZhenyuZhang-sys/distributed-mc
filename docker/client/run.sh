#!/usr/bin/env bash
# 跑一次真客户端：准备（下载 1.21.1 客户端、写死版本的 Fabric Loader），装上 /mods 里的 mod，用离线账号进服，
# 挂 <秒数> 秒后结束，把证据写进 /out：客户端日志、期间的进程快照、游戏目录里 mod 建了什么。
# 用法：run.sh <玩家名> <秒数>   环境变量：MC_SERVER（默认 minecraft:25565）；CLIENT_JVM_ARGS（给客户端的额外 JVM 参数，
# 比如测试钩子的 -Dmcdonate.testhooks.leaveAfter=40）；KILL_AFTER（连上以后这么多秒对游戏进程 kill -9，测 9.4）
set -euo pipefail
NAME="$1"; SECONDS_TO_STAY="$2"; SERVER="${MC_SERVER:-minecraft:25565}"
MC_VERSION=1.21.1; LOADER=0.16.14; PROFILE="fabric-loader-${LOADER}-${MC_VERSION}"
export HOME=/cache                       # ~/.minecraft（版本、库、资源）在卷里，下载一次
GAME=/game                               # 这一轮的游戏目录（mods、config、mc-donate），每轮新的
rm -rf "$GAME" && mkdir -p "$GAME/mods" /out
# Java 的 user.home 来自系统用户（/root），不看 $HOME：要显式设，否则 HeadlessMC 下载到 /root/.minecraft（不在卷里，
# 和下面写 Fabric 版本描述的位置也对不上——11-09 第一次跑报 "Couldn't find object for name fabric-loader-…"）
HMC=(java -Duser.home="$HOME" -Dhmc.check.xvfb=false -Dhmc.java.use.current=true -Dhmc.offline=true -Dhmc.offline.username="$NAME"
     -Dhmc.gamedir="$GAME" -Dhmc.always.lwjgl.flag=true -Dhmc.auto.download.specifics=false
     "-Dhmc.gameargs=--quickPlayMultiplayer $SERVER" ${CLIENT_JVM_ARGS:+"-Dhmc.jvmargs=$CLIENT_JVM_ARGS"}
     -jar /opt/headlessmc.jar)

# 1. 原版客户端（HeadlessMC 从 Mojang 版本清单下载，按清单里的 sha1 校验）
[[ -f "$HOME/.minecraft/versions/$MC_VERSION/$MC_VERSION.json" ]] || "${HMC[@]}" --command "download $MC_VERSION"
# 2. Fabric Loader 写死 0.16.14（和服务器一样）：版本描述从 Fabric 官方 meta 取，库在启动时按描述下载
P="$HOME/.minecraft/versions/$PROFILE"
if [[ ! -f "$P/$PROFILE.json" ]]; then
  mkdir -p "$P"
  curl -fsSL "https://meta.fabricmc.net/v2/versions/loader/$MC_VERSION/$LOADER/profile/json" -o "$P/$PROFILE.json"
fi
cp /mods/*.jar "$GAME/mods/"
# 第一次启动会先弹无障碍引导和多人游戏警告，quickPlay 就停在那里不连（11-09 实测：等了 20 分钟没开始连）。这里直接跳过
cat > "$GAME/options.txt" <<'OPT'
onboardAccessibility:false
skipMultiplayerWarning:true
joinedFirstServer:true
renderDistance:2
simulationDistance:5
maxFps:30
soundCategory_master:0.0
OPT
ls -la "$GAME/mods" > /out/mods.txt

# 3. 启动，挂 SECONDS_TO_STAY 秒；期间每 5 秒记一次进程（看有没有 worker 子进程）
"${HMC[@]}" --command "launch $PROFILE -lwjgl -offline" > /out/launcher.log 2>&1 &
LAUNCHER=$!
# 第一次启动要下载资源文件（约 4000 个，11-09 实测 40 秒只下了 70%）：先等到客户端真的开始连服务器，再开始计时
for ((w = 0; w < 1200; w += 5)); do
  grep -qs "Connecting to" "$GAME/logs/latest.log" && break
  kill -0 "$LAUNCHER" 2>/dev/null || break
  sleep 5
done
echo "waited ${w}s for the client to start connecting" >> /out/launcher.log
for ((i = 0; i < SECONDS_TO_STAY; i += 5)); do
  sleep 5
  { echo "== t=$((i + 5))"; ps -eo pid,ppid,args --width 300; } >> /out/ps.txt
  if [[ -n "${KILL_AFTER:-}" && $((i + 5)) -ge "$KILL_AFTER" && ! -f /out/killed ]]; then
    GAME_PID="$(pgrep -f 'net.fabricmc.loader.impl.launch.knot.KnotClient' | head -1)"
    echo "kill -9 game pid $GAME_PID at t=$((i + 5))" > /out/killed
    kill -9 "$GAME_PID" 2>/dev/null || true
    # 之后每秒记一次进程，看 worker 几秒内退出
    for ((k = 1; k <= 15; k++)); do sleep 1; { echo "== after-kill t=$k"; ps -eo pid,ppid,args --width 300; } >> /out/ps-after-kill.txt; done
    break
  fi
done
kill "$LAUNCHER" 2>/dev/null || true
pkill -f "$PROFILE" 2>/dev/null || true
sleep 3
cp "$GAME/logs/latest.log" /out/latest.log 2>/dev/null || true
(cd "$GAME" && find . -maxdepth 2 | sort) > /out/gamedir.txt
echo done > /out/done
