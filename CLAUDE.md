# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# mc-donate

让在线玩家的电脑帮 Minecraft 服务器提前生成 chunk。项目介绍见 `README.md`，开发顺序和每一步的验收标准见 `DEV_PLAN.md`。动手前先看 `DEV_PLAN.md`，确认当前在哪一步；某一步有详细计划的，在 `docs/stepN-plan.md`。

## 现在有什么

第 0 步已有 `scripts/`、`tests/`、`docker/` 和只读 `analysis/mca/`。原来的两个 skip 已替换为真实存档断言；完整验收记录见 `docs/step0-plan.md`。已同步上游第 2 步的仓库骨架和 bench mod。第 1 步按用户决定只做了粗测（`analysis/results/baseline.md`，验收 `tests/step1/test_90_matrix.py` 复核粗测产物）；完整矩阵换到部署机器后重新 pilot 再跑，轮数和预热量在 `bench/protocol.json`。

## 测试环境怎么串起来

- `scripts/lib.sh` 的 `tc` 是 Compose 入口，叠加 `docker/compose.test.yaml` 挂载本地 mod，使用 `docker/.env.test`、`docker/n100.env` 和 `docker/test-data/world.env`。项目由 `MCD_TEST_PROJECT` 指定，默认 `mc-test`，仅允许 `mc-test` 或 `mc-test-<名字>`；本 checkout 使用 `mc-test-kexu`、端口 25567。`prepare_heavy` 拿共享锁，确认游戏服已停、EULA 已同意、挂载及现有实例属于当前 checkout。
- Python 侧（`tests/mcd.py`）不自己拼 docker 命令：`compose()` 通过 bash 调 `lib.sh` 的 `tc`，`rcon()` 调 `scripts/rcon.sh`。新加的工具函数也走这条路。
- `tests/conftest.py` 的 session fixture 先在 A 世界生成远处哨兵，再换 B 世界并保存无玩家的初始快照，向测试 yield B。读取器只读一致快照；Chunky 通过 CSV 明确指定坐标。各阶段日志在删世界或重启前归档，最终停服后再检查。证据保存至 `artifacts/`；清理失败同样返回非零。
- 正式服务器和测试服务器共用 `docker/compose.yaml`；测试另加 mod 挂载覆盖文件和测试 env。改基础 compose 会同时影响两台。

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

测试服务器（在仓库根目录运行，只操作当前配置且归属本 checkout 的测试实例）：

```bash
scripts/test.sh step0              # 跑测试，退出码 0 才算通过
.venv/bin/python -m pytest tests/step0/test_00_mca.py -q  # 无 Docker 的读取器自测
scripts/new_world.sh <名字> [seed]  # 测试服务器换一个全新世界
scripts/rcon.sh <命令>              # 给测试服务器发控制台命令
scripts/test_server.sh up|down|status|logs
```

平时玩的服务器由管理者在实际部署目录操作；历史 `/home/fish/mc` 路径不适用于当前 checkout：

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
- 当前 checkout 是 `/home/kexu/distributed-mc`，分支 `kexu`，已有 GitHub 远程。同步前检查未提交改动和实际远程状态，不照搬历史 worktree 路径。
- 分支已发布，用 `git fetch origin`、`git merge origin/main` 同步，不 rebase 已发布提交。
- 一个功能做完，先同步上游并重跑测试；全过后在 `main` 上 `git merge --ff-only kexu` 并推送。
- 尽量分开改：同一步里一人一个模块，避免同时改同一个文件。
- commit 信息用中文，一句话说清改了什么。
