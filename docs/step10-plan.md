# 第 10 步详细计划：完整实验和报告素材

依据：[开发总计划](../DEV_PLAN.md) 第 10 步、[README](../README.md) 的研究假设，以及第 1、4、6、7、8、9 步的计划和产物（fish 的 `docs/step6-plan.md`、`step7-plan.md`、`step9-plan.md`）。

状态：**计划已通过（调度 11-08 审阅，4 条补充已写入第 3、4、5、6 节和 2.5）**。负责人 kexu。按用户 11-08 的"先把功能做好"：先把矩阵框架、汇总和画图做出来，有自动测试；这台机器上只跑一个很小的冒烟矩阵；完整矩阵、置信区间和最终的 X、Y、Z 留到部署机器。

## 1. 目标和产物

把前面各步的场景组合成一个实验矩阵，回答研究假设：

> 在一台 2 核的服务器上，由 N 个志愿玩家分担 chunk 生成，可以把 p95 chunk 就绪延迟降低 X 倍，MSPT 保持在 50ms 以下；同时验证机制能抓到 ≥99% 的篡改 chunk，验证成本不超过本地生成的 Y%，捐赠者帧时间增加不超过 Z%。

| 产物 | 内容 |
| --- | --- |
| `analysis/run_matrix.py` | 按配置文件跑矩阵，能断点续跑；只产出每轮的原始结果，不画图 |
| `analysis/matrix/*.json` | 矩阵配置：`smoke.json`（这台机器）、`full.json`（部署机器） |
| `analysis/matrix_summary.py` | 只读每轮结果，写成整齐的表 `matrix-runs.csv`（一轮一行）和 `matrix-summary.csv`（一个配置一行，带置信区间） |
| `analysis/plots.py` | 只读这两张表，画所有图；不碰原始数据、不起服务器 |
| `analysis/results/matrix-<名字>/report.md` | X、Y、Z 和每张图，自动生成 |
| `scripts/matrix.sh smoke\|full` | 一条命令：跑矩阵 → 汇总 → 画图 |

## 2. 矩阵框架（`run_matrix.py`）

### 2.1 配置

矩阵维度全在配置文件里，代码里不写死：

```json
{
  "name": "smoke",
  "seed": 424242,
  "repeats": 1,
  "experiments": {
    "flight": {
      "donors": [0, 2],
      "speed": ["elytra"],
      "server_cpus": [2],
      "latency_ms": [0],
      "features": ["v0"],
      "bots": [1],
      "duration_s": 60
    },
    "capacity": {"enabled": false},
    "verify": {"cheats": ["ore"], "duration_s": 300},
    "frametime": {"import_dir": null}
  },
  "server_cpuset": "12-13",
  "donor_cpuset": "16-19",
  "rerun_on_interference": false
}
```

- 一个**单元** = 一种实验 + 一组参数值 + 第几次。单元编号由参数的规范 JSON 算哈希，和顺序无关。
- `features` 的取值是第 7 步开关的组合名（`v0` = 全关，`all` = 全开，单项名 = 只开这一项），对应 fish 的 `mcdonate.features`。
- 跑的顺序按种子打乱（同第 1 步），避免"先跑的都是 0 donor"这种时间漂移混进结果。

### 2.2 断点续跑

- 每个单元一个目录 `analysis/results/runs/matrix-<名字>/<单元编号>/attempt-NNN/`，先写 `manifest.json`（状态 running），跑完原子地改成 passed / failed / invalid。沿用第 1 步 `bench/archive.py` 的封存和校验。
- 重跑同一个配置：已 passed 且校验通过的单元跳过；failed / invalid 的重跑，最多 3 次，每次都留记录。
- 配置文件改了：已有结果的单元参数不变就沿用，参数变了算新单元。配置摘要记在 campaign 目录里。
- 中断（Ctrl-C、SIGTERM）：当前单元记 interrupted，正常停服；下次从它重跑。启动新世界前先移走残留的 Chunky 任务（第 1 步修过的坑）。

### 2.3 锁和并发

- 每个单元单独拿重活锁（独占），单元之间放锁，让 fish 的测试能插进来。
- 有效性规则沿用第 4 步 5.1a：单元期间记录 `docker events`，有别的容器事件就标"有并发"。完整矩阵（`rerun_on_interference: true`）判无效重跑；冒烟矩阵只标出来。

