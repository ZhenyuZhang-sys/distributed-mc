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

take_heavy_lock

# 仓库挂在同一个路径，报错里的文件路径和本机一致。
docker run --rm \
  --user "$(id -u):$(id -g)" \
  -e GRADLE_USER_HOME=/gradle-home \
  -v "$GRADLE_HOME_DIR:/gradle-home" \
  -v "$REPO_ROOT:$REPO_ROOT" \
  -w "$REPO_ROOT" \
  "$JDK_IMAGE" \
  ./gradlew --no-daemon --console=plain "$@"
