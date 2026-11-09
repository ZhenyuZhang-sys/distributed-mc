# 游戏内来源上色和算力面板（overlay / hud）计划

状态：**计划待审**（调度 11-09 安排，用户要的新功能）。负责人 kexu（客户端、common、发送器、测试）；fish 加 worker 状态字段和两个小接口（第 6 节）。在演示矩阵 demo-v2 跑完、演示默认挑好以后开工。

## 1. 要什么

玩家在自己的客户端里看到"mod 在干活"：

- **上色模式（overlay）**：按"谁做完的"给 chunk 上色。四类：服务器自己生成、某个 donor 生成（每个 donor 一种颜色，带玩家名或主机名）、donor 导入后服务器接着做完（RESUMED）、被打回。两种画法：地面上按来源着色的 chunk 边框（像 F3+G，但带颜色）；屏幕角落一张以玩家为中心的小地图，带颜色图例。实时刷新。
- **算力面板（hud）**：自己的 worker 用几个线程、进程 CPU、内存；自己累计做了多少 tile、多少 chunk、多少字节；worker 状态（在跑 / 暂停 / 关）；服务器整体：在线 donor 数、你的贡献占比。每秒刷新。
- **命令**（Fabric 客户端命令，只在自己的客户端生效）：`/mcdonate show`（两样一起开关）、`/mcdonate overlay on|off`、`/mcdonate hud on|off`。状态存进客户端设置文件。

## 2. 数据从哪来

| 显示的东西 | 来源 | 怎么到客户端 |
| --- | --- | --- |
| chunk 来源（四类 + donor 是谁） | 服务器：common 的 `Provenance` 事件（DONOR、REJECTED 由 coordinator 报；VANILLA、RESUMED、LOADED 由 chunk 变成完整状态时的钩子报），加上 coordinator 的导入账本（启动以前导入的 chunk） | 新的游戏内 payload，只发给订阅了的客户端（第 4 节） |
| donor 的名字 | coordinator 的 worker 注册表：worker 编号 → 玩家 UUID（第 9 步）或主机名 | 同一个 payload 里的 donor 表 |
| 在线 donor 数、你的贡献占比 | coordinator：在线 worker 数；这次开服以来按玩家 UUID 计的导入 chunk 数 ÷ 全部导入数 | payload 里的摘要（只在 hud 开着时发） |
| 自己 worker 的线程、CPU、内存、累计 tile / chunk / 字节、状态 | worker 进程自己报：本地控制通道已有的 `STATUS`（fish 在 JSON 里加字段，第 6 节） | 客户端每秒往 worker 标准输入写一行 `STATUS`，读回 `MCDONATE STATUS {...}` |

### 2.1 部署时没有 bench：VANILLA、RESUMED 谁来报

现在"服务器自己生成"（VANILLA）和"导入后服务器接着做完"（RESUMED）是 bench mod 的 `ChunkStatusTasksMixin` 报的，分辨"读盘的半成品"还要 bench 的 `FromDisk` 标记（`ProtoChunkMixin`、`ChunkSerializerMixin`）。正式部署（deploy/compose.yaml）不装 bench，overlay 就只看得到 DONOR 和 REJECTED。

**办法：把"chunk 变成完整状态"这一处挪进 common**，common 是 coordinator、bench、worker、客户端都内嵌的库（jar-in-jar，Loader 按 mod id 只加载一份，见第 4 步计划 3.3）：

- common 加一个小的 mixin 配置（`mcdonate-common.mixins.json`）：`FromDisk` 标记的两个 mixin 原样搬过来；`ChunkStatusTasks.full` 开头一个注入，按"成品读盘 / 半成品读盘接着做完 / 从头生成"分三类，调 common 新类 `FullStatus.onFull(kind, cx, cz)`。只算主世界，和现在一样。
- `FullStatus` 就是现在 bench 的 `Generation` 搬进 common：读写锁里先通知计数监听者、再发 `Provenance` 事件，`listen` / `unlisten` 拿写锁读快照。bench 的 `Counters` 注册成计数监听者，`Generation` 变成对 `FullStatus` 的薄包装，**第 4 步"飞行期间来源事件数严格等于计数增量"的保证不变**（JUnit 4 线程并发那条照跑）。
- bench 的 `ChunkStatusTasksMixin` 去掉 `full` 那个注入（`generateNoise`、`generateFeatures` 计数留着）。coordinator 的 `ChunkStatusTasksFullMixin`（fish 的首次加载钩子）不动，两个 HEAD 注入互不影响。
- worker 和客户端里这个 mixin 也会生效：没有监听者时 `Provenance.report` 什么都不做（现在就是这样），开销是每个 chunk 一次 instanceof 判断。
- **要和 fish 对齐**：`ChunkStatusTasksMixin`、`Counters` 是两个人都改过的文件；`FromDisk` 搬家后 fish 的代码里如果引用了 `mcdonate.bench.FromDisk` 要跟着改（现在 coordinator 没有引用）。先写的测试：GameTest 在没有 bench 的条件下（只看 common 的监听）也能收到 VANILLA；第 4 步 ProvenanceGameTest、GenerationTest 原样通过。

