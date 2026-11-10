# 在自己电脑上当 donor（客户端 mod 试用）

装上 mc-donate 客户端 mod，进测试服务器以后，你的电脑会在后台起一个 Minecraft 服务器进程（worker），帮服务器提前生成地形。
默认关闭，要你本人在游戏里点"同意"（含 Minecraft EULA）才会开始。

> **现状（11-09）**：Linux 上的无头真客户端自动测试全过，用户的 Mac 上试过。Windows 没测过。
> 出问题看下面"出问题时看哪里"，把日志发回来。

## 1. 要准备的东西

| 东西 | 版本 | 从哪里下载 |
| --- | --- | --- |
| Minecraft Java 版 | 1.21.1 | 官方启动器 |
| Fabric Loader | 0.16.14 | https://fabricmc.net/use/installer/ （安装器里选 Minecraft 1.21.1、Loader 0.16.14） |
| Fabric API | 0.116.6+1.21.1 | https://modrinth.com/mod/fabric-api/version/0.116.6+1.21.1 |
| mc-donate 客户端 mod | 0.1.0（第二版 v2.1，提交 14d18f4） | 服务器管理者给的包：`mcdonate-client-0.1.0-14d18f4.jar`（开发机上在 `/home/zhenyu/mc-slots/user-test/deploy/v2.1-14d18f4/`） |

客户端 jar 里已经带了 worker mod，不用另装。sha256 在包里的 `SHA256SUMS`。

worker 用游戏自带的 Java 21，不用另外装 Java。第一次打开时会从 Mojang 官方地址下载 Minecraft 服务器（约 50 MB）、
从 Fabric 官方下载服务器端的库，放在 `.minecraft/mc-donate/worker/`（Mac 上是 `~/Library/Application Support/minecraft/mc-donate/worker/`）。

## 2. 安装

1. 用 Fabric 安装器给 Minecraft 1.21.1 装上 Loader 0.16.14，启动器里会多一个"fabric-loader-0.16.14-1.21.1"的配置。
2. 拿到包以后在包目录里核对 sha256：

   ```bash
   sha256sum -c SHA256SUMS            # macOS：shasum -a 256 -c SHA256SUMS；Windows：certutil -hashfile <jar> SHA256，和 SHA256SUMS 对一下
   ```
