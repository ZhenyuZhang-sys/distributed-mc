# 所有脚本共用的函数。用法：source "$(dirname "$0")/lib.sh"
#
# 这里的函数只操作测试服务器（mc-test 或 mc-test-<名字>）。
# 平时玩的服务器（项目 mc-donate、世界 world）从代码上碰不到。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOCKER_DIR="$REPO_ROOT/docker"
TEST_ENV="$DOCKER_DIR/.env.test"
TEST_PROJECT="${MCD_TEST_PROJECT:-}"
if [[ -z "$TEST_PROJECT" && -f "$TEST_ENV" ]]; then
  TEST_PROJECT="$(sed -n 's/^MCD_TEST_PROJECT=//p' "$TEST_ENV")"
fi
TEST_PROJECT="${TEST_PROJECT:-mc-test}"
TEST_DATA="$DOCKER_DIR/test-data"
WORKER_DATA="$DOCKER_DIR/test-data-worker"
WORKER2_DATA="$DOCKER_DIR/test-data-worker2"
# 第 7 步 7.3 的慢 donor（profile worker3，平时不起）
WORKER3_DATA="$DOCKER_DIR/test-data-worker3"
EXCHANGE="$DOCKER_DIR/test-exchange"
WORLD_ENV="$TEST_DATA/world.env"
LIMITS_ENV="$DOCKER_DIR/n100.env"
# 本目录用哪组核等（不进 git），叠在 n100.env 之后。几个目录同时跑测试时各用各的核
LOCAL_ENV="${MCD_LOCAL_ENV:-$DOCKER_DIR/.env.local}"
PLAY_PROJECT="mc-donate"
# 全局锁：benchmark 拿独占模式（take_heavy_lock），测试服务器和 Gradle 拿共享模式（take_shared_lock）。
# benchmark 跑的时候别的重活都等它；benchmark 也要等手上的测试和构建结束。几个目录的测试之间不用互相等。
# 用户 11-09 决定：测量只求大概，两个目录可以各用各的锁文件（.env.local 或环境变量 MCD_HEAVY_LOCK）、
# 各用各的 NUMA 节点并行跑。环境变量优先，其次 .env.local，都没有就用大家共用的那个。
HEAVY_LOCK="${MCD_HEAVY_LOCK:-}"
if [[ -z "$HEAVY_LOCK" && -f "$LOCAL_ENV" ]]; then
  HEAVY_LOCK="$(sed -n 's/^MCD_HEAVY_LOCK=//p' "$LOCAL_ENV" | tail -1)"
fi
HEAVY_LOCK="${HEAVY_LOCK:-/tmp/mc-donate-heavy.lock}"
# 本目录的锁：测试、构建拿。同一个目录里共用 compose 项目和存档目录，不能两个同时跑
CHECKOUT_LOCK="/tmp/mc-donate-checkout-$(printf '%s' "$REPO_ROOT" | md5sum | cut -c1-12).lock"

info() { echo "==> $*" >&2; }
die() { echo "错误：$*" >&2; exit 1; }

[[ "$TEST_PROJECT" =~ ^mc-test(-[a-z0-9][a-z0-9_-]*)?$ ]] || die "非法测试项目名：$TEST_PROJECT"

# 第一次用时生成 docker/.env.test。EULA 默认 FALSE，由运行的人自己改。
ensure_test_env() {
  if [[ ! -f "$TEST_ENV" ]]; then
    local pw
    pw="$(python3 -c 'import secrets; print(secrets.token_hex(24))')"
    umask 077
    cat >"$TEST_ENV" <<EOF
# 测试服务器的配置。不进 git。由 scripts/lib.sh 生成。
# 阅读 https://aka.ms/MinecraftEULA 后，由运行者自行改成 TRUE。
EULA=FALSE

RCON_PASSWORD=$pw
MC_DATA_DIR=./test-data
MC_PORT=25566
MC_BIND_ADDRESS=127.0.0.1
MC_RESTART=no
MC_SEED=424242
MC_LEVEL=test
MCD_TEST_PROJECT=$TEST_PROJECT
EOF
    info "已生成 $TEST_ENV"
  fi
  if ! grep -qx 'EULA=TRUE' "$TEST_ENV"; then
    die "测试服务器还没同意 EULA。阅读 https://aka.ms/MinecraftEULA 后，自己把 $TEST_ENV 里的 EULA 改成 TRUE。"
  fi
  mkdir -p "$TEST_DATA"
}