## 3. 颜色

- 四类的底色沿用实时地图（`bench/map/server.py`）：服务器自己生成灰绿 `#5b8c5a`、导入后接着做完灰紫 `#7d6b9e`、被打回红 `#d62728`、读盘或来源不明不画（透明）。
- **每个 donor 一种颜色，而且每次都一样**：颜色由 donor 的**稳定名字**算，不由 worker 编号算（worker 每次重连编号都会变）。稳定名字 = 玩家 UUID（第 9 步的玩家 donor），没有就用 donor 世界编号或主机名。算法：名字的 SHA-256 取前 4 字节 → 色相 = 值 × 黄金角 mod 360，饱和度 0.75、亮度 0.55（和地图一样）；离四类底色太近（色相差 < 20°）就再加一个黄金角。纯函数，客户端和服务器都能算，测试里固定几个名字 → 固定颜色。
- 图例：四类各一行，donor 每人一行（颜色块 + 名字，最多 8 行，更多的折成"其他 N 个 donor"）。

## 4. 服务器 → 客户端的 payload

common 里新文件 `mcdonate.common.net.ProvenanceUpdate`（S2C，频道 `mcdonate:provenance`）和 `OverlaySubscribe`（C2S，频道 `mcdonate:overlay`），照 `DonorInvite` 的写法：两边在 `PayloadTypeRegistry` 登记；带格式版本；解码不抛异常（抛了会把玩家踢下线），坏数据得到 `valid() == false` 的对象，收到的一方丢掉；所有长度有上限。

**订阅**（C2S `OverlaySubscribe`）：`version`、`flags`（bit0 = overlay，bit1 = hud）。客户端开关 overlay / hud 时发一次，进服时按设置发一次。服务器只给订阅了、而且 Fabric `canSend(player, TYPE)` 为真的玩家发；原版客户端不注册频道，收不到也不会订阅。**两样都关时服务器什么都不发、也不维护这个玩家的状态。**

**更新**（S2C `ProvenanceUpdate`）：

| 字段 | 编码 | 说明 |
| --- | --- | --- |
| version | varint | 格式版本，现在 1 |
| full | bool | true = 以玩家为中心的整片快照（订阅时、跨维度、跳得太远时）；false = 增量 |
| centerX, centerZ | varint | 这一包的中心 chunk，坐标按它的差值编 |
| donors | varint n + n ×（varint 索引，string ≤ 32 名字，string ≤ 36 稳定名字） | 这一包用到、客户端还没见过的 donor |
| chunks | varint n + n ×（zigzag varint dx、dz，byte 类别，varint donor 索引，只在 DONOR 时有） | n ≤ 2048，多了分几包 |
| summary | 可选（hud 开着时）：在线 donor 数、这次开服以来导入 chunk 总数、这个玩家的 donor 导入数 | 每秒最多一次 |

- **范围**：玩家视距内（服务器视距，默认 10，最多 32）的方形。
- **增量**：服务器端每个订阅的玩家有一个"已发送"的集合；来源变化（Provenance 事件）只标脏，每秒（每 20 tick）收一次脏 chunk，落在玩家范围里的打包发出。玩家走动，进入范围的新 chunk 也算进下一包。
- **来源状态表**（服务器端，`OverlayState`）：chunk → (类别, donor 索引)，`Long2IntOpenHashMap`（Minecraft 自带的 fastutil）。只记这次开服以来有事件的 chunk；没有事件的，第一次要发时查导入账本（`Ownership.owner`，donor 世界 → 稳定名字），查不到就是"来源不明"不发。
- **接法**：`OverlaySender` 在 `Provenance` 上挂一个监听者（fish 的提议），coordinator 的调用点不用改；发送和订阅的注册在 coordinator 初始化时调一次 `OverlaySender.install(server)`（一行，请 fish 加，第 6 节）。新文件都放在 `coordinator/src/main/java/mcdonate/coordinator/overlay/`，是我的。

