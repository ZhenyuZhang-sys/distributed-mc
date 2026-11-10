#!/usr/bin/env bash
# 在 Docker 里跑 Gradle，本机不用装 Java。
# 用法：scripts/gradle.sh <任务…>，例如 scripts/gradle.sh build collectMods
#
# Gradle 和 Loom 的下载缓存放在 ~/.cache/mc-donate-gradle，几个 worktree 共用，
# 第二次构建不用重新下载。
source "$(dirname "$0")/lib.sh"

[[ $# -ge 1 ]] || die "用法：scripts/gradle.sh <任务…>"

# Loom 1.19 要求 Gradle 跑在 Java 25 上；mod 本身按 Java 21 编译（见 build.gradle）。
JDK_IMAGE="${MCD_JDK_IMAGE:-eclipse-temurin:25-jdk}"
GRADLE_HOME_DIR="${MCD_GRADLE_HOME:-$HOME/.cache/mc-donate-gradle}"
mkdir -p "$GRADLE_HOME_DIR"

# benchmark 跑的时候等它（全局锁的共享模式）；同一个目录里的构建排队。
# 另外不碰 benchmark 所在的 NUMA 节点，其余的核照样用满。
take_checkout_lock
take_shared_lock

# 仓库挂在同一个路径，报错里的文件路径和本机一致。
# 11-10：每个 jar 嵌的 mcdonate-common 版本带上构建它的提交（build.gradle 读 MCD_BUILD_ID），
# 来自不同提交的 jar 放在一起时 Fabric 启动就报错（原来都是 0.1.0，只加载其中一份，跑到一半 NoSuchMethodError）。
# 在宿主机上算（JDK 镜像里不一定有 git）；有没提交的改动加 .dirty
BUILD_ID="$(git -C "$REPO_ROOT" rev-parse --short=7 HEAD 2>/dev/null || echo dev)"
if [[ "$BUILD_ID" != dev && -n "$(git -C "$REPO_ROOT" status --porcelain --untracked-files=no 2>/dev/null)" ]]; then
  BUILD_ID="$BUILD_ID.dirty"
fi

docker run --rm \
  --cpuset-cpus "$(build_cpus)" \
  -e MCD_BUILD_ID="$BUILD_ID" \
  --user "$(id -u):$(id -g)" \
  -e GRADLE_USER_HOME=/gradle-home \
  -v "$GRADLE_HOME_DIR:/gradle-home" \
  -v "$REPO_ROOT:$REPO_ROOT" \
  -w "$REPO_ROOT" \
  "$JDK_IMAGE" \
  ./gradlew --no-daemon --console=plain "$@"