# compose 会看到的变量值：shell 环境变量优先，否则按 --env-file 的顺序后面的覆盖前面的（和 tc 传的顺序一致）。
env_value() {
  local name="$1" v="" f x
  if [[ -n "${!name+x}" ]]; then
    printf '%s' "${!name}"
    return
  fi
  for f in "$TEST_ENV" "$LIMITS_ENV" "$LOCAL_ENV" "$WORLD_ENV"; do
    [[ -f "$f" ]] || continue
    x="$(sed -n "s/^$name=//p" "$f" | tail -1)"
    [[ -n "$x" ]] && v="$x"
  done
  printf '%s' "$v"
}

# worker 的生成线程数：明确设了（WORKER_GEN_THREADS）就用它，否则 = 核数取整、至少 1（和真客户端一样，池 = 设的线程数）。
gen_threads() {
  local set="$1" cpus="$2"
  if [[ -n "$set" ]]; then
    [[ "$set" =~ ^[1-9][0-9]*$ ]] || die "生成线程数要是正整数：$set"
    printf '%s' "$set"
    return
  fi
  awk -v c="$cpus" 'BEGIN { n = int(c); if (n < c) n++; if (n < 1) n = 1; print n }'
}

# 对测试服务器执行 docker compose：compose.yaml 叠加 compose.test.yaml（挂我们的 mod）。
# 后面的 env 文件覆盖前面的：.env.test < n100.env（资源限制）< world.env（new_world.sh 写的）。
# shell 里的环境变量优先级最高，可以临时覆盖，比如 MC_CPUS=2 MC_CPUSET=12-13。
tc() {
  local args=(-p "$TEST_PROJECT" --project-directory "$DOCKER_DIR"
    -f "$DOCKER_DIR/compose.yaml" -f "$DOCKER_DIR/compose.test.yaml"
    --env-file "$TEST_ENV" --env-file "$LIMITS_ENV")
  [[ -f "$LOCAL_ENV" ]] && args+=(--env-file "$LOCAL_ENV")
  # 先建好挂载的目录，否则 docker 会用 root 建空目录
  mkdir -p "$REPO_ROOT/build/mods" "$REPO_ROOT/build/mods-worker" "$TEST_DATA" "$WORKER_DATA" "$WORKER2_DATA" "$WORKER3_DATA" "$EXCHANGE"
  [[ -f "$WORLD_ENV" ]] && args+=(--env-file "$WORLD_ENV")
  # 第 9 步给用户自己的电脑测（槽位 u1）：coordinator 端口映射到宿主机，只绑 127.0.0.1，用户 ssh 转发过去。
  # 环境变量或 .env.local 里有 MCD_COORD_HOST_PORT 才加，默认不映射（coordinator 只在容器网络里）
  local coord_port="${MCD_COORD_HOST_PORT:-}"
  if [[ -z "$coord_port" && -f "$LOCAL_ENV" ]]; then
    coord_port="$(sed -n 's/^MCD_COORD_HOST_PORT=//p' "$LOCAL_ENV" | tail -1)"
  fi
  if [[ -n "$coord_port" ]]; then
    [[ "$coord_port" =~ ^[0-9]+$ ]] || die "MCD_COORD_HOST_PORT 要是端口号：$coord_port"
    export MCD_COORD_HOST_PORT="$coord_port"
    args+=(-f "$DOCKER_DIR/compose.coord-port.yaml")
  fi
  # 11-10：worker 的 -XX:ActiveProcessorCount = 生成线程数 + 1（真客户端 WorkerLaunch 也是这样），compose 不会算术，这里算
  # 默认核数和 compose.test.yaml 里的一致（worker 4、worker3 0.5）
  local wc w3c wt w3t
  wc="$(env_value WORKER_CPUS)"
  w3c="$(env_value WORKER3_CPUS)"
  wt="$(gen_threads "$(env_value WORKER_GEN_THREADS)" "${wc:-4}")" || return 1
  w3t="$(gen_threads "$(env_value WORKER3_GEN_THREADS)" "${w3c:-0.5}")" || return 1
  export MCD_WORKER_APC=$((wt + 1)) MCD_WORKER3_APC=$((w3t + 1))
  # 停服、关服时总带上 worker（它是按需启动的），免得漏关
  case "${1:-}" in
    stop|down) args+=(--profile worker --profile worker2 --profile worker3) ;;
  esac
  # 容器用跑脚本的人的 uid 写存档，否则 new_world.sh 删不掉旧世界
  MC_UID="${MC_UID:-$(id -u)}" MC_GID="${MC_GID:-$(id -g)}" docker compose "${args[@]}" "$@"
}

