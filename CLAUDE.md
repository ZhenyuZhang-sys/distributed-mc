# mc-donate

让在线玩家的电脑帮 Minecraft 服务器提前生成 chunk。项目介绍见 `README.md`，开发顺序和每一步的验收标准见 `DEV_PLAN.md`。动手前先看 `DEV_PLAN.md`，确认当前在哪一步。

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

## 常用命令

都在 `docker/` 目录下运行：

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
- 开工前先同步：`git fetch && git rebase origin/main`。
- 一个功能做完、`scripts/test.sh` 全部通过后，再合进 `main`。合并前先 rebase 到最新的 `main`。
- 尽量分开改：同一步里一人一个模块，避免同时改同一个文件。
- commit 信息用中文，一句话说清改了什么。
