# 部署 mc-donate 服务器

在你自己的服务器上跑一个装了 mc-donate 的 Minecraft 1.21.1 服务器。装了客户端 mod、点了同意的玩家，
电脑会在后台帮服务器提前生成地形。部署用 `deploy/` 目录，和开发用的 `docker/` 分开。

> **现状（11-09）**：服务器端和客户端在开发机的 Docker 里跑通了（无头真客户端的自动测试全过），
> 用户的服务器（2 核、3 GB 容器）和 Mac 客户端上试过。Windows 客户端没测过。出了问题请把日志发回来（见第 10 节）。
> 从上一版升级看第 11 节。

## 1. 前置条件

| 东西 | 版本 | 说明 |
| --- | --- | --- |
| Docker Engine + Compose 插件 | Compose v2.24 以上 | `docker compose version` 能用就行 |
| Java | 服务器不用装 | 镜像里自带 Java 21 |
| 内存 | 建议 6 GB 以上 | 服务器默认最多 4 GB 堆（`.env` 里 `MC_MAX_MEMORY`）。2 核、3 GB 的小机器用 `compose.n100.yaml`（堆 2 GB，见第 3 节） |
| 自己构建 jar 时 | Docker | `scripts/gradle.sh` 在 Docker 里跑 Gradle（JDK 25 镜像），本机不装 Java |

## 2. 拿到代码和 jar

```bash
git clone <仓库地址> mc-donate && cd mc-donate      # 已经 clone 过就 git pull
```

jar 不在 git 里，两种拿法任选：

1. **在这台服务器上自己构建**（要 Docker，第一次要下载几分钟）：

   ```bash
   scripts/gradle.sh build collectMods -x test
   cp build/mods/mcdonate-coordinator-0.1.0.jar deploy/mods/
   # 给玩家的客户端 mod：client/build/libs/mcdonate-client-0.1.0.jar
   ```

2. **从开发机拷构建好的**：`/home/zhenyu/mc-slots/user-test/deploy/v2.1-14d18f4/` 里有 coordinator（`mods/` 下）和客户端的 jar，
   文件名带提交号，校验和在 `SHA256SUMS`。客户端 jar 里已经带了 worker，不用另装。整个目录拷过来以后在目录里核对：`sha256sum -c SHA256SUMS`。
   coordinator 的 jar 放进仓库的 `deploy/mods/`（文件名带不带提交号都行）。

服务器只装两个 mod：Fabric API（镜像启动时自动从 Modrinth 官方下载 0.116.6+1.21.1）和 mc-donate coordinator。
不要装 `mcdonate-bench`，它是开发用的测量 mod。
**mods 目录里我们的 jar 必须是同一个提交构建的**（演示、测试环境装了 `mcdonate-bench` 时尤其注意：bench 和 coordinator 各自嵌了一份 `mcdonate-common`）。
11-10 起 common 的版本带提交号（`0.1.0-b<提交号>`），不同提交的混在一起时 Fabric 启动就报 "requires mcdonate-common =…"，
换成同一个包里的 jar 就好。之前的版本（v2.3 及以前）混装不会报，而是跑起来以后 `NoSuchMethodError`、worker 不停断开。

## 3. 配置

```bash
cd deploy
cp .env.example .env
```

打开 `.env` 改：