### 2.4 每种实验的驱动

`run_matrix.py` 只管调度、续跑、归档；每种实验一个驱动，接口是 `run(单元参数, 输出目录) -> result.json`：

| 实验 | 驱动 | 用到的东西 |
| --- | --- | --- |
| `flight` | 第 4 步 `bench/layer2.py` 的单轮飞行 | 第 6 步的 donor（compose 里的 worker 服务，个数 = `donors`）、第 7 步的开关、网络延迟代理（2.5） |

**chunk 来源按三类算（fish 11-09）**：服务器从头生成（`vanilla` / `full_generated`）、donor 导入后服务器接着做完（`resumed` / `full_resumed`）、读盘（`loaded` / `full_loaded`）。flight 驱动的结果里三类计数分开记，`layer2-chunks.csv` 的 `source` 列给每个进视野的 chunk 标是哪一类；图 1、2 和报告里的"donor 分担了多少"按 resumed 占三类之和算，不把 resumed 当成服务器自己生成的。报告里 X（就绪 p95 之比）旁边同时列卡住时长（第 4 步的 `stall_s`）。
| `capacity` | 第 7 步的容量测试（fish `step7-plan.md` 第 2 节）：逐个加鞘翅假玩家，直到虚空率 > 5% | 第 4 步多架飞行（z 方向隔 1024 格） |
| `verify` | 第 8 步：诚实 donor + 各种作弊 donor 跑一段时间 | 第 8 步作弊开关、验证器、回滚报告 |
| `frametime` | 不跑，只**导入**第 9 步在真实电脑上采的 `frametimes.csv` | fish `step9-plan.md` 9.7（需要人和显卡） |

### 2.5 网络延迟

用一个 Python TCP 转发代理 `analysis/netdelay.py`：worker 连代理，代理每个方向延迟固定毫秒再转给 coordinator。好处是不需要容器的 NET_ADMIN 权限，也不改共用的 compose 文件。

- 参数：`latency_ms`（单向固定延迟），`jitter_ms`（抖动，默认 0 = 关）。开抖动时每段数据的延迟在 `latency_ms ± jitter_ms` 里均匀取，但不早于上一段的发出时间，保证 TCP 字节顺序不乱。
- **已知限制**（报告里照写）：不模拟丢包、带宽限制和乱序；抖动只是均匀分布。丢包下的**正确性**第 5 步已经用 tc netem（1% 丢包）测过；这里只管**性能**。

## 3. 结果和画图分开

`matrix-runs.csv` 一轮一行，列是：单元编号、实验、各参数、第几次、状态、有没有并发，以及这一轮的指标：

- flight：虚空率均值和 p95、就绪延迟 p50 / p95 / p99、MSPT 均值和最大值、服务器 CPU 核数、第 7 步的 coordinator 计数（字段名照第 4 步 6.1 的表）。
- capacity：最多撑住几个鞘翅假玩家（`capacity_bots`；不能叫 `max_bots`，那是搜索上限参数，指标和参数同名时汇总直接报错）、每一级的虚空率。
- verify：每种作弊的检出与否、开始作弊到拉黑写进了多少 chunk、误报次数、验证 CPU、Y。
- frametime：worker 开 / 关、限速开 / 关时的 p99 帧时间。

`matrix-summary.csv` 一个配置一行：均值、标准差、n，以及 95% 置信区间（5 次时用 t 分布；比值类的 X 用自助法，见第 5 节）。n < 2 的配置置信区间写空，报告标"冒烟，没有置信区间"。

`matrix_summary.py` 还输出一张**假设检验汇总表** `hypotheses.csv`，报告开头引用。研究假设逐条一行：

