# 开发规则

## 每个小功能的流程

1. **同步**：`git fetch origin`，再 `git merge origin/main`，拿到对方已经合进 `main` 的东西。不用 rebase：分支已经推到 GitHub，rebase 后只能强推。
2. **先写测试**：先写"怎么算做对了"。跑一下，确认它失败，因为功能还没写。
3. **写功能**：写到测试通过。
4. **全部重跑**：`scripts/test.sh stepN`，之前所有步骤的测试也要通过。
5. **提交**：commit 信息用中文，一句话说清改了什么。
6. **合并**：整块功能做完，先 `git merge origin/main` 并重跑测试，全过后在 `main` 上 `git merge --ff-only fish`（或 `kexu`），推送 `main`。

## 目录和分支

下表是历史部署路径；当前 checkout 及分支归属以 `git worktree list` 为准，不照抄其他主机路径。本 checkout 为 `/home/kexu/distributed-mc`。

| 目录 | 分支 | 用途 |
| --- | --- | --- |
| `/home/fish/mc` | `main` | 平时玩的服务器；合并在这里做 |
| `/home/fish/mc-fish` | `fish` | fish 开发 |
| `/home/fish/mc-kexu` | `kexu` | kexu 开发 |

- 同一个分支不能同时在两个目录里检出。要动 `main` 就去 `/home/fish/mc`。
- 尽量一人一个模块，不同时改同一个文件。要改公共文件（`CLAUDE.md`、`scripts/lib.sh`、`docker/compose.yaml`），先和对方说，改完尽快合进 `main`。

## 两台服务器

| | 平时玩的 | 测试用的 |
| --- | --- | --- |
| compose 项目 | `mc-donate` | `mc-test` |
| 端口 | 25565 | 25566（只绑 127.0.0.1） |
| 存档 | `/home/fish/mc/docker/data/` | 各自目录的 `docker/test-data/` |
| 配置 | `/home/fish/mc/docker/.env` | 各自目录的 `docker/.env.test`（自动生成） |
| 管理 | 在 `/home/fish/mc/docker` 里 `docker compose ...` | `scripts/test_server.sh`、`scripts/new_world.sh` |

- **测试只打测试服务器**。`scripts/` 里的脚本从代码上就碰不到 `world`，不要绕过它们直接操作 `mc-donate`。
- 测试服务器每轮都用全新世界，旧的测试世界由 `new_world.sh` 自动删掉。

## 机器资源

开发机是 128 核、251GB（AMD EPYC 7542）。最终部署在 N100 小主机上（4 核 4 线程、8GB）。

- **构建不限资源**：Gradle、反编译（`genSources`）用满整台机器。
- **测试服务器和 benchmark 按 N100 限资源**：配置在 `docker/n100.env`（绑 12-15 这 4 个物理核、容器 6GB、Java 堆 4GB），`scripts/lib.sh` 的 `tc` 自动叠加。换核数用环境变量临时覆盖，比如 `MC_CPUS=2 MC_CPUSET=12-13`。
- **CPU 频率要固定**：跑测试或 benchmark 前先 `scripts/test_config.sh pin`（关睿频，测试用的核固定在 2.9GHz，要 sudo）；`check` 检查，`status` 查看，`unpin` 恢复。睿频是整机开关，pin 期间整台机器都不睿频，大构建前后可以先 `unpin`。没固定时，`tests/step0/test_04_limits.py` 会失败。
- **benchmark 独占，测试和构建之间可以并行**（全局锁 `/tmp/mc-donate-heavy.lock`）：
    - benchmark（`bench.sh`、`test.sh --slow`）拿独占模式：它跑的时候，所有测试服务器和 Gradle 构建都排队等它；它开始前也要等手上的测试和构建结束。
    - 普通测试和 Gradle 拿共享模式：几个目录的测试之间不用互相等。同一个目录里的测试、构建另有本目录的锁，一个接一个跑（共用 compose 项目和存档目录）。
    - **为什么不按 NUMA 节点放行**：11-08 试过"核不在 benchmark 的节点上就不等它"。kexu 第 1 步测量时，fish 的测试启动、容器全停又重建的那几分钟，测量那轮在 `chunky start` 后停了 69.5 秒（平时 9～12 秒），吞吐 2.98 chunk/s（平时 9～10）；当时 fish 那个节点的 CPU 只用了 3～18%。分核只隔开了 CPU，隔不开共用的磁盘和 Docker 守护进程（容器创建删除、世界文件读写）。
    - 每个目录用哪组核写在 `docker/.env.local`（不进 git）：`MC_CPUSET`、`WORKER_CPUSET`、`WORKER2_CPUSET`，几个目录的测试同时跑时各用各的核。现在的分配：benchmark 12-15（节点 0）；fish 目录测试服务器 32-35、worker 36-39、worker2 40-43（节点 2）。
    - Gradle 构建另外不碰 benchmark 所在的 NUMA 节点，其余的核照样用满。
- **跑测试服务器前，先停平时玩的那台**：`docker compose --project-directory /home/fish/mc/docker stop`。脚本发现它还在跑就会退出，不会自动帮人关。要关它得问人。
- 测试跑完，`scripts/test.sh` 会自动 `down` 测试服务器。手动开的记得 `scripts/test_server.sh down`。

## 不能做的事

- 不动 `world` 这个世界。
- 不提交 `docker/.env`、`docker/.env.test`、`docker/data/`、`docker/test-data/`。
- 不替人把 `EULA` 改成 `TRUE`。
- 不用裸的 `git stash`（几个目录共用一个 stash）。临时放一边就用 WIP commit。