| 配置项 | 必填 | 含义 |
| --- | --- | --- |
| `EULA` | 是 | 默认 `FALSE`，服务器不会启动。请运行服务器的人自己读 https://aka.ms/MinecraftEULA ，同意后改成 `TRUE` |
| `PUBLIC_HOST` | 是 | 这台服务器的公网 IP 或域名。玩家进服时，服务器把"coordinator 在哪"发给他的客户端，worker 按这个地址连。填错了 worker 连不上 |
| `RCON_PASSWORD` | 是 | 随便一串随机字符（`python3 -c 'import secrets; print(secrets.token_hex(24))'`）。RCON 只在容器里用，端口不对外 |
| `MC_PORT` | 否 | 游戏端口，默认 25565 |
| `COORD_PORT` | 否 | coordinator 端口，默认 25580。玩家电脑上的 worker 连这个 |
| `COORD_PUBLIC_PORT` | 否 | 只有路由器做了端口转发、对外端口和 `COORD_PORT` 不一样时才设 |
| `ONLINE_MODE` | 否 | 正版验证，默认 `TRUE`，正式部署不要关（见第 8 节） |
| `MCD_DEMAND` | 否 | 自动找需求，默认 `on` |
| `MCD_LOOKAHEAD` | 否 | 找需求往外多看几圈，默认 16（离玩家更远就派活，donor 有时间做完） |
| `MCD_FEATURES` | 否 | 调度开关，默认 `territory,deadline,throughput`（逗号分隔，不是加号）：按实测耗时一次给一个 donor 几个任务（donor 几个 tile 同时做）；相邻的 tile 尽量给同一个 donor；按玩家飞行方向先做最急的，来不及导入的不派、排队来不及的提前丢掉、飞过的撤回。可选值见 `docs/config.md`，实测见 `docs/step7-report.md` 开头 |
| `MCD_JVM_EXTRA` | 否 | 其他 coordinator 配置，空格分隔，全部配置项见 `docs/config.md`。模板里是 `-Dmcdonate.demandScanBudgetMicros=2000`（扫描预算，代码默认 500；11-09 同 2 核、1 个 4 核 donor 烟花直飞 A/B，卡住 17.2% → 3.8%），**建议保留**。不要再写一遍上面已有的 `-D`（`demandLookahead`、`features` 等） |
| `MC_VIEW_DISTANCE`、`MC_SIMULATION_DISTANCE` | 否 | 视距和模拟距离，默认 10 和 6 |
| `MC_MAX_MEMORY` | 否 | 服务器堆大小，默认 4G（用 `compose.n100.yaml` 时是 2G，这一项不起作用） |
| `MC_SEED`、`MC_LEVEL` | 否 | 世界的 seed（留空随机）和名字 |

`allowTokenless` 在 compose 里写死为 `false`：只有经游戏发了一次性 token 的 worker 才能连进来，不要改。

**2 核、3 GB 的小机器**：`compose.yaml` 本身不限 CPU 和内存（堆 1G～4G）。`compose.n100.yaml` 把容器限成 2 核、3 GB，堆 512M～2G。
用的时候每条 `docker compose` 命令都带两个 `-f`：

```bash
docker compose -f compose.yaml -f compose.n100.yaml up -d --wait --wait-timeout 600
```

已经用别的办法限了资源（自己写的 compose、宿主机的 cgroup）就沿用原来的命令，不用这个文件。

## 4. 防火墙

两个 TCP 端口都要放行：

| 端口 | 给谁 |
| --- | --- |
| `MC_PORT`（25565） | 玩家的游戏客户端 |
| `COORD_PORT`（25580） | 玩家电脑上的 worker（客户端 mod 起的后台进程） |

比如 ufw：`sudo ufw allow 25565/tcp && sudo ufw allow 25580/tcp`。云服务器还要在控制台的安全组里放行。

## 5. 启动和停止

```bash
cd deploy
docker compose up -d --wait --wait-timeout 600     # 启动（第一次要下载服务器和 Fabric，几分钟）
docker compose ps                                  # 状态
docker compose logs --tail=100 minecraft           # 日志
docker compose stop                                # 停止（存档在 deploy/data/）
docker compose down                                # 停止并删容器（存档还在）
```

用了 `compose.n100.yaml` 的，上面每条命令都在 `docker compose` 后面加 `-f compose.yaml -f compose.n100.yaml`。

起来以后日志里应该有 `mcdonate-coordinator loaded` 和 `coordinator 在端口 25580 监听`。
如果有 `allowTokenless` 的 WARN，说明配置不对。

## 6. 玩家这边

每个想帮忙的玩家：

1. 用 Fabric 安装器（https://fabricmc.net/use/installer/）给 Minecraft 1.21.1 装 Loader 0.16.14。
2. 把 Fabric API 0.116.6+1.21.1（https://modrinth.com/mod/fabric-api/version/0.116.6+1.21.1 ）和
   mc-donate 客户端 mod（`mcdonate-client-0.1.0-<提交号>.jar`，核对 sha256）放进 `.minecraft/mods/`。