| 条目 | 数值 | 置信区间 | 判定 | 判定规则（事先定好） |
| --- | --- | --- | --- | --- |
| X（2 donor） | 0 donor 的 p95 ÷ 2 donor 的 p95 | 自助法 95% | 成立 / 不成立 / 数据不足 | 区间下限 ≥ README 的候选目标 2 为成立；区间上限 < 2 为不成立；跨过 2 或 n < 5 为数据不足 |
| MSPT < 50 ms | 2 核、所有 donor 配置里，每轮"每秒 MSPT 均值"的 p95，取最差的那一轮；另列每秒最大值 | — | 同上 | 最差一轮的 p95 < 50 为成立；≥ 50 为不成立；没有 2 核 flight 数据为数据不足 |
| 检出率 ≥ 99%（每种作弊一行） | 默认强度下被抓到的比例 | Clopper–Pearson 95% | 同上 | 区间下限 ≥ 99% 为成立；上限 < 99% 为不成立；其余为数据不足（次数少时 99% 本来就证明不了，照实写）。少量塞矿的下限按第 8 步报告附在备注里 |
| Y | 验证 CPU ÷ 本地生成 CPU | t 分布 | 已定 / 数据不足 | 有 ≥ 5 次 verify 数据即"已定"，给出数值（Y 是待定的数，不是事先的门槛） |
| Z | 开 worker 后 p99 帧时间增加量 | t 分布 | 已定 / 数据不足 | 有真实电脑的 frametimes 数据即"已定"；这台机器上一定是"数据不足" |
| 卡住（stall，11-09 加） | X 那组里 2 donor 的卡住占比（假玩家停在没就绪 chunk 前面的时间 ÷ 飞行时长），注里列 0 donor 的对照和实际 / 理想距离 | — | 记录 / 数据不足 | 描述性，不是门槛；紧跟在 X 后面 |
| donor 分担（donor_share，11-09 加） | X 那组里 2 donor 的轮：导入后服务器接着做完（full_resumed）÷ 三类之和 | — | 记录 / 数据不足 | 描述性；resumed 不算成服务器自己生成的（fish 11-09 口径） |

`x-by-n.csv` 每个 N 一行，除了 X 和区间，还列 0 donor / N donor 的卡住占比、实际 / 理想距离均值和 donor 分担比例。

`plots.py` 只读这几张表。测试用合成的表就能跑通所有图，不需要真跑矩阵。

## 4. 每张图要什么数据、从哪来

| 图 | 回答什么 | 数据 | 来自 |
| --- | --- | --- | --- |
| 1. 虚空率和就绪延迟 vs donor 数 | donor 越多，眼前空白越少吗 | flight：donors × speed，2 核，v0 和全开 | 第 4 步飞行场景 + 第 6 步 donor + 第 7 步开关 |
| 2. 服务器 CPU 和 MSPT vs donor 数 | 省下多少服务器算力，MSPT 是否 < 50ms | 同图 1 的单元 | 第 4 步每秒记录（cgroup CPU、MSPT） |
| 3. 可持续探索容量 | 虚空率 ≤ 5% 时最多几个鞘翅玩家 | capacity：donors × features | 第 7 步容量测试 |
| 4. 第 7 步每项的消融 | 每个开关单独带来多少 | flight：features 取 v0、单项、全开，鞘翅，2 donor | 第 7 步（fish 的消融结果也可直接导入） |
| 5. tile 大小 | 外圈重复计算 vs 延迟 | flight：tile_size 1/2/4/8 | 第 7 步 `ring_overhead`、`hedge_wasted_tiles` |
| 6. 网络延迟敏感性 | donor 在远处还有用吗 | flight：latency_ms 0/50/150 | 2.5 的延迟代理 |
| 7. 检出率和成本 | 每种作弊抓到没有、花多少 CPU；抽查率–检出率–成本曲线 | verify：cheats × 抽查率 | 第 8 步验证器和 `/donor status` 的验证 CPU |
| 8. 作弊到被拉黑写进的 chunk 数 | 回滚要收拾多大的烂摊子 | verify | 第 8 步回滚报告 |
| 9. donor 帧时间 | 玩家当 donor 卡不卡，限速有没有用 | frametime：worker 开 / 关 × 限速开 / 关 | 第 9 步 `frametimes.csv`（真实电脑） |
| 10. 背景：原版每核吞吐和不确定率 | 原版本身多快、多不确定 | 不进矩阵，直接读 | 第 1 步 `analysis/results/layer1-summary.csv`、`determinism.csv` |
| 11. X 随 donor 数 N 变化 | 假设里的"N 个志愿玩家"：多加 donor 还有没有用 | flight：2 核、鞘翅、1 个假玩家，donors 0/1/2/4；每个 N 一个 X 和自助法区间 | 同图 1 的单元（完整矩阵 donors 至少取 0、1、2、4；冒烟只有 0、2，只画一个点） |

