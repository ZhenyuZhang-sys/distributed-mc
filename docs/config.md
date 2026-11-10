# 配置项

和机器有关的数都做成配置项。开发机上测出来的只是起点，部署到真正的服务器（N100）和玩家电脑上再调。

## 怎么设

优先级从高到低：

1. JVM 参数 `-Dmcdonate.<名字>=<值>`（测试用 `JVM_OPTS` 传，见 `docker/compose.test.yaml`）。
2. 配置文件：服务器目录下的 `config/mcdonate.properties`（Docker 里是 `/data/config/mcdonate.properties`），一行一个 `名字 = 值`。路径可用 `-Dmcdonate.config=...` 换。
3. 代码里的默认值（下表）。

启动时读一次，改了要重启。数字可以写下划线（`30_000`）；写错（不是数字）启动直接报错，不会悄悄用默认值。

**当前生效的值**：`/donor status` 输出里的 `config`。

**测试环境里临时改**：环境变量 `MCD_JVM_EXTRA` 会加进测试服务器和两个 donor 的 JVM 参数（`docker/compose.test.yaml`），
比如 `MCD_JVM_EXTRA="-Dmcdonate.tileSize=8 -Dmcdonate.demandLookahead=16" scripts/test.sh step7 ...`。
tile 大小这类两边必须一样的配置，用它保证服务器和 donor 拿到同一个值。

## 服务器（coordinator）