3. 用 Fabric 配置启动游戏，多人游戏连 `PUBLIC_HOST:MC_PORT`。
4. 进服后按 Esc，点右下角的 **mc-donate** 按钮 → "帮服务器生成地形：关"。第一次会弹出说明（用几个线程、多少内存、会下载什么），
   勾选"我已阅读并同意 Minecraft EULA"，点"同意并打开"。默认关，不点不会做任何事。
5. 第一次会在后台下载 Minecraft 服务器（约 50 MB，Mojang 官方地址）和 Fabric 的服务器库，状态行显示"第一次准备中…"，
   然后是"worker 在跑"。
6. **帧率低时自动暂停**：最近 10 秒帧时间的中位数超过**帧时间目标**（默认 50 ms）持续 5 秒就暂停 worker，低于目标的 0.8 倍（恢复线，默认 40 ms）持续 5 秒恢复。
   集显、开光影、平时就 40 ms 以上的电脑会**一直暂停、不贡献算力**：设置界面状态行是"worker 在跑（帧率低，暂停中）"，
   算力面板（`/mcdonate hud on`）显示"暂停（帧率限速）"。在设置界面的"帧时间目标"里改成 100 或 200 ms（马上生效）。
7. 游戏内显示：`/mcdonate overlay on` 按 donor 给附近的 chunk 上色，`/mcdonate hud on` 显示自己贡献了多少（线程、CPU、做了多少 tile），`/mcdonate show` 两个都开。小地图中间的白色箭头是你的朝向。

worker 用游戏自带的 Java 21。没装客户端 mod 的玩家照常能玩，只是不帮忙。详细说明和排错见 `docs/client-install.md`。

## 7. 怎么确认 donor 在干活

```bash
cd deploy
docker compose exec -T minecraft rcon-cli donor status               # workers：连着的 worker（name 是玩家电脑的主机名）、done 做完几个 tile；features、config 是生效的配置
docker compose exec -T minecraft rcon-cli donor provenance stats     # donor 写入、被丢弃、导入后服务器接着做完的累计数
docker compose exec -T minecraft rcon-cli donor imports              # 每个导入的 tile：是否被接受、大小、耗时
```

找需求只在离玩家一段距离（视距 + 约 12 圈以外）派活，原地站着基本没有活，要有人在跑图、飞行时才看得到 done 涨。

**游戏内来源上色（`/mcdonate show`）的来源记录**：coordinator 把每个 chunk 是谁做完的记在内存里（开服以来的，每个 chunk 约 12 字节；超过 100 万个时删掉离所有在线玩家都超过 64 个 chunk 的），没人打开 overlay 时也记、只是不发。**服务器重启后清空**：重启以前生成的 chunk、以及开服以前就有的 chunk，在小地图上没颜色（来源不明）。

## 8. 安全注意

- **正版验证要开**（`ONLINE_MODE=TRUE`）。关掉时游戏连接不加密，邀请里的一次性 token 能被同一网络里的人抓到、抢先用掉。
- **coordinator 端口是明文 TCP**：worker 和服务器之间不加密。抓到包的人能看到生成的地形数据，也能抢用 token
  （token 一次性，被抢后玩家的 worker 会被拒，再要一次就行）。以后的工作：加 TLS。
- **只对可信的玩家开放**：donor 交回的地形服务器只做格式和接缝检查，还不验证内容（防作弊是第 8 步，还没做）。
  恶意的 donor 可以交回改过的地形。白名单（`docker compose exec -T minecraft rcon-cli whitelist on`）是简单的办法。
- RCON 端口不对外，只在容器里用。`.env` 有密码，不要提交、不要发给别人。

## 9. 没有 Docker 时（简要）