## 5. X、Y、Z 各从哪来

| 数 | 定义 | 数据 | 置信区间 |
| --- | --- | --- | --- |
| X | 2 核服务器、1 个鞘翅假玩家：0 个 donor 的 p95 就绪延迟 ÷ N 个 donor 的 p95 就绪延迟（N 取 README 的候选 2，图 1 同时给出其他 N） | 图 1 的 flight 单元，每配置 5 次 | 两组各 5 个"本轮 p95"，对比值做自助法（重抽 10000 次，取 2.5% 和 97.5% 分位）。删失（p95 为无穷大）照第 4 步的规则如实报 |
| Y | 验证 CPU ÷ 本地生成同样多 chunk 的 CPU（第 8 步第 6 节），默认抽查策略 | verify 单元（诚实 donor），每配置 5 次 | t 分布 |
| Z | 开 worker 后 p99 帧时间的增加量（相对 worker 关），限速开 | frametime，真实电脑上至少 5 段 10 分钟挂机 | t 分布；**这台机器测不了**（fish `step9-plan.md` 第 0 节：软件渲染的帧时间没有意义），等人提供有显卡的电脑 |

同时报研究假设里的另外两条：MSPT 是否 < 50ms（图 2）；作弊检出率是否 ≥ 99%（图 7，按第 8 步的默认作弊强度，少量塞矿的下限照第 8 步报告写）。

## 6. 冒烟矩阵和完整矩阵

| | 冒烟（这台机器） | 完整（部署机器） |
| --- | --- | --- |
| flight 主网格 | 鞘翅 × donor 0 / 2 × 2 核 × v0，1 次，每轮 60 秒 | donor 0/1/2/4 × 走路 / 骑马 / 鞘翅 × 2 / 4 核 × v0 / 全开（0 donor 不分开关），5 次，每轮 120 秒：42 个配置、210 轮 |
| flight 延迟切片 | 无 | 鞘翅、2 donor、2 核、全开 × 延迟 50 / 150 ms（0 ms 在主网格里），5 次：10 轮 |
| capacity | 关 | v0 / 全开 × donor 2 / 4，各 1 次容量搜索 |
| verify | 诚实 + ore 各 1 次，5 分钟 | 五种作弊 × 抽查率 3 档 × 5 次（每次约 10 分钟）；诚实 donor 30 分钟 × 5 |
| frametime | 不导入 | 导入真实电脑的数据 |

时长估计（按第 4 步"每轮 6～8 分钟"、fish 第 7 步"容量搜索每次约 1 小时"）：

| 部分 | 轮数 | 估计 |
| --- | --- | --- |
| flight 主网格 | 210 × 约 6 分钟 | 约 21 小时 |
| flight 延迟切片 | 10 × 约 6 分钟 | 约 1 小时 |
| capacity | 4 × 约 1 小时 | 约 4 小时 |
| verify | 75 × 约 10 分钟 + 5 × 30 分钟 | 约 15 小时 |
| 合计 | | **约 40 小时**，分两三天跑，靠断点续跑 |

之前写的"20～30 小时"低估了：全因子（再乘上 3 档延迟）要 70 多小时，所以改成主网格加延迟切片，合计约 40 小时。实际数以 `run_matrix.py --plan` 按配置算出来的为准。

### 6.1 部署机器上要准备什么（给用户）

| 角色 | 几台 | 配置 | 说明 |
| --- | --- | --- | --- |
| 服务器 | 1 台 N100（4 核 4 线程、8 GB） | Docker；测试用的 Minecraft 容器 6 GB、堆 4 GB，2 核配置限 2 核（`MC_CPUS=2`） | 只跑服务器和矩阵脚本（Python，很轻）。2 核配置时剩下 2 个核给脚本和系统；4 核配置时脚本和服务器共用，报告里注明 |
| donor | 2 台，每台 ≥ 4 核、≥ 8 GB；或 4 台 N100 级小机器，每台 1 个 donor | Docker；每个 donor 是一个 worker 容器，约 2 核、3 GB | donor 不能和服务器挤在同一台 N100 上，否则抢的是服务器的 CPU，结论就不成立。4 donor 配置需要 4 个 worker 同时在线 |
| 网络 | 同一个局域网，有线千兆 | coordinator 端口要对局域网开放（现在测试只绑 127.0.0.1，部署时要改 compose 叠加文件，和 fish 商量） | 延迟代理跑在 donor 那一侧 |
| 帧时间（Z） | 1 台有独立显卡的电脑 | 正版 Minecraft 1.21.1 + Fabric + 客户端 mod | 按 fish `step9-plan.md` 9.7 的脚本挂机，至少 5 段 × 10 分钟 |