## 5. 客户端

新文件都在 `client/src/main/java/mcdonate/client/overlay/`：

- `OverlaySettings`：overlay、hud 两个开关，存进现有的 `mc-donate-settings.properties`（加两项，读不懂用默认值：都关）。改 `ClientSettings` 是 fish 的文件，加两个字段请 fish 同意，或者单独一个 `mc-donate-overlay.properties`（倾向后者，不碰 fish 的文件）。
- `OverlayCommands`：`ClientCommandRegistrationCallback` 注册 `/mcdonate show | overlay on|off | hud on|off`；改设置、存盘、给服务器发 `OverlaySubscribe`；在聊天栏回一句现在的状态。
- `ChunkSources`：收 `ProvenanceUpdate`，维护玩家附近 chunk → 颜色的表；overlay 关时清空、不收（服务器也不再发）。
- `ChunkBorderRenderer`：`WorldRenderEvents.AFTER_TRANSLUCENT`，画玩家周围 8 个 chunk 以内的来源边框（每个 chunk 四条竖线 + 地面一圈，颜色按来源，线宽 2）。顶点表只在来源表变化或玩家跨 chunk 时重建，平时每帧只提交现成的。
- `MiniMap`：`HudRenderCallback`，右上角 128×128 像素，以玩家为中心、每个 chunk 一格；画在一张 `DynamicTexture` 上，只在来源表变化或玩家跨 chunk 时重画贴图，每帧只贴一次图；玩家朝向画一个小箭头；下面是图例。
- `ComputeHud`：`HudRenderCallback`，左上角几行字：worker 状态、线程、CPU、内存、累计 tile / chunk / 字节、在线 donor 数、你的贡献占比。worker 数据来自 `DonorController` 每秒发的 `STATUS`（现在 fish 的控制器在需要时发，我这边加一个每秒的轮询，只在 hud 开着时）。

## 6. 要 fish 做的（调度协调）

1. **worker `STATUS` 的 JSON 加字段**（字段名先定好）：

   | 字段 | 类型 | 来源 |
   | --- | --- | --- |
   | `threads` | int | worker 设置的生成线程数（`max.bg.threads` 或实际的生成线程池大小） |
   | `cpuLoad` | double 0～1 | `com.sun.management.OperatingSystemMXBean.getProcessCpuLoad()`（整台机器所有核的比例；拿不到是 -1） |
   | `cpus` | int | `Runtime.availableProcessors()`，客户端用它把 `cpuLoad` 换成"几个核" |
   | `heapUsedMb`、`heapMaxMb` | int | `Runtime.totalMemory() - freeMemory()`、`maxMemory()` |
   | `tiles`、`chunks`、`bytes` | long | 这次启动以来交回去的 tile 数、chunk 数、结果的线上字节数（交回成功才算） |
   | `state` | string | `running` / `paused` / `stopping`（现有的 `paused` 布尔保留） |

   都不读 `/proc`，Windows 上一样能用。
2. **coordinator 初始化时调一次 `OverlaySender.install(server)`**（一行）。
3. **给一个查 donor 名字的小接口**：worker 编号 → (稳定名字, 显示名)：有玩家 UUID 的用 UUID 和玩家名（玩家在线时从玩家列表拿名字，不在线用 UUID 前 8 位），没有的用 donor 世界编号和主机名。`OverlaySender` 收到 DONOR 事件时调它。
4. **`ChunkStatusTasksMixin` 的 full 注入挪进 common**（第 2.1 节）要两个人对齐，我来做，改完给 fish 看。

## 7. 性能估算

- **都关着**：服务器不发、不维护任何玩家状态；`OverlaySender` 的 `Provenance` 监听者看到没有订阅者就直接返回（一次 volatile 读）。客户端不收、不算、不画。和现在一样。
- **服务器，开着**：每个来源事件一次哈希表写入（几十纳秒）。鞘翅飞行时每秒约 10～40 个 chunk 有事件（第 4 步 2 核鞘翅每轮约 1000 个原版 + donor 几百个，120 秒）。每秒每个订阅的玩家：扫一遍脏集合（几十个）、按范围过滤、编码，几十微秒，在主线程上。来源状态表：每个 chunk 12 字节左右，开服以来 10 万个 chunk 约 2～3 MB。
- **带宽**：快照一次，视距 10 是 21² = 441 个 chunk × 约 4 字节 ≈ 2 KB（视距 32 也只有约 17 KB）；增量每秒约 10～40 个 × 4 字节 + 包头 ≈ 0.1～0.2 KB/s。摘要每秒约 20 字节。donor 名字每人只发一次。
- **客户端，开着**：收包和更新表每秒一次，微秒级。小地图：只在变化时重画 128×128 贴图（约 1 万像素，零点几毫秒），每帧一次贴图。边框：289 个 chunk × 约 12 条线 ≈ 3500 条线段，顶点表变化时重建，每帧提交一次，和 F3+G 同量级。HUD：几行字。合计每帧远小于 1 毫秒。
- **worker 轮询**：每秒一行 `STATUS`，worker 那边调 MXBean 和 Runtime，微秒级。