1. 装 Java 21。用 Fabric 安装器装服务器：Minecraft 1.21.1、Loader 0.16.14（`java -jar fabric-installer.jar server -mcversion 1.21.1 -loader 0.16.14 -downloadMinecraft`）。
2. `mods/` 里放 Fabric API 0.116.6+1.21.1 和 `mcdonate-coordinator-0.1.0.jar`。
3. 第一次启动生成 `eula.txt`，运行服务器的人自己读过 EULA 后改成 `eula=true`；`server.properties` 里 `online-mode=true`。
4. 启动时带上 coordinator 的配置：

   ```bash
   java -Xmx4G -Dmcdonate.publicHost=<公网 IP 或域名> -Dmcdonate.publicPort=25580 \
        -Dmcdonate.demand=on -Dmcdonate.features=territory,deadline,throughput -Dmcdonate.demandLookahead=16 -Dmcdonate.allowTokenless=false \
        -jar fabric-server-launch.jar nogui
   ```
5. 防火墙放行 25565 和 25580（TCP）。`/donor status` 等命令在服务器控制台输入（不带 `/`）。

## 10. 出问题时

服务器：`docker compose logs minecraft > server.log`。玩家：`.minecraft/logs/latest.log` 和 `.minecraft/mc-donate/worker/worker.log`。
日志里不会有 token。把这些发回来。

## 11. 从上一版升级（11-09 第二版 v2.1：worker 代码 14d18f4）

这一版：worker 并行生成不再卡死、worker 重连后马上能领活、续租有上限（120 秒）、导入分摊到多个 tick、
按玩家前方的走廊先派、扫描预算 2000 微秒（`.env` 里打开）。已导入的地形不会因为换版本变化。
v2.1（14d18f4）只改了 worker：同一个 tile 卸载后又派回来时不再失败（"不在内存里"）。coordinator jar 和 v2（f19d087）字节相同，
已经换成 v2 coordinator 的服务器不用再换，只换客户端。

1. **核对 jar**：在包目录里 `sha256sum -c SHA256SUMS`（macOS：`shasum -a 256 -c SHA256SUMS`），两个 jar 都要 OK。
2. **备份**：拷一份现在用的 coordinator jar、客户端 jar、`.env` 和 compose 文件。然后正常停服务器
   （`docker compose stop`，用了 `compose.n100.yaml` 的带两个 `-f`）。只停 Minecraft 这个容器，同一台机器上别的服务照常跑。
3. **换服务器的 mod**：`deploy/mods/` 里删掉旧的 `mcdonate-coordinator-*.jar`，放新的，**只留一份**。
   服务器上不装客户端 jar、worker jar、`mcdonate-bench`。
4. **合并 `.env`**：保留自己的 `PUBLIC_HOST`、端口、`RCON_PASSWORD`、`EULA`、世界名和 seed；对照新的 `.env.example`
   加上 `MCD_JVM_EXTRA=-Dmcdonate.demandScanBudgetMicros=2000`（原来 `MCD_JVM_EXTRA` 里有别的项就用空格接在后面），
   确认 `MCD_FEATURES=territory,deadline,throughput`、`MCD_LOOKAHEAD=16`、`MC_VIEW_DISTANCE=10`、`MC_SIMULATION_DISTANCE=6`。
5. **换客户端**：Mac 上先在游戏里关掉 donor，**完全退出 Minecraft**，再把 mods 目录
   （`~/Library/Application Support/minecraft/mods/`）里旧的 `mcdonate-client-*.jar` 换成新的。Fabric API 0.116.6+1.21.1 不变。
6. **不要删 worker 目录**：`mc-donate/worker/` 里有 worker 的世界和 `mcdonate-world-id`，同一个服务器、同一个 seed 时留着能接着用；
   客户端每次启动会自己把 jar 里带的新 worker mod 覆盖进去。
7. **启动服务器后核对配置**：`docker compose exec -T minecraft rcon-cli donor status`，看 `features` 是 territory、deadline、throughput，
   `config` 里 `demandLookahead` 16、`demandScanBudgetMicros` 2000、`maxLeaseTotalMs` 120000。
8. **玩家进服**：Mac 上开游戏进服，Esc → mc-donate → 打开 donor；聊天框 `/mcdonate hud on` 看 worker 在跑、done 在涨。
9. **试飞**：从已生成区域的边上用烟花鞘翅飞一小段，服务器上看 `donor provenance stats`（donor 写入在涨）和 MSPT（`/tick query` 或日志里有没有 `Can't keep up`）。
   有异常：先关 donor，再按第 2 步的备份换回旧 jar 和 `.env`。注意已经导入的地形不会自动回滚。