3. 把 `mcdonate-client-0.1.0-14d18f4.jar` 和 Fabric API 的 jar 放进 mods 目录：
   Mac 是 `~/Library/Application Support/minecraft/mods/`，Windows 是 `%APPDATA%\.minecraft\mods\`，Linux 是 `~/.minecraft/mods/`。
   **mods 里只留一个 `mcdonate-client-*.jar`**，旧版本删掉。

**从上一版升级**：先在游戏里关 donor，完全退出 Minecraft，再换 mods 里的 jar。**不要删** `mc-donate/worker/`：
里面有 worker 的世界和 `mcdonate-world-id`，同一个服务器、同一个 seed 时留着能接着用；客户端每次启动会自己把 jar 里带的新 worker mod 覆盖进去。

## 3. 连上服务器

启动 Minecraft（Fabric 配置）→ 多人游戏 → 连服务器管理者给的地址（游戏端口，默认 25565）。

- 服务器会把 coordinator 的地址（默认端口 25580）发给你的 worker，worker 自己去连，不用手动填。
  服务器管理者已经用了别的端口的，照他给的来。
- 两个端口都是服务器那边放行的，你这边不用开端口。
- 要产生生成需求就得往没去过的地方跑：烟花鞘翅、创造模式飞行。

## 4. 打开 donor

1. 进服以后按 Esc，右下角有个 **mc-donate** 按钮，点开是设置界面。
2. 点"帮服务器生成地形：关"。第一次会弹出说明：会用几个线程、多少内存、会下载什么。
   勾选"我已阅读并同意 Minecraft EULA"（https://aka.ms/MinecraftEULA），再点"同意并打开"。
3. 第一次会在后台下载服务器（约 50 MB），设置界面最下面一行显示状态："第一次准备中…" → "worker 在跑"。
4. 设置界面里可以改生成线程（默认 2）、内存上限（默认 2048 MB），下次起 worker 时生效。
5. 关掉：设置里点开关；退出世界、关游戏也会停 worker（游戏被强制结束时，worker 几秒内自己退出）。

同意记录在 `.minecraft/config/mc-donate.json`，设置在 `.minecraft/config/mc-donate-settings.properties`。删掉同意记录就回到"没同意"。

## 5. 怎么确认自己的 donor 在干活

- **游戏里**：设置界面最下面一行是"worker 在跑"；帧率低时会变成"worker 在跑（帧率低，暂停中）"，见下面"帧率低时会自动暂停"。
  算力面板：聊天框输入 `/mcdonate hud on`（`/mcdonate overlay on` 按 donor 给 chunk 上色，`/mcdonate show` 两个都开）。小地图中间的白色箭头是你的朝向（无头测试看不到画面，请在真机上看一眼方向对不对）。
- **服务器上**（服务器管理者在 `deploy/` 目录里，命令见 `docs/deploy.md` 第 7 节）：

  ```bash
  docker compose exec -T minecraft rcon-cli donor status            # workers 里有你的 worker（name 是你电脑的主机名），done 是做完的 tile 数，paused 是不是暂停
  docker compose exec -T minecraft rcon-cli donor provenance stats  # donor 事件的累计数，随着你飞会涨
  docker compose exec -T minecraft rcon-cli donor imports           # 每个导入的 tile：bytes（结果大小）、ms（派出到收到）
  ```
- 实时地图（第 4 步的 bench.map）现在只画测试飞行记录下来的数据，还不能看真人玩家的会话；要看的话请调度安排。

找需求只在离你一段距离（视距 + 约 12 圈以外）的地方派活，原地站着基本没有活；要飞起来（创造模式飞行、鞘翅）才会看到 done 涨。

### 帧率低时会自动暂停（集显、开光影的电脑要看这一节）

worker 会抢你电脑的 CPU。为了不让游戏卡，客户端一直看最近 10 秒帧时间的中位数（一半的帧比它快、一半比它慢；加载 chunk 那几秒的掉帧不会把它拉高）：

- 超过**帧时间目标**持续 5 秒：暂停 worker（不再领新活，手上的做完）。
- 低于目标的 **0.8 倍（恢复线）**持续 5 秒：恢复。
- 默认目标 50 ms（20 帧/秒），恢复线 40 ms。

如果你的电脑平时就在 40 ms 以上（集成显卡、开着光影、高视距），worker 会**一直暂停、一点活都不干**。看得到：
设置界面最下面一行是"worker 在跑（帧率低，暂停中）"；打开算力面板（聊天框输入 `/mcdonate hud on`）会显示"暂停（帧率限速）"。

调法：按 Esc → **mc-donate** → **帧时间目标**，可选 33 / 50 / 100 / 200 ms（按钮上写着对应的恢复线），改了马上生效，不用重启。
平时 30 帧左右的电脑选 100 ms（恢复线 80 ms）；不在乎游戏卡一点、想多帮忙选 200 ms。想游戏更流畅就选 33 ms（更容易暂停）。

## 6. 出问题时看哪里

| 现象 | 看哪里 |
| --- | --- |
| 没有 mc-donate 按钮 | `.minecraft/logs/latest.log` 里搜 `mcdonate`：应该有 `mcdonate-client loaded`；没有说明 mod 没加载（Fabric API 没装、Loader 版本不对） |
| 状态一直是"等服务器的邀请" | 日志里应该有"收到 donor 邀请：coordinator <地址>:25580"；没有就是服务器没发（服务器那边没配 publicHost） |
| "准备失败" | 日志里的原因；多半是下载不下来（网络、代理）。修好网络后关掉 donor 再打开重试。**不要删整个 `mc-donate/worker/`**（里面的世界和 `mcdonate-world-id` 删了要重新生成）；实在要重来，先把日志发回来 |
| worker 起来了但服务器上看不到 | `mc-donate/worker/worker.log`：连不上 coordinator（服务器没放行 25580、`PUBLIC_HOST` 填错）、token 被拒（会自动再要一次） |
| 游戏卡 | 设置里把线程调到 1，或者关掉 |
| "有残留 worker 占着世界锁" / "有残留 worker 占用此目录（PID …）" | 上一次的 worker 进程没退出（旧版本的客户端有这个问题：游戏关了 worker 还在跑）。mod 不会自己去杀它：退出游戏，在活动监视器（Mac）/ 任务管理器（Windows）/ `ps`（Linux）里找命令行带 `fabric-server-launch.jar` 和 `mcdonate.parentPid` 的 java 进程结束掉，再开游戏。**不要删 `session.lock`**。新版本的 worker 在游戏没了、关掉 donor、被要求停的时候最多 5 秒就退出 |

把 `.minecraft/logs/latest.log` 和 `.minecraft/mc-donate/worker/worker.log` 发回来就行。日志里不会有 token。

## 7. 已知限制

- Linux 上的子进程、低优先级（nice）、父进程退出时 worker 自己退出有单元测试和无头真客户端测试；
  Windows 上用 `start /low` 降低优先级、经标准输入发控制命令都还没测过，可能有问题。
- token 和 worker 的数据走明文 TCP。安全注意见 `docs/deploy.md` 第 8 节、`docs/config.md` 的"部署时的安全注意"。
- 限速只按帧时间暂停、恢复；帧时间目标改了马上生效，线程数、内存运行中改不了（下次起 worker 生效）。