服务器机器上照样要固定 CPU 频率；N100 如果固定不了，`frequency` 字段如实记下当前的调频策略，报告里注明。

## 7. 演示

- **实时地图回放**：第 4 步的地图已经能回放一轮的生成顺序。图 1 里 0 donor 和 2 donor 各挑一轮，自动导出回放截图序列（用地图服务器的回放接口 + 无头浏览器，或直接用 Python 按同样的上色规则画帧，二选一，后者不需要浏览器）。
- **作弊被抓**：verify 单元里，地图上把被打回的 chunk（`rejected`）标红，配上拉黑那一刻的日志行。
- **原版和有 donor 的并排视频**：需要真实客户端录屏，留给人做；这一步只准备好服务器端的场景脚本。

### 7.1 演示面板（调度 11-09 安排，**计划已通过**）

目的：给用户看"mod 起作用了"。是演示，不是测量：数字标"演示，粗测"，不进第 10 步的矩阵和假设表。

**放在哪**：还是 `bench/map/server.py` 这个小服务器（标准库 `http.server`，只绑 127.0.0.1），加一个 `/demo` 页面。页面左边是实时地图（复用现在的画图代码，抽成 `map.js` 两页共用），右边是曲线和控制。后端新代码放 `bench/demo/`：

| 模块 | 做什么 |
| --- | --- |
| `bench/demo/poll.py` | 每秒采一次样，存进内存里的环形缓冲（最近 30 分钟），给 `/api/live`、`/api/series?since=N` |
| `bench/demo/control.py` | 控制动作的白名单，每个动作对应一串固定的命令；不接受任意命令 |
| `bench/demo/ab.py` | 一键 A/B 的编排，跑在后台线程里，状态给 `/api/ab` |

**1. 实时曲线（每秒刷新）**，每个量一张小图，不画双轴：

| 曲线 | 从哪来 |
| --- | --- |
| MSPT 均值和最大值（最近 1 秒）、TPS | `/bench stats` 加三个字段：`tick`（`getTickCount()`）、最近 20 个 tick 的 MSPT 均值和最大值（`getTickTimesNanos()`）。TPS = 两次采样之间的 tick 数 ÷ 宿主机墙钟。现在的 `mspt` 是 100 tick 平均，太平滑，留着不动 |
| 容器 CPU 核数 | 宿主机 cgroup，复用 `bench/metrics.py` 的 `Cgroup.sample()`，两次采样的差 ÷ 墙钟 |
| 视野虚空率、卡住占比、实际 / 理想距离 | 正在飞时读这一轮的 `seconds.jsonl`（和地图一样增量读完整的行）：每秒的 `void_rate`、`stalled_ticks / ticks`，距离用 `x` 和起飞后的理想位置算。不在飞时这几张图空着 |
| donor 在线数、导入数、丢弃数（按原因：来晚、别的 donor 世界、碰到服务器的 chunk） | `/donor status`：`workers` 的个数，`accepted` 和 `discarded.*` 每秒的增量 |
| donor 写入时在玩家前方的比例 | 正在飞时读 `provenance.jsonl` 的 donor 事件，和同一时刻 `seconds.jsonl` 的玩家 x 比（和 fish 对比脚本的 `donor_lead` 同一算法，抽成共用函数）；每秒一个点，另给整轮累计 |

每秒发 2 条 RCON（`bench stats`、`donor status`），在服务器主线程上各占几十微秒；页面上注明"面板自己也占一点服务器时间"。某一项拿不到（比如没起 coordinator，`/donor status` 报错）就那张图写"无数据"，其他照常。