# 保险：确认 compose 实际挂载的存档目录就是 test-data 和 test-data-worker，否则不往下走。
assert_test_data_mount() {
  local got
  got="$(tc config --format json | python3 -c '
import json, sys
svcs = json.load(sys.stdin)["services"]
# 有 worker 服务时（第 3 步起）它的存档目录也要查
for name in ("minecraft", "worker", "worker2", "worker3"):
    if name in svcs:
        print(name, next(v["source"] for v in svcs[name]["volumes"] if v["target"] == "/data"))')"
  local want="minecraft $TEST_DATA"
  [[ "$got" == *"worker "* ]] && want+=$'\n'"worker $WORKER_DATA"
  [[ "$got" == *"worker2 "* ]] && want+=$'\n'"worker2 $WORKER2_DATA"
  [[ "$got" == *"worker3 "* ]] && want+=$'\n'"worker3 $WORKER3_DATA"
  [[ "$got" == "$want" && ! -L "$TEST_DATA" && ! -L "$WORKER_DATA" && ! -L "$WORKER2_DATA" && ! -L "$WORKER3_DATA" ]] \
    || die "存档目录是 $got，不是独立的 $TEST_DATA 和 $WORKER_DATA，停止。"
}

# Compose 项目可能由其他 checkout 创建；先检查，再允许 stop/down。
# 测试服务器的存档在 test-data，worker 的在 test-data-worker。
assert_test_instance() {
  local cid src cids
  cids="$(docker ps -aq --filter "label=com.docker.compose.project=$TEST_PROJECT")" || die "无法核验测试容器归属"
  for cid in $cids; do
    src="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Source}}{{end}}{{end}}' "$cid")"
    [[ "$src" == "$TEST_DATA" || "$src" == "$WORKER_DATA" || "$src" == "$WORKER2_DATA" || "$src" == "$WORKER3_DATA" ]] || die "项目 $TEST_PROJECT 的容器 $cid 属于 $src；不能接管。"
  done
}

# 停服后、替换世界或容器前保存日志（测试服务器和 worker 各一份）；归档失败就保留旧世界。
archive_test_logs() {
  local cid destination service
  cid="$(tc ps -aq minecraft)" || die "无法查询待归档容器"
  [[ -n "$cid" ]] || return 0
  destination="${MCD_ARTIFACT_DIR:-$REPO_ROOT/artifacts}/before-replace-${old_level:-unknown}-$(date -u +%Y%m%dT%H%M%S)-$$"
  mkdir -p "$destination"
  tc logs --no-color --no-log-prefix minecraft >"$destination/server.log" || die "无法归档旧世界日志：$destination"
  [[ -s "$destination/server.log" ]] || die "旧世界日志为空：$destination"
  for service in worker worker2 worker3; do
    if [[ -n "$(tc ps -aq "$service")" ]]; then
      tc logs --no-color --no-log-prefix "$service" >"$destination/$service.log" || die "无法归档 $service 日志：$destination"
    fi
  done
}

validate_world_name() {
  [[ "$1" =~ ^[a-z0-9_-]{1,32}$ && "$1" != world ]] || die "非法测试世界名：$1"
  [[ ! -L "$TEST_DATA/$1" && ! -L "$WORKER_DATA/$1" && ! -L "$WORKER2_DATA/$1" && ! -L "$WORKER3_DATA/$1" ]] || die "测试世界不能是符号链接：$1"
}

# 用法：delete_test_world <名字> [目录，默认 test-data；只能是 test-data 或 test-data-worker]
delete_test_world() {
  local dir="${2:-$TEST_DATA}"
  validate_world_name "$1"
  [[ "$dir" == "$TEST_DATA" || "$dir" == "$WORKER_DATA" || "$dir" == "$WORKER2_DATA" || "$dir" == "$WORKER3_DATA" ]] || die "只能删测试存档目录里的世界：$dir"
  [[ ! -L "$dir" ]] || die "测试数据目录不能是符号链接：$dir"
  if [[ -e "$dir/$1" ]]; then
    [[ -d "$dir/$1" ]] || die "测试世界不是目录：$dir/$1"
    rm -rf -- "${dir:?}/$1"
  fi
}

# 平时玩的服务器在跑就退出。不自动帮人关。
require_play_server_stopped() {
  local running
  running="$(docker ps -q --filter "label=com.docker.compose.project=$PLAY_PROJECT" --filter status=running)"
  if [[ -n "$running" ]]; then
    die "平时玩的服务器 $PLAY_PROJECT 还在跑，请先由管理者正常停服。"
  fi
}