| 名字 | 默认 | 含义 | 部署时怎么调 |
| --- | --- | --- | --- |
| `tileSize` | 4 | 一个 tile 是几×几个 chunk（donor 另外多导出外面一圈） | **donor 和服务器必须一样**，对不上服务器会拒收。大了每个任务更久、丢弃一次损失更大；小了任务多、网络和导入开销占比高。按 donor 做一个 tile 的耗时在 2～5 秒之间选 |
| `demandLookahead` | 8 | 找需求时 inner 往外再看几圈（outer = inner + 这个数） | 窗口 ≈ 提前量 ÷ 玩家速度。玩家快（鞘翅）、donor 慢时调大；调大后队列里离玩家远的 tile 变多，可能白做 |
| `demandInnerMargin` | 1 | inner = 视距 + 原版占内存的圈数（约 11）+ 这个数 | 看 `/donor status` 的 `discarded.late`：来晚了的多，说明 inner 太靠里，调大 |
| `demandQueueCap` | 256 | 找需求时队列里最多放几个 tile | 玩家多时调大 |
| `demandReadsPerScan` | 256 | 每次找需求最多发起几次读存档（查 chunk 在存档里的状态） | 刚开始或玩家传送后，周围几千个 chunk 状态未知；调大会让那几次扫描变慢，调小要多扫几次才能派活 |
| `demandScanBudgetMicros` | 500 | 每次找需求最多占主线程多少微秒，没查完的 tile 下一次再查（近的先查） | **deploy/ 的 `.env.example` 设 2000**（11-09 codex-server 同 2 核、1 个 4 核 donor 烟花直飞 A/B：卡住 17.2% → 3.8%，前方就绪 21 → 50 格；转弯 22.3% → 11.7%，1 轮）。服务器 MSPT 紧时调小；调小后离玩家远的 tile 要多等几秒才入队 |
| `scanTicks` | 20 | 每隔几个 tick 找一次需求（20 = 1 秒） | `scan` 里扫描耗时大、服务器 MSPT 紧时调大 |
| `leaseMs` | 30000 | 任务租约，超时收回重派 | 比最慢的 donor 做一个 tile 的时间长几倍 |
| `heartbeatMs` | 5000 | 心跳间隔（donor 端同名配置也要一致） | 一般不动 |
| `deadMs` | 15000 | 多久没收到消息判 donor 断线 | 至少 3 个心跳 |
| `maxAttempts` | 3 | 一个任务最多派几次，之后交还服务器自己生成 | 一般不动 |
| `orphanMs` | 60000 | 只能给某个 donor 世界做的任务，那个世界的 donor 都不在多久后丢掉 | |
| `maxConcurrency` | 4 | 一个 donor 最多同时领几个任务 | donor 核多、网络延迟大时调大 |
| `tileDefaultMs` | 3000 | 没测过的 donor 预计做一个 tile 要多久 | 用部署机器上 donor 的实测平均值 |
| `throughputWindow` | 10 | donor 耗时按最近几个任务平均 | |
| `motionWindowMs` | 1500 | 估玩家速度用最近多久的位置 | |
| `motionShortWindowMs` | 750 | 转弯：短窗口估的方向（新方向） | 截止时间、离飞行线距离取短、长两个方向里小的（转弯时新旧路线的并集；11-09 kexu：转弯时 donor 没帮上） |
| `motionLongWindowMs` | 3000 | 转弯：长窗口估的方向（旧方向） | 同上 |
| `motionHorizonS` | 120 | 按玩家速度往前预测多少秒 | |
| `importFrontier` | true | 开 deadline 时实测服务器前沿（玩家前进方向上最远有 holder 的圈数，/donor status 的 scan.frontier），按它算每个 tile 的 importBy；派之前问 donor 能不能在 importBy 之前做完，做不完的不派，importBy 过了的丢掉 | 11-09 kexu 演示里前沿约 14 圈，比 inner（22）近 8 圈。false 退回只按 due（玩家看到的时间）|
| `importMarginMs` | 1000 | importBy 前面再留多少毫秒（结果在主线程排队、前沿每秒才测一次） | 看 /donor imports 的 seg.main_wait |
| `corridorChunks` | 6 | 开 deadline 时，tile 中心离飞行线不超过这么多 chunk 的先派（前方走廊），其余后派 | 11-09 kexu 烟花直线：donor 写入只有 26% 在前方；服务器只有 2 核时 donor 要先包下路线 |
| `territoryBlock` | 0（关） | **实验性，A/B 未完成**（11-10 收尾：kexu 两 donor A/B、codex-audit 复核没做完）。设成正数时，开 territory 时领地按这么多 × 这么多个 tile 的块认主（0 是不按块，只按相邻 tile）：第一次从某块领 tile 的 donor 拿下整块；还有时间的任务（离截止超过 territoryUrgentMs）别的 donor 不进这块，donor 先做自己的块，再挨着自己的空块，再不挨着别人的空块，最后才是挨着别人的。真实导入的相邻归属照旧优先（夹在两个 donor 中间的给服务器）。reset、donor 断开（同世界没别人在线）放块 | 11-10 用户演示两人一起飞（M5 + Windows，1 核）："两人一起跑前面一段比一个人还卡"：两个 donor 交错领 tile，接缝上的 tile 全落给服务器。两 donor 仿真（快 0.7 秒、慢 3 秒）：走廊 24×4 落给服务器 24→9 个，手动一批 12×12 23→13 个；codex-audit 路线模型按时完成 4 比 8 好（沿块边界飞 186 对 144）。**块 = 4 时路线模型有收益**，要用就设 `-Dmcdonate.territoryBlock=4` |
| `territoryUrgentMs` | 4000 | 离截止不到这么多毫秒的任务算紧的：只按截止先后派，谁能做谁做（别人的块也能领），不按块 | codex-audit 第二十三节 T1/T2：块被慢 donor 或只做后台的 donor 占着时前方紧任务没人领、剪枝却按快 donor 估；块优先压过截止 |
| `demandForwardFirst` | 1（11-09 起） | 1：找需求时每个玩家的候选先扫前方走廊里的（在飞行方向前面、离飞行线不超过 corridorChunks），再按远近扫其余的。前方组的时间片和读盘额度按玩家平分（一共一半）。不记游标，每次扫描从头来。/donor status 的 `scan.forward` 报前方组候选数（`candidates` 最近一次、`avgCandidates` 平均）和看完的比例（`visitedRatio`），关着也算 | 11-09 直线 A/B 用：直飞时前方空白大多在 chunk 自己 FULL 之前，待齐邻居里 39% 的 tile 整轮没派过（codex-pipe）；扫描按远近、预算用完就停，正前方远处的轮不到。A/B 后默认开：kexu 1×8 三轮前方空白中位 4.6→4.2 秒、前方就绪 57→62 格；codex-bench ABABAB 3 对 43.2→47.4 格；MSPT、扫描 CPU 基本不变。0 关（对照用） |
| `playerTicketThrottle` | 不改（原版 4） | 原版 DistanceManager 的玩家票节流（ticketThrottler）同时在途的名额：每张玩家票等这个 chunk 能 entity tick（5×5 都 FULL）才放名额。配了就换成这个数（mixin，每个维度都改）。不改票的级别和占用范围，不碰接缝规则；原版自己的生成也跟着放宽。/donor status 的 `playerTicketThrottle.actual` 读回各维度实际的值 | 11-09 直线 A/B 用（4/8/16）：烟花直飞每秒约 44 个新 chunk 进视野，4 个名额约 20～27 个/秒，donor 早就导入好的 chunk 也要排几秒队（codex-server：进视距→票够 p50 3.5 秒，拿到票到 FULL 0.15 秒）。看前方空白时长和 MSPT p95/max 再定 |
| `backgroundRadius` | 48 | 加速模式（11-09）：后台 tile 离玩家（或预设中心）最多几圈 chunk；找需求的 outer 圈以内的照旧归找需求。0 关后台扫描 | 只在有开了加速、没暂停的 donor 连着时扫 |
| `backgroundQueueCap` | 64 | 加速模式：最多排着几个后台 tile | |
| `backgroundScanBudgetMicros` | 300 | 加速模式：后台扫描每次最多占主线程多久（在找需求之后，单独算） | |
| `backgroundReadsPerScan` | 64 | 加速模式：后台扫描每次最多发起几次读存档 | |
| `backgroundCenter` | 空 | 加速模式：预设中心（chunk 坐标 `x,z`）：没有玩家在附近也从这里一圈一圈往外做 | 比如出生点附近先铺开 |
| `backgroundImportMaxMspt` | 30 | 加速模式：服务器平均 tick 超过这么多毫秒就不导后台结果（codex-audit 第十七节 B1：一个 tile 在 2 核上导入要 42～72 ms） | 后台结果和前方结果分两个队列，前方的先导 |
| `backgroundImportEveryTicks` | 4 | 加速模式：后台结果最多每这么多 tick 导一个，而且只在那个 tick 没导前方的、前方没排着的时候 | |
| `backgroundPendingCap` | 4 | 加速模式：等着导入的后台结果有这么多个时不再派新的后台任务 | 背压 |
| `boostMaxInFlight` | 8 | 加速模式：开了加速的 donor 在途上限（按它报的生成线程数 ÷ `boostInFlightDivisor`，不比不开时少），lanes 往上探也到这里为止 | |
| `boostInFlightDivisor` | 1 | 加速模式：在途上限 = 线程数 ÷ 这个数 | |
| `importTrustDisk` | false | **只给演示服务器用，正式服不开**：导入时不读存档查旧状态（主线程上 36 次读盘），只看内存（照样拒收）和导入账本（服务器接手过的当服务器的；我们写的核心不被外圈覆盖；没有记录的当空的直接写） | 11-10 演示：开不开 donor 的差别要明显。代价：存档里服务器自己生成、没导入过的 chunk 认不出来，可能被覆盖 |
| `skipNoDeadline` | true | 开 deadline 时，找需求算过、玩家不会去的 tile（侧后方，没有截止时间）不派 | false 退回原来的（donor 空着时也派），对照用 |
| `maxLeaseTotalMs` | 120000 | 一个任务从派出起，心跳最多续租到多久，过了收回重派 | 11-09：worker 卡死时心跳照发，原来能无限续租 |
| `importBudgetMs` | 10 | 软预算：每个 tick 结束时导入用到这么多毫秒就停，其余留到下个 tick。每个 tick 至少提交 1 个 tile，单个 tile 本身就可能超过 10 ms，所以**不保证导入那一 tick 的 MSPT 在 10 ms 以内**，只是不再几个 tile 挤在同一 tick 里 | 11-09 codex-audit A10：几个结果一起到时同一 tick 里做完，MSPT 最大 305 ms |
| `requeueCooldownMs` | 5000 | 丢掉的 tile 过多久、需求重新成立时才能重新入队 | 11-09 codex-audit F6 |
| `tokenTtlMs` | 60000 | 发给客户端的一次性 token 多久作废 | |
| `allowTokenless` | false | 没带 token 的 worker 放不放进来。测试的 compose 里设 true（Docker 里的本地 worker 没有玩家）；正式部署保持 false | 第 9 步 |
| `publicHost` | 空 | 发给玩家客户端的 coordinator 地址（主机名或 IP）。空就不发邀请，玩家的 worker 起不来 | 第 9 步 |
| `inviteRequestMinMs` | 10000 | 每个玩家多久最多再要一次邀请（`mcdonate:invite_request`），超了忽略并记 WARN | 第 9 步 |
| `publicPort` | 同 `port` | 发给玩家客户端的 coordinator 端口（端口转发时和监听端口不一样） | 第 9 步 |
| `features` | 空 | 第 7 步的功能开关，逗号分隔（territory、deadline、throughput、hedge、waitdonor） | 空是 v0（先来先服务，测试的对照组）。**部署推荐 `territory,deadline,throughput`**（deploy/ 的默认；deadline 带实测前沿和排队剪枝；throughput 一次给一个 donor 几个任务，worker 几个 tile 同时生成。真机上 1 个 donor 时开 throughput 收下的比例从 52% 涨到 82%）。deploy/ 的 demandLookahead 默认 16，见 docs/step7-report.md 开头 |
| `demand` | on | `off` 关掉自动找需求（测试里手动派任务时用） | |
| `port` | 25580 | donor 连进来的端口 | |
| `waitDonorMaxMs` | 2000 | 原版要生成的 chunk 正在 donor 上做时，最多等多久（7.6，做好后生效） | 越大服务器省的越多，但 donor 不回时玩家多等；不超过 donor 做一个 tile 的时间 |