**2. 控制**：都是 `POST /api/control`，JSON 里一个 `action`；后端按白名单拼命令，走现有入口（`bench.server.Server.rcon` → `scripts/rcon.sh`，`bench.server.compose` → `lib.sh` 的 `tc`），不另开控制通道。

| 动作 | 背后 |
| --- | --- |
| donor 开 | `compose up -d --wait worker worker2`，等两个都在 `/donor status` 里出现，再 `donor demand on` |
| donor 关 | `donor demand off`，`compose stop worker worker2 worker3`，等 `/donor status` 的 workers 为空 |
| 第 7 步开关 | `donor feature <名字> on|off`，名字只能是 territory、deadline、throughput、hedge、waitdonor |
| 飞行开始 | 选速度（走路 / 骑马 / 鞘翅）和时长，后台线程调第 4 步的 `bench.layer2.fly`（放假玩家、等初始视野就绪、`/bench fly start`、归档）；航线每次换一条没生成过的（z 方向隔 8000 格，计数记在 artifacts 里） |
| 飞行停止 | `bench fly stop <run>`；这一轮标"提前停下"，归档照留，不出汇总 |

安全：只绑 127.0.0.1；控制接口只收 `POST`、`Content-Type: application/json`、带自定义头 `X-Demo: 1`（跨站表单发不出来），并检查 `Host` 是 `127.0.0.1` 或 `localhost`（防 DNS 重绑定）。同一时间只允许一个动作在跑，其他返回 409。页面上每个按钮先弹确认框。

**3. 一键 A/B**：`POST /api/ab`（速度、时长，开关默认 `deadline`）。步骤：

1. 记下开始时间（后面查 `docker events` 标"有并发"）。
2. A：donor 关（上面的动作），`scripts/new_world.sh` 换新世界（同一个 seed 424242），飞一轮。
3. B：再换一个新世界（同 seed），`donor reset`，donor 开，开 deadline，飞同一条航线、同样速度和时长。两组都用全新世界、同一条航线，地形一样；只用一个世界换 z 的话地形不同，不公平。
4. 有效性：两轮玩家同步加载都是 0；B 飞行窗口内 donor 来源事件 > 0；B 起飞前后 `/donor status` 的 worker id 没变（中途没被看门狗杀掉）。不满足的在结果里标"无效"和原因，照样显示。
5. 结果存 `artifacts/demo-ab-<时间>/`：`A/`、`B/`（`layer2.fly` 的归档）、`ab.json`（两轮汇总、有效性、并发、参数）、`README.md`（写明"演示，粗测"）。

页面并排显示：表格（卡住时长和占比、实际 / 理想距离、虚空率、就绪 p95、MSPT 均值 / 最大、服务器 CPU、导入 / 丢弃、donor 写入在前方的比例、有效性），同一张小图里两条曲线（A、B 两条线，图例 + 直接标注），下面两个地图回放（用现有 `/replay`）。

**审阅补充（调度 11-09，不用再审）**：

- B 轮开始前调一次 `donor reset`，donor 默认开 deadline。worker 要关掉看门狗（fish 给 worker 设的 `MAX_TICK_TIME=-1`）：面板每次采样用 `docker inspect` 看 worker 容器的环境变量，没有这一项就在页面顶上标"worker 看门狗没关，可能中途被杀"。fish 的改动进 main 之前要演示的话，在本槽位用一个不进 git 的 compose 叠加文件临时加上（`lib.sh` 现在不支持额外的叠加文件，到时候和 fish 商量是加一个 `MCD_COMPOSE_EXTRA`，还是等 fish 的改动进 main），页面照样标出来。
- "模拟网络"开关（可选，默认关）：复用 `scripts/netem.sh`，按 35ms 延迟、20mbit 带宽、1% 丢包。现在的 `netem.sh` 只管 `worker` 一个服务、没有带宽参数；要 fish 那边给它加上带宽和 `worker2`（公共脚本，不自己改），加上之前这个开关灰着。开着的时候页面顶上醒目地标"模拟网络：35ms / 20mbit / 1% 丢包"，A/B 结果里也记下来。

