# mc-donate 评测（2026-11-24，course project）

给写论文用的说明，所有数字都可以从 `data/` 下的 CSV 重算出来。

## 目录

| 路径 | 内容 |
| --- | --- |
| `data/e1-runs.csv` | E1 每轮一行（6 轮） |
| `data/e23-runs.csv` | E2 每轮一行（4 轮） |
| `data/e3-runs.csv` | E3 每轮一行（2 轮） |
| `figures/fig1-stall.png` … `fig4-cores.png` | 4 张图，下面逐张说明 |
| `figures/summary.json` | 画图用到的每轮数值 |
| `plots.py` | 出图脚本，直接 `python plots.py` |
| `e1.json` `e23.json` `e3.json` | 实验矩阵配置（`analysis/run_matrix.py --config`） |
| `run-e*.log` | 运行日志 |
| `raw/e1` `raw/e23` `raw/e3` | 每轮的全部原始数据（`run_matrix.py` 的输出目录原样拷贝） |

`raw/<实验>/<cell_id>/attempt-001/` 下是一轮的所有输出：`flight/seconds.jsonl` 是逐秒数据（图 2 用的），`flight/raw/` 下是服务器侧的逐事件日志，还有服务器日志和 `run_matrix.py` 自动出的 demo 图。超过 1 MB 的文件（主要是 `stages.jsonl`）压成了 `.gz`，用 `zcat` 读。`raw/<实验>/matrix-runs.csv` 和 `data/` 下的 CSV 是同一份。

重新出图：`python plots.py`（默认读 `raw/`，输出到 `figures/`）。

## 系统简介

mc-donate 让玩家的电脑（donor worker）帮 Minecraft 服务器（Fabric 1.21.1）提前生成地形 chunk。服务器上的 coordinator mod 预测玩家要去哪，把前方的 chunk 派给 donor，donor 生成后传回来，服务器校验后导入。目的是让小服务器（核数少）也能扛住玩家快速移动时的地形生成压力。

## 实验设置（所有实验相同的部分）

- 代码：main 分支 `d196f2c`，worker jar md5 前缀 `c50dcbf9`（所有 donor 轮都核对过，见 `summary.json` 的 `worker_jar_mismatch: []`）。
- 服务器：Docker 容器，绑定 2 个（E3 为 4 个）物理核，容器内存 3G，JVM `-Xms512M -Xmx2G`，视距 10，模拟距离 6。
- 负载：1 个机器人玩家，用烟花加鞘翅沿直线飞 120 秒。起点周围半径 16 个 chunk 是预先生成好的缓存区，飞出缓存区以后的地形都要现场生成。
- 原版（vanilla）：不启动 donor 容器，coordinator 功能关闭（`features=v0`）。
- +donor：1 个 donor 容器，绑 4 个核；调度策略是部署时的默认值（`deadline+throughput`，提前量 lookahead 16 个 chunk，每 tick 扫描预算 2000 µs）。
- 原版和 donor 轮交替执行（`order: paired`）。E1 跑在 k2 槽位，E2/E3 跑在 k3 槽位，两个槽位同时跑，但服务器和 worker 用的是互不重叠的核。

## 指标

- **卡住时间占比**（`stall_after_exit`）：飞出缓存区以后，玩家因为前方地形没生成好而被卡住的 tick 占所有 tick 的比例。这是主要指标，越低越好。
- **MSPT p95**（`mspt_p95`）：服务器每 tick 耗时的 95 分位数，单位 ms。超过 50 ms 服务器就会掉 TPS。
- `void_mean`：视距内还没就绪的 chunk 平均比例（辅助指标）。
- `imports_accepted`：服务器接受的 donor chunk 数量。
- `exit_s`：飞出缓存区的时刻（单位秒；33.5 格/秒时是 8 秒）。

## 实验与结果

| 实验 | 配置 | 原版卡住占比 | +1 donor 卡住占比 | 每组轮数 |
| --- | --- | ---: | ---: | --- |
| E1 | 2 核，33.5 格/秒 | 41%（40–43%） | 4%（2–5%） | 3 |
| E2 | 2 核，20 格/秒 | 2.0% | 0% | 1 |
| E2 | 2 核，10 格/秒 | 0% | 0% | 1 |
| E3 | 4 核，33.5 格/秒 | 0.8% | 0% | 1 |

MSPT p95：E1 原版 8.1–8.9 ms，+donor 10.2–12.8 ms；E3 4 核原版 8.1 ms，+donor 13.1 ms。所有轮都远低于 50 ms。
E1 的 donor 轮每轮导入 136–201 个 chunk，donor 4 个核平均只用了约 2.2 个。

### 结论

1. **主结果（E1）**：2 核服务器上，玩家以 33.5 格/秒飞出缓存区后，原版有 41% 的时间被卡住；加 1 个 4 核 donor 后降到 4%，约 10 倍。3 轮的范围不重叠。这个结果和 11-09 的独立批次一致（当时原版也是 41%）。
2. **代价**：加 donor 后服务器 MSPT p95 多了 2–4 ms（导入 chunk 的开销），但离 50 ms 的上限还很远。
3. **速度（E2）**：原版在 20 格/秒以下基本不卡（≤2%），到 33.5 格/秒就崩了。donor 在三种速度下都低于 5%。20 到 33.5 格/秒之间没测，所以原版具体从哪个速度开始崩还不知道。
4. **加核 vs 加 donor（E3）**：服务器从 2 核加到 4 核，原版卡住占比也会降到 0.8%，效果和加 donor 差不多。所以 donor 的价值主要在于**服务器没法加核**的情况（比如租来的小规格云主机），论文里应该照实写。

## 图

- **图 1** `fig1-stall.png`：E1 两组的卡住占比。条形是中位数，横线是 3 轮的最小到最大值。
- **图 2** `fig2-void-timeline.png`：E1 两组各取卡住占比为中位数的那一轮，横轴是飞行时间，纵轴是累计卡住秒数，虚线是飞出缓存区的时刻。原版飞出后几乎一直在卡，累计约 46 秒；donor 只偶尔卡一下，累计约 4 秒。
- **图 3** `fig3-speed.png`：卡住占比随飞行速度的变化（10 和 20 格/秒来自 E2，33.5 格/秒来自 E1 的中位数）；点线是 5% 阈值。
- **图 4** `fig4-cores.png`：33.5 格/秒下，2 核和 4 核各自原版和 +donor 的卡住占比。

## 局限（写论文时要提）

- E2、E3 每组只跑了 1 轮，只能看趋势，不能做统计检验；E1 每组 3 轮。
- 只测了 1 个玩家、直线飞行、1 个 donor、局域网（没加网络延迟）。
- E3 原计划是 3 核，但测试驱动只支持 1/2/4 核，所以改成了 4 核。
- 跑实验时修了 main 上的一个 bug（原版轮停 worker 时会把 minecraft 服务也一起停掉）。修复在 `bench/demo/runner.py` 和 `bench/demo/control.py`，和这份结果一起提交了。这个修复只影响测试脚本，不影响被测系统。