## donor（worker）

| 名字 | 默认 | 含义 |
| --- | --- | --- |
| `coordinator` | 无 | 服务器地址 `host:port` |
| `tileSize` | 4 | 同上，必须和服务器一样 |
| `heartbeatMs` | 5000 | 心跳间隔 |
| `readTimeoutMs` | 15000 | 多久收不到服务器消息算断线 |

## 生成线程数

不是我们的配置项，是原版的：生成线程数 = 可见核数 − 1，最少 1 个（`Util.makeExecutor`）。所以 1 核和 2 核服务器都只有 1 个生成线程。

- 服务器：容器限核（`MC_CPUS`）或 JVM 参数 `-XX:ActiveProcessorCount=N` 决定可见核数；`-Dmax.bg.threads=N` 设上限。
- donor：客户端 mod 启动 worker 时用 `-XX:ActiveProcessorCount` 定（`WorkerLaunch`），默认留出玩家玩游戏要用的核。
- `/donor status` 的 `config.genThreads` 显示服务器实际的生成线程数。

## 开发机上的值从哪来

都是开发机（EPYC 7542，按 N100 限 4 核、频率固定 2.9GHz）上粗测的，第 1、6、7 步都只做了快速对比，精调留到部署机器上。

## 部署时的安全注意（第 9 步，已知限制）

- **游戏连接**：邀请（coordinator 地址和一次性 token）走游戏自己的连接。服务器 `online-mode=false`（离线模式）时游戏连接**不加密**，同一个局域网里能抓到 token，抢在玩家的 worker 之前用掉。正式部署要开 `online-mode=true`；离线模式只适合可信的网络（比如测试）。
- **coordinator 和 worker 之间的 TCP 是明文**：抓到包的人同样能抢用 token（一次性，被抢用后玩家的 worker 会被拒，可以再要一次邀请）。以后的工作：加 TLS。
- **`allowTokenless` 只给测试**：开着时没带 token 的 worker 也能连进来、领任务、导入。只在 `docker/compose.test.yaml` 里设，coordinator 启动时开着会打 WARN。