**4. 怎么用**：`docs/demo.md` 写：在测试槽位上起测试服务器和 worker（`scripts/test_server.sh up`，用这个槽位的 `.env.local`）；起面板 `scripts/demo.sh`（`python -m bench.map.server` 加演示参数，端口默认 8765）；本地浏览器 `ssh -L 8765:127.0.0.1:8765 <这台机器>` 再开 `http://127.0.0.1:8765/demo`；每个按钮是什么意思；A/B 大约要多久（两次换世界 + 两次飞行，鞘翅 120 秒约 8～10 分钟）。

**测试（先写）**，都放 `tests/step10/test_06_demo.py`，不起服务器：

- 数据接口：假的 rcon 和 cgroup，造两次采样 → `/api/live` 的 TPS、MSPT、CPU、donor 增量算得对；`since` 增量正确；某个来源报错时那一项为空、其他照常。
- 飞行中的曲线：合成 `seconds.jsonl` 和 `provenance.jsonl` → 虚空率、卡住占比、距离、前方比例对。
- 控制接口：每个动作调到的命令序列对（假的 runner 记下来）；不认识的动作 / 开关名 400；没有 `X-Demo`、不是 JSON、`Host` 不对 403；GET 不能控制；忙的时候 409。
- A/B：假的 fly、new_world、compose → 步骤顺序对（A 时 donor 关、B 时 deadline 开、两次都换了新世界、同一条航线）；B 没有 donor 事件或 worker id 变了标无效；artifacts 里有 `ab.json` 和"演示，粗测"。
- 前端冒烟：`/demo` 返回的页面有各张图的 canvas、控制按钮和确认框代码。
- Java：GameTest 查 `/bench stats` 新加的 `tick` 每次调用变大、最近 1 秒的 MSPT 字段存在。
- 真机冒烟（long，第 10 步验收里跑一次）：在测试服务器上起面板，`/api/live` 的 MSPT > 0、`/donor status` 有数，跑一次很短的 A/B（30 秒）出结果。

**顺序**：计划过审后，等手上的完整测试过了、第 4 步的 RESUMED 改动进 main，再动手；控制和 A/B 要用 fish 第 7 步的 `/donor feature`、`demand`、`reset`，等它们进 main 再接真机，之前先用假 runner 把接口和编排做完。

### 7.2 跟踪真人玩家（调度 11-09 安排，排在面板 A/B 之后）

用户用 fish 的客户端 mod 当 donor、自己进游戏玩时，地图和面板也要能看。bench 加"只记录、不移动玩家"的模式：

- 命令：`/bench record start <玩家名> [run 名]`、`/bench record stop [run 名]`。和 `/bench fly` 共用 `FlightRecorder` 的记录部分（视野扫描、就绪事件、逐秒行、来源事件、守护计数、done.json），只是不调 `Flight.advance`、不 teleport；文件格式和飞行一样，地图回放、`layer2.compose`、面板直接能用。done.json 里 `mode` 写 `record`，没有速度和理想距离（写 null）。
- 卡住：真人客户端自己会停在没收到的 chunk 边缘。一个 tick 算卡住 = 这个 tick 玩家位置几乎没动（< 0.01 格）**而且**朝向前方（按视线水平方向，没有视线就按上一次移动方向）挨着的那个 chunk 没就绪。站着不动看风景、前方 chunk 已就绪的不算。这是近似：玩家可能因为别的原因停下，报告里照写。
- 实际距离：每 tick 位置差的累加，没有理想距离。
- 玩家下线：自动结束这一轮，done.json 写 `stopped_reason: "left"`。
- 面板加"跟踪真人玩家"入口：列在线玩家（`/bench stats` 的 players），选一个开始 / 停止；实时曲线和地图跟着这一轮的文件走。
- **测试（先写）**：JUnit 测卡住判定（位置不动 + 前方未就绪 / 已就绪 / 在动，各一种）；GameTest 用 mock 玩家：record 期间不改玩家位置、文件齐全、来源事件进得来、玩家移除后自动结束；pytest 测面板的跟踪入口调对命令、`compose` 能读 record 的归档。
- **接入 u1**：u1 上的服务器是 fish 起的，不重启它，只用 rcon 发命令。但新命令要新版 bench mod，u1 现在加载的是 fish 构建的 jar：要等 fish 下次重启 u1 时带上包含 `/bench record` 的 jar，或者由 fish / 用户决定什么时候重启。面板连 u1 用它的 rcon 和端口（25575 / 25585），不动它的 compose。

