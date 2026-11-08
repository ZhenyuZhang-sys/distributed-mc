# 配置项

和机器有关的数都做成配置项。开发机上测出来的只是起点，部署到真正的服务器（N100）和玩家电脑上再调。

## 怎么设

优先级从高到低：

1. JVM 参数 `-Dmcdonate.<名字>=<值>`（测试用 `JVM_OPTS` 传，见 `docker/compose.test.yaml`）。
2. 配置文件：服务器目录下的 `config/mcdonate.properties`（Docker 里是 `/data/config/mcdonate.properties`），一行一个 `名字 = 值`。路径可用 `-Dmcdonate.config=...` 换。
3. 代码里的默认值（下表）。

启动时读一次，改了要重启。数字可以写下划线（`30_000`）；写错（不是数字）启动直接报错，不会悄悄用默认值。

**当前生效的值**：`/donor status` 输出里的 `config`。

## 服务器（coordinator）

| 名字 | 默认 | 含义 | 部署时怎么调 |
| --- | --- | --- | --- |
| `tileSize` | 4 | 一个 tile 是几×几个 chunk（donor 另外多导出外面一圈） | **donor 和服务器必须一样**，对不上服务器会拒收。大了每个任务更久、丢弃一次损失更大；小了任务多、网络和导入开销占比高。按 donor 做一个 tile 的耗时在 2～5 秒之间选 |
| `demandLookahead` | 8 | 找需求时 inner 往外再看几圈（outer = inner + 这个数） | 窗口 ≈ 提前量 ÷ 玩家速度。玩家快（鞘翅）、donor 慢时调大；调大后队列里离玩家远的 tile 变多，可能白做 |
| `demandInnerMargin` | 1 | inner = 视距 + 原版占内存的圈数（约 11）+ 这个数 | 看 `/donor status` 的 `discarded.late`：来晚了的多，说明 inner 太靠里，调大 |
| `demandQueueCap` | 256 | 找需求时队列里最多放几个 tile | 玩家多时调大 |
| `scanTicks` | 20 | 每隔几个 tick 找一次需求（20 = 1 秒） | `scan` 里扫描耗时大、服务器 MSPT 紧时调大 |
| `leaseMs` | 30000 | 任务租约，超时收回重派 | 比最慢的 donor 做一个 tile 的时间长几倍 |
| `heartbeatMs` | 5000 | 心跳间隔（donor 端同名配置也要一致） | 一般不动 |
| `deadMs` | 15000 | 多久没收到消息判 donor 断线 | 至少 3 个心跳 |
| `maxAttempts` | 3 | 一个任务最多派几次，之后交还服务器自己生成 | 一般不动 |
| `orphanMs` | 60000 | 只能给某个 donor 世界做的任务，那个世界的 donor 都不在多久后丢掉 | |
| `maxConcurrency` | 4 | 一个 donor 最多同时领几个任务 | donor 核多、网络延迟大时调大 |
| `tileDefaultMs` | 3000 | 没测过的 donor 预计做一个 tile 要多久 | 用部署机器上 donor 的实测平均值 |
| `throughputWindow` | 10 | donor 耗时按最近几个任务平均 | |
| `motionWindowMs` | 3000 | 估玩家速度用最近多久的位置 | |
| `motionHorizonS` | 120 | 按玩家速度往前预测多少秒 | |
| `tokenTtlMs` | 60000 | 发给客户端的一次性 token 多久作废 | |
| `features` | 空 | 第 7 步的功能开关，逗号分隔 | 见 docs/step7-plan.md |
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
