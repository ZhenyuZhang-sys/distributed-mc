# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# mc-donate

让在线玩家的电脑帮 Minecraft 服务器提前生成 chunk。项目介绍见 `README.md`，开发顺序和每一步的验收标准见 `DEV_PLAN.md`。动手前先看 `DEV_PLAN.md`，确认当前在哪一步；某一步有详细计划的，在 `docs/stepN-plan.md`。

## 现在有什么

目前只有第 0 步的测试环境：`scripts/`、`tests/`、`docker/`。`common/`、`coordinator/` 等 mod 目录和 `analysis/mca/` 还没建。`tests/step0` 里有两个测试在等存档读取器，现在会 `skip`，所以 `scripts/test.sh step0` 按规则是失败的，这是预期的。

## 测试环境怎么串起来

- `scripts/lib.sh` 是唯一碰 docker 的地方。`tc` 函数固定用 compose 项目 `mc-test` 和 `docker/.env.test`，再叠加 `docker/test-data/world.env`（`new_world.sh` 写的世界名和 seed）。`prepare_heavy` 负责拿锁、确认平时的服务器已停、EULA 已同意、存档目录是 `test-data`。
- Python 侧（`tests/mcd.py`）不自己拼 docker 命令：`compose()` 通过 bash 调 `lib.sh` 的 `tc`，`rcon()` 调 `scripts/rcon.sh`。新加的工具函数也走这条路。
- `tests/conftest.py` 的 `server` fixture 是整轮共用的：第一次用到时调 `new_world.sh t<时间戳> 424242`，结束时扫日志（`/ERROR]`、`Exception`、堆栈行），去掉 `tests/log_whitelist.txt` 里的再判失败。
- 正式服务器和测试服务器共用同一个 `docker/compose.yaml`，差别全在 env 文件里。改 compose 会同时影响两台。

## 技术栈

- Minecraft 1.21.1，Fabric Loader 0.16.14，Fabric API 0.116.6+1.21.1，Java 21
- 映射：Mojang 官方名字 + Parchment 参数名
- 服务器跑在 Docker 里（`docker/compose.yaml`，镜像 `itzg/minecraft-server`），本机不装 Java
- 测量和分析用 Python

## 目录（计划）

```
common/        协议：消息格式、NBT 打包
coordinator/   服务器 mod：需求、调度、写入、验证
worker/        donor mod：领任务、生成、导出
client/        客户端 mod：启动 worker、按帧率限速
bench/         测量 mod、假玩家场景、实时地图
docker/        镜像和 compose 文件
analysis/      Python：存档读取器、实验脚本、画图
scripts/       rcon.sh、new_world.sh、test.sh
```

## 详细规则

- 开发流程、目录分工、资源限制：`.claude/rules/development.md`
- 测试方法：`.claude/rules/testing.md`

## 常用命令

测试服务器（在仓库根目录运行，只会碰 `mc-test`）：

```bash
scripts/test.sh step0              # 跑测试，退出码 0 才算通过
scripts/new_world.sh <名字> [seed]  # 测试服务器换一个全新世界
scripts/rcon.sh <命令>              # 给测试服务器发控制台命令
scripts/test_server.sh up|down|status|logs
```

平时玩的服务器，在 `/home/fish/mc/docker/` 里运行：

```bash
docker compose up -d --wait --wait-timeout 600   # 启动
docker compose ps                                # 状态
docker compose logs --tail=100 minecraft         # 日志
docker compose exec -T minecraft rcon-cli <命令>  # 控制台命令，不带 /
```

## 规则

- **不要动 `world` 这个世界**：那是我们平时玩的存档。测试和 benchmark 一律用 `new_world.sh` 开新世界，跑完删掉。
- **不要提交** `docker/.env`（有 RCON 密码）和 `docker/data/`（存档和下载的 jar）。
- **EULA**：不要替人把 `EULA` 改成 `TRUE`，由运行服务器的人自己同意。
- **先写测试再写功能**：每一步的验收用 `scripts/test.sh stepN` 跑，退出码 0 才算通过。改完代码要把之前所有步骤的测试重跑一遍。
- **判对错靠存档**：端到端测试用 `analysis/mca/` 读 `.mca` 文件，和同一个 seed 下原版生成的对照世界比，不靠看游戏画面。
- **性能测试**标成 `slow`，不放进日常回归；每次都用全新世界、同一个 seed（默认 424242）。
- 文档和注释用中文，写短句。

## Git 协作

两个人一起开发，各用一个分支：

- `main`：只放测试通过的代码。
- `fish`、`kexu`：各自的开发分支。
- 远程仓库在 GitHub（`origin`），三个分支都推到上面。
- 开工前先同步：`git pull origin <自己的分支>`，再 `git merge origin/main`。**用 merge，不用 rebase**：rebase 会改写已经推上去的提交，只能强推。
- 一个功能做完、`scripts/test.sh` 全部通过后，再合进 `main`：先把最新的 `main` merge 进自己的分支，测试再过一遍，然后 `main` 上 `git merge --ff-only <自己的分支>` 并推送。
- 尽量分开改：同一步里一人一个模块，避免同时改同一个文件。
- commit 信息用中文，一句话说清改了什么。