## 8. 依赖和接口

| 依赖 | 谁 | 状态 |
| --- | --- | --- |
| 飞行场景、多架飞行、结果字段 | kexu 第 4 步 | 4.1、4.4、4.6 已做，4.2 起等 fish 第 3 步进 main |
| donor 个数、开关、tile 大小、`/donor status` 字段 | fish 第 6、7 步 | 在 fish 分支，等合进 main；字段名按第 4 步 6.1 的表 |
| 容量测试的步骤 | fish 第 7 步第 2 节 | 驱动直接调它，不重写 |
| 验证器、作弊开关、回滚报告 | kexu 第 8 步 | 8.1、8.2 纯逻辑已做，接进 coordinator 等 merge main |
| `frametimes.csv` | fish 第 9 步 | 格式在 `step9-plan.md` 第 4 节，数据等人跑 |

## 9. 拆成小步和验收

按项目规则先写测试、提交、看到它失败，再写功能。

| 小步 | 内容 | 先写的测试 | 完成条件 |
| --- | --- | --- | --- |
| 10.1 | 配置和单元（**已做**） | pytest：单元编号和顺序无关；配置展开出的单元数对；非法配置报错；`--plan` 估时 | 通过 |
| 10.2 | 断点续跑（**已做**） | pytest（假驱动，不起服务器）：跑到一半中断，重跑只补没做完的；failed 最多重试 3 次；参数变了算新单元；封存校验失败不沿用 | 通过 |
| 10.3 | 汇总（**已做**） | pytest：合成的每轮结果 → `matrix-runs.csv`、`matrix-summary.csv` 列和数对；t 区间、自助法区间、Clopper–Pearson 区间和手算一致；n < 2 时区间为空；`hypotheses.csv` 每条的判定（成立 / 不成立 / 数据不足）在构造好的边界数据上都对 | 通过 |
| 10.4 | 画图（**已做**） | pytest：合成的表能画出全部 11 张图；缺某种实验的数据时那张图写"无数据"而不是报错 | 通过 |
| 10.5 | 网络延迟代理（**已做**） | pytest：本地回环测往返时间，延迟设 50ms 时实测 100ms 左右（来回各一次）；开抖动时延迟落在范围内、字节顺序不乱 | 通过 |
| 10.6 | 真驱动 | flight、verify 驱动接上第 4、6、8 步 | 冒烟矩阵里对应单元 passed |
| 10.7 | 冒烟矩阵 | `scripts/matrix.sh smoke` 一条命令跑完、出报告和图 | `scripts/test.sh step10 --slow` 退出码 0 |
| 10.8 | 收尾 | 所有步骤的回归测试；DEV_PLAN 勾选照实标（冒烟 / 待部署机器） | 通过 |

**降级（留到部署机器）**：完整矩阵、5 次和置信区间、最终的 X、Y；Z 要真实显卡；并排视频要真实客户端。这些写进 log.md 的"已跳过"。

报告的"已知限制"一节至少写：网络代理只有固定延迟（可选均匀抖动），没有丢包、带宽和乱序；这台机器上的数是 EPYC 限核测的，不是 N100；Z 没在这台机器上测；第 8 步统计层阈值只来自三个区域。DEV_PLAN 验收的"一条命令在一台新机器上跑完整个矩阵"，在这台机器上用冒烟配置证明框架能跑通，完整配置到部署机器上跑。

## 10. 什么时候开工

- 10.1～10.5 是纯逻辑，不依赖别人的代码，可以先做。
- 10.6 起要第 4、6、7、8 步都进 main。
- 优先级：第 4 步 4.2 起 > 第 8 步接进 coordinator > 第 10 步。

## 11. 风险

- 完整矩阵很长（估 20～30 小时），中途机器被别的任务占用。靠断点续跑和"有并发就重跑"兜底。
- 各步的结果字段可能对不上：驱动只认第 4 步 6.1 的字段表，对不上的字段写 null 和原因，不填 0。
- X 的分母是 2 donor 的 p95；如果 2 donor 的 p95 也是删失（无穷大），比值没有意义，报告如实写"donor 也没跟上"，不硬算。
- 网络延迟代理不模拟带宽和丢包，图 6 的结论只限"固定延迟"。
