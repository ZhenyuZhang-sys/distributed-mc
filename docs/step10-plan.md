# 第 10 步详细计划：完整实验和报告素材

依据：[开发总计划](../DEV_PLAN.md) 第 10 步、[README](../README.md) 的研究假设，以及第 1、4、6、7、8、9 步的计划和产物（fish 的 `docs/step6-plan.md`、`step7-plan.md`、`step9-plan.md`）。

状态：**计划，待审**。负责人 kexu。按用户 11-08 的"先把功能做好"：先把矩阵框架、汇总和画图做出来，有自动测试；这台机器上只跑一个很小的冒烟矩阵；完整矩阵、置信区间和最终的 X、Y、Z 留到部署机器。

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
| `capacity` | 第 7 步的容量测试（fish `step7-plan.md` 第 2 节）：逐个加鞘翅假玩家，直到虚空率 > 5% | 第 4 步多架飞行（z 方向隔 1024 格） |
| `verify` | 第 8 步：诚实 donor + 各种作弊 donor 跑一段时间 | 第 8 步作弊开关、验证器、回滚报告 |
| `frametime` | 不跑，只**导入**第 9 步在真实电脑上采的 `frametimes.csv` | fish `step9-plan.md` 9.7（需要人和显卡） |

### 2.5 网络延迟

用一个 Python TCP 转发代理 `analysis/netdelay.py`：worker 连代理，代理每个方向延迟固定毫秒再转给 coordinator。只模拟延迟，不模拟带宽和丢包，报告里写明。好处是不需要容器的 NET_ADMIN 权限，也不改共用的 compose 文件。

## 3. 结果和画图分开

`matrix-runs.csv` 一轮一行，列是：单元编号、实验、各参数、第几次、状态、有没有并发，以及这一轮的指标：

- flight：虚空率均值和 p95、就绪延迟 p50 / p95 / p99、MSPT 均值和最大值、服务器 CPU 核数、第 7 步的 coordinator 计数（字段名照第 4 步 6.1 的表）。
- capacity：最多撑住几个鞘翅假玩家、每一级的虚空率。
- verify：每种作弊的检出与否、开始作弊到拉黑写进了多少 chunk、误报次数、验证 CPU、Y。
- frametime：worker 开 / 关、限速开 / 关时的 p99 帧时间。

`matrix-summary.csv` 一个配置一行：均值、标准差、n，以及 95% 置信区间（5 次时用 t 分布；比值类的 X 用自助法，见第 5 节）。n < 2 的配置置信区间写空，报告标"冒烟，没有置信区间"。

`plots.py` 只读这两张表。测试用合成的表就能跑通所有图，不需要真跑矩阵。

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
| flight | 鞘翅 × donor 0 / 2 × 2 核 × v0，1 次，每轮 60 秒 | donor 0/1/2/4 × 三种速度 × 2/4 核 × 延迟 0/50/150 × v0/全开，5 次，每轮 120 秒 |
| capacity | 关 | v0 / 全开 × donor 2/4 |
| verify | 诚实 + ore 各 1 次，5 分钟 | 五种作弊 × 抽查率 3 档，5 次；诚实 donor 30 分钟 |
| frametime | 不导入 | 导入真实电脑的数据 |
| 时长 | 约 20 分钟 | 估计 20～30 小时，按单元断点续跑 |

完整矩阵的单元数和时长由 `run_matrix.py --plan` 先算出来打印，不用跑就能看。

## 7. 演示

- **实时地图回放**：第 4 步的地图已经能回放一轮的生成顺序。图 1 里 0 donor 和 2 donor 各挑一轮，自动导出回放截图序列（用地图服务器的回放接口 + 无头浏览器，或直接用 Python 按同样的上色规则画帧，二选一，后者不需要浏览器）。
- **作弊被抓**：verify 单元里，地图上把被打回的 chunk（`rejected`）标红，配上拉黑那一刻的日志行。
- **原版和有 donor 的并排视频**：需要真实客户端录屏，留给人做；这一步只准备好服务器端的场景脚本。

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
| 10.1 | 配置和单元 | pytest：单元编号和顺序无关；配置展开出的单元数对；非法配置报错；`--plan` 估时 | 通过 |
| 10.2 | 断点续跑 | pytest（假驱动，不起服务器）：跑到一半中断，重跑只补没做完的；failed 最多重试 3 次；参数变了算新单元；封存校验失败不沿用 | 通过 |
| 10.3 | 汇总 | pytest：合成的每轮结果 → `matrix-runs.csv`、`matrix-summary.csv` 列和数对；t 区间、自助法区间和手算一致；n < 2 时区间为空 | 通过 |
| 10.4 | 画图 | pytest：合成的表能画出全部 10 张图；缺某种实验的数据时那张图写"无数据"而不是报错 | 通过 |
| 10.5 | 网络延迟代理 | pytest：本地回环测往返时间，延迟设 50ms 时实测 100ms 左右（来回各一次） | 通过 |
| 10.6 | 真驱动 | flight、verify 驱动接上第 4、6、8 步 | 冒烟矩阵里对应单元 passed |
| 10.7 | 冒烟矩阵 | `scripts/matrix.sh smoke` 一条命令跑完、出报告和图 | `scripts/test.sh step10 --slow` 退出码 0 |
| 10.8 | 收尾 | 所有步骤的回归测试；DEV_PLAN 勾选照实标（冒烟 / 待部署机器） | 通过 |

**降级（留到部署机器）**：完整矩阵、5 次和置信区间、最终的 X、Y；Z 要真实显卡；并排视频要真实客户端。这些写进 log.md 的"已跳过"。DEV_PLAN 验收的"一条命令在一台新机器上跑完整个矩阵"，在这台机器上用冒烟配置证明框架能跑通，完整配置到部署机器上跑。

## 10. 什么时候开工

- 10.1～10.5 是纯逻辑，不依赖别人的代码，可以先做。
- 10.6 起要第 4、6、7、8 步都进 main。
- 优先级：第 4 步 4.2 起 > 第 8 步接进 coordinator > 第 10 步。

## 11. 风险

- 完整矩阵很长（估 20～30 小时），中途机器被别的任务占用。靠断点续跑和"有并发就重跑"兜底。
- 各步的结果字段可能对不上：驱动只认第 4 步 6.1 的字段表，对不上的字段写 null 和原因，不填 0。
- X 的分母是 2 donor 的 p95；如果 2 donor 的 p95 也是删失（无穷大），比值没有意义，报告如实写"donor 也没跟上"，不硬算。
- 网络延迟代理不模拟带宽和丢包，图 6 的结论只限"固定延迟"。
