# 所有脚本共用的函数。用法：source "$(dirname "$0")/lib.sh"
#
# 这里的函数只操作测试服务器（compose 项目 mc-test）。
# 平时玩的服务器（项目 mc-donate、世界 world）从代码上碰不到。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOCKER_DIR="$REPO_ROOT/docker"
TEST_PROJECT="mc-test"
TEST_ENV="$DOCKER_DIR/.env.test"
TEST_DATA="$DOCKER_DIR/test-data"
WORLD_ENV="$TEST_DATA/world.env"
LIMITS_ENV="$DOCKER_DIR/n100.env"
PLAY_PROJECT="mc-donate"
PLAY_DIR="/home/fish/mc/docker"
HEAVY_LOCK="/tmp/mc-donate-heavy.lock"

info() { echo "==> $*" >&2; }
die() { echo "错误：$*" >&2; exit 1; }

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
EOF
    info "已生成 $TEST_ENV"
  fi
  if ! grep -qx 'EULA=TRUE' "$TEST_ENV"; then
    die "测试服务器还没同意 EULA。阅读 https://aka.ms/MinecraftEULA 后，自己把 $TEST_ENV 里的 EULA 改成 TRUE。"
  fi
  mkdir -p "$TEST_DATA"
}

# 对测试服务器执行 docker compose。
# 后面的 env 文件覆盖前面的：.env.test < n100.env（资源限制）< world.env（new_world.sh 写的）。
# shell 里的环境变量优先级最高，可以临时覆盖，比如 MC_CPUS=2 MC_CPUSET=8-9。
tc() {
  local args=(-p "$TEST_PROJECT" --project-directory "$DOCKER_DIR"
    -f "$DOCKER_DIR/compose.yaml" --env-file "$TEST_ENV" --env-file "$LIMITS_ENV")
  [[ -f "$WORLD_ENV" ]] && args+=(--env-file "$WORLD_ENV")
  # 容器用跑脚本的人的 uid 写存档，否则 new_world.sh 删不掉旧世界
  MC_UID="${MC_UID:-$(id -u)}" MC_GID="${MC_GID:-$(id -g)}" docker compose "${args[@]}" "$@"
}

# 保险：确认 compose 实际挂载的存档目录就是 test-data，否则不往下走。
assert_test_data_mount() {
  local src
  src="$(tc config --format json | python3 -c '
import json, sys
vols = json.load(sys.stdin)["services"]["minecraft"]["volumes"]
print(next(v["source"] for v in vols if v["target"] == "/data"))')"
  [[ "$src" == "$TEST_DATA" ]] || die "存档目录是 $src，不是 $TEST_DATA，停止。"
}

# 平时玩的服务器在跑就退出。不自动帮人关。
require_play_server_stopped() {
  local running
  running="$(docker ps -q --filter "label=com.docker.compose.project=$PLAY_PROJECT" --filter status=running)"
  if [[ -n "$running" ]]; then
    die "平时玩的服务器还在跑，两台同时开会互相干扰。先手动停：docker compose --project-directory $PLAY_DIR stop"
  fi
}

# 重活锁：构建、测试服务器、benchmark 同一时间只跑一个。
# 子进程继承锁，通过 MCD_LOCK_HELD 避免重复加锁导致卡死。
take_heavy_lock() {
  [[ "${MCD_LOCK_HELD:-}" == 1 ]] && return 0
  exec 9>"$HEAVY_LOCK"
  if ! flock -n 9; then
    info "另一个重活正在跑，排队等待……"
    flock 9
  fi
  export MCD_LOCK_HELD=1
}

# 测试服务器实际用的 CPU 列表：环境变量优先，否则读 n100.env。
test_cpuset() {
  if [[ -n "${MC_CPUSET:-}" ]]; then
    echo "$MC_CPUSET"
  else
    sed -n 's/^MC_CPUSET=//p' "$LIMITS_ENV"
  fi
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
prepare_heavy() {
  take_heavy_lock
  require_play_server_stopped
  ensure_test_env
  assert_test_data_mount
}