# 重活锁：构建、测试服务器、benchmark 同一时间只跑一个。
# 子进程继承锁，通过 MCD_LOCK_HELD 避免重复加锁导致卡死。
take_heavy_lock() {
  [[ "${MCD_LOCK_HELD:-}" == 1 ]] && return 0
  # Linux flock 可在只读 FD 上持排他锁；避免跨用户截断 /tmp 文件失败。
  if [[ ! -e "$HEAVY_LOCK" ]]; then
    (umask 000; set -o noclobber; : >"$HEAVY_LOCK") 2>/dev/null || true
  fi
  [[ -f "$HEAVY_LOCK" && ! -L "$HEAVY_LOCK" ]] || die "锁文件必须是普通文件：$HEAVY_LOCK"
  exec 9<"$HEAVY_LOCK"
  if ! flock -n 9; then
    info "另一个重活正在跑，排队等待……"
    flock 9
  fi
  export MCD_LOCK_HELD=1
}

# 测试服务器实际用的 CPU 列表：环境变量优先，否则读 n100.env。
test_cpuset() {
  local v=""
  if [[ -n "${MC_CPUSET:-}" ]]; then
    echo "$MC_CPUSET"
    return
  fi
  [[ -f "$LOCAL_ENV" ]] && v="$(sed -n 's/^MC_CPUSET=//p' "$LOCAL_ENV")"
  [[ -n "$v" ]] || v="$(sed -n 's/^MC_CPUSET=//p' "$LIMITS_ENV")"
  echo "$v"
}

# benchmark 用的核：n100.env 的 MC_CPUSET 和它们的超线程兄弟，一行一个
bench_cpus() {
  local c
  for c in $(expand_cpus "$(sed -n 's/^MC_CPUSET=//p' "$LIMITS_ENV")"); do
    expand_cpus "$(cat "/sys/devices/system/cpu/cpu$c/topology/thread_siblings_list")"
  done | sort -n | uniq
}

# 一组核在哪些 NUMA 节点上（一行一个节点号）
numa_nodes_of() {
  local c
  for c in "$@"; do
    basename "$(ls -d /sys/devices/system/cpu/cpu"$c"/node* 2>/dev/null | head -1)" | sed 's/^node//'
  done | sort -n | uniq
}

# benchmark 所在的 NUMA 节点
bench_nodes() {
  numa_nodes_of $(bench_cpus)
}

# 构建能用的核：benchmark 所在 NUMA 节点以外的全部核（逗号分隔），不和测量抢内存带宽
build_cpus() {
  local n bad
  bad="$(bench_nodes)"
  for n in $(ls -d /sys/devices/system/node/node* | sed 's/.*node//' | sort -n); do
    grep -qx "$n" <<<"$bad" && continue
    expand_cpus "$(cat /sys/devices/system/node/node"$n"/cpulist)"
  done | sort -n | paste -sd,
}

# 把 "8-11,20" 这样的列表展开成一行一个编号。
expand_cpus() {
  local part
  IFS=',' read -ra parts <<<"$1"
  for part in "${parts[@]}"; do
    if [[ "$part" == *-* ]]; then seq "${part%-*}" "${part#*-}"; else echo "$part"; fi
  done
}

# 当前测试世界的名字。
current_level() {
  if [[ -f "$WORLD_ENV" ]]; then
    sed -n 's/^MC_LEVEL=//p' "$WORLD_ENV"
  fi
}

# 准备好跑重活：锁、平时的服务器已停、配置齐全。
# 共享模式拿全局锁：和别的测试、构建可以同时持有；benchmark 拿着独占锁时等它。
# 不按 NUMA 节点放行：分核只隔开 CPU，隔不开共用的磁盘和 Docker 守护进程。
take_shared_lock() {
  [[ -n "${MCD_LOCK_HELD:-}" ]] && return 0
  if [[ ! -e "$HEAVY_LOCK" ]]; then
    (umask 000; set -o noclobber; : >"$HEAVY_LOCK") 2>/dev/null || true
  fi
  [[ -f "$HEAVY_LOCK" && ! -L "$HEAVY_LOCK" ]] || die "锁文件必须是普通文件：$HEAVY_LOCK"
  exec 9<"$HEAVY_LOCK"
  if ! flock -s -n 9; then
    info "benchmark 正在跑，排队等它结束……"
    flock -s 9
  fi
  export MCD_LOCK_HELD=shared
}

# 本目录的锁：同一个目录里的测试、构建排队；不同目录之间不排队
take_checkout_lock() {
  [[ "${MCD_CHECKOUT_LOCK_HELD:-}" == 1 ]] && return 0
  exec 8>>"$CHECKOUT_LOCK"
  if ! flock -n 8; then
    info "本目录另一个测试或构建正在跑，排队等待……"
    flock 8
  fi
  export MCD_CHECKOUT_LOCK_HELD=1
}

# 测试服务器要用的准备工作：本目录的锁 + 全局锁的共享模式（benchmark 跑的时候等它）。
prepare_heavy() {
  take_checkout_lock
  take_shared_lock
  require_play_server_stopped
  ensure_test_env
  assert_test_data_mount
  assert_test_instance
}
