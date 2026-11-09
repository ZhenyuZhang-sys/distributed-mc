# 在自己电脑上当 donor（客户端 mod 试用）

装上 mc-donate 客户端 mod，进测试服务器以后，你的电脑会在后台起一个 Minecraft 服务器进程（worker），帮服务器提前生成地形。
默认关闭，要你本人在游戏里点"同意"（含 Minecraft EULA）才会开始。

> **现状（11-09）**：客户端的各个部分单元测试都过了，但还没有在真客户端上从头到尾跑通过（无头的自动测试还在调）。
> 这次是第一次真机试用，出问题很正常。看下面"出问题时看哪里"，把日志发回来。

## 1. 要准备的东西

| 东西 | 版本 | 从哪里下载 |
| --- | --- | --- |
| Minecraft Java 版 | 1.21.1 | 官方启动器 |
| Fabric Loader | 0.16.14 | https://fabricmc.net/use/installer/ （安装器里选 Minecraft 1.21.1、Loader 0.16.14） |
| Fabric API | 0.116.6+1.21.1 | https://modrinth.com/mod/fabric-api/version/0.116.6+1.21.1 |
| mc-donate 客户端 mod | 0.1.0（提交 4405a55） | 服务器上的 `/home/zhenyu/mc-slots/user-test/client/mcdonate-client-0.1.0-4405a55.jar` |

客户端 mod 的 sha256 在同目录的 `.sha256` 文件里：

```
3408e186b2b308bc40d1e5336b78865b86e4a74f028ef3733dd2d08e3cf40937  mcdonate-client-0.1.0-4405a55.jar
```

worker 用游戏自带的 Java 21，不用另外装 Java。第一次打开时会从 Mojang 官方地址下载 Minecraft 服务器（约 50 MB）、
从 Fabric 官方下载服务器端的库，放在 `.minecraft/mc-donate/worker/`。

## 2. 安装

1. 用 Fabric 安装器给 Minecraft 1.21.1 装上 Loader 0.16.14，启动器里会多一个"fabric-loader-0.16.14-1.21.1"的配置。
2. 把客户端 mod 拷到自己电脑上，核对 sha256：

   ```bash
   scp <服务器>:/home/zhenyu/mc-slots/user-test/client/mcdonate-client-0.1.0-4405a55.jar .
   sha256sum mcdonate-client-0.1.0-4405a55.jar      # macOS：shasum -a 256 …；Windows：certutil -hashfile … SHA256
   ```
3. 把 `mcdonate-client-0.1.0-4405a55.jar` 和 Fabric API 的 jar 放进 `.minecraft/mods/`。

## 3. 连上测试服务器（槽位 u1）

测试服务器只听服务器本机（127.0.0.1），用 ssh 把两个端口转发到你的电脑：游戏端口 25575、coordinator 端口 25585。

```bash
ssh -N -L 25575:127.0.0.1:25575 -L 25585:127.0.0.1:25585 <你在服务器上的用户名>@<服务器地址>
```

这个窗口开着别关。然后启动 Minecraft（Fabric 配置）→ 多人游戏 → 直接连接 → `127.0.0.1:25575`。

- 服务器是离线模式（只在本机、经 ssh 转发才进得去），任何用户名都能进。
- 服务器发给你的 worker 的 coordinator 地址是 `127.0.0.1:25585`，就是你转发过来的那个端口，所以两个端口都要转发。
- 要飞着到处看（产生生成需求），在服务器上给自己开旁观或创造：
  `cd /home/zhenyu/mc-slots/u1 && scripts/rcon.sh gamemode creative <你的用户名>`

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
  算力面板：聊天框输入 `/mcdonate hud on`（`/mcdonate overlay on` 按 donor 给 chunk 上色，`/mcdonate show` 两个都开）。
- **服务器上**（ssh 上去）：

  ```bash
  cd /home/zhenyu/mc-slots/u1
  scripts/rcon.sh donor status                 # workers 里有你的 worker（name 是你电脑的主机名），done 是做完的 tile 数，paused 是不是暂停
  scripts/rcon.sh donor provenance stats       # donor 事件的累计数，随着你飞会涨
  scripts/rcon.sh donor imports                # 每个导入的 tile：bytes（结果大小）、ms（派出到收到）
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
| 状态一直是"等服务器的邀请" | 日志里应该有"收到 donor 邀请：coordinator 127.0.0.1:25585"；没有就是服务器没发（服务器那边没配 publicHost） |
| "准备失败" | 日志里的原因；多半是下载不下来（网络、代理）。`.minecraft/mc-donate/worker/` 删掉重来 |
| worker 起来了但服务器上看不到 | `.minecraft/mc-donate/worker/worker.log`：连不上 127.0.0.1:25585（ssh 转发没开）、token 被拒（会自动再要一次） |
| 游戏卡 | 设置里把线程调到 1，或者关掉 |

把 `.minecraft/logs/latest.log` 和 `.minecraft/mc-donate/worker/worker.log` 发回来就行。日志里不会有 token。

## 7. 已知限制

- **还没在真客户端上跑通过**（见开头）。Linux 上的子进程、低优先级（nice）、父进程退出时 worker 自己退出有单元测试；
  Windows 上用 `start /low` 降低优先级、经标准输入发控制命令都还没测过，可能有问题。
- token 和 worker 的数据走明文 TCP（这里经 ssh 转发，转发这段是加密的）。正式部署见 docs/config.md 的"部署时的安全注意"。
- 限速只按帧时间暂停、恢复；帧时间目标改了马上生效，线程数、内存运行中改不了（下次起 worker 生效）。