## 8. 测试（先写）

| 层 | 测什么 | 放哪 |
| --- | --- | --- |
| JUnit（common） | `ProvenanceUpdate`、`OverlaySubscribe` 编解码往返；坏数据（版本不对、长度超限、截断、乱码）解码不抛异常、`valid()` 为 false；分包后每包 ≤ 2048 条 | common/src/test |
| JUnit（common） | `FullStatus`：并发上报时监听期间的事件数等于计数增量（GenerationTest 搬过来）；没有监听者时只计数 | common/src/test |
| JUnit（common 或 client） | 颜色：同一个稳定名字每次同一种颜色（固定几个名字 → 固定颜色）；和四类底色的色相差 ≥ 20°；worker 编号变了颜色不变 | common/src/test |
| JUnit（coordinator） | `OverlaySender` 的纯逻辑：没订阅不发；订阅时发整片快照；之后只发范围内的脏 chunk；走出范围的不发；取消订阅后不再发；donor 表每个名字只发一次；摘要只在 hud 开着时有 | coordinator/src/test |
| JUnit（client） | 命令改设置并存盘、`show` 两样一起开关、重开读回；`ChunkSources` 按快照和增量更新、关掉后清空；HUD 的每一行来自 worker 的 STATUS JSON（缺字段写"—"，cpuLoad × cpus 换成核数） | client/src/test |
| GameTest | 只用 common 的监听（不靠 bench 的 Generation）也能收到强制生成的 chunk 的 VANILLA；第 4 步的 ProvenanceGameTest 原样过 | bench gametest |
| 端到端（无头真客户端，fish 第 9 步的 `tests/step9/headless_client.py`） | 测试钩子在进服后执行 `/mcdonate overlay on`：客户端日志里出现"收到来源快照 N 个 chunk"、之后有增量；执行 `/mcdonate overlay off` 以后服务器那边对这个玩家的发送计数不再增加；原版客户端（9.1 的 mineflayer）进服不报错、收不到 | tests/step10（或 step9 旁边） |
| 渲染冒烟 | 无头客户端开着 overlay 和 hud 跑 30 秒不崩、日志没有渲染异常；截图只作附加证据，不判 | 同上 |

## 9. 估计时间

| 部分 | 估计 |
| --- | --- |
| common：两个 payload、颜色、`FullStatus` 和 mixin 搬家（含 bench 改成复用、GameTest） | 3～4 小时 |
| coordinator：`OverlayState`、`OverlaySender`、订阅、摘要 | 2～3 小时 |
| client：设置、命令、`ChunkSources`、边框、小地图、HUD | 4～5 小时 |
| 端到端测试（无头客户端加测试钩子） | 2～3 小时（第一次下载客户端慢，fish 那边已经有缓存卷） |
| 文档（docs/demo.md 加一节、演示报告提一句） | 0.5 小时 |
| **合计** | **约 12～15 小时**，fish 那边 worker 字段和两个接口约 1～2 小时，可以并行 |

## 10. 风险

- **mixin 搬家碰到 fish 的代码**：两个人都改过 `ChunkStatusTasksMixin`。先和 fish 对齐，改完第 4 步、第 6 步的相关测试都要过。
- **客户端渲染 API**：1.21.1 的 `WorldRenderEvents` 和 `RenderSystem` 用法要对着映射查；渲染只做冒烟测试，画得对不对靠截图人看。
- **来源不明的旧 chunk**：开服以前就有的、不是 donor 导入的 chunk，overlay 不知道是谁生成的，不画（图例里写"没颜色 = 来源不明，比如开服前就有的"）。
- **无头客户端的命令**：测试钩子要能在客户端执行客户端命令；fish 的 `TestConsentDriver` 已经能在进服后做操作，照它加。
