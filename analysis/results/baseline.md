# 第 1 步：原版生成基线（粗测）

Campaign：`baseline-20261108T154535`；协议：`952f8cca3812a01135c73f499bbc254a79c0a03f9e4dd30e631be55bd2c98e24`。

> **粗测**，在这台 EPYC 上限核测得，部署机器 N100 上要重测。用户 11-08 决定第 1 步只粗测（"到时候生成也不是这台服务器，简单测一下，保留 config 到时候可以调，先把功能做好"），调度 distributed-mc-77 确认后提前结束。
> 完成 21 / 45 轮；每配置至少 2 轮。轮数、预热量、阶段分析槽位都在 `bench/protocol.json`，换机器后重新 pilot 再跑满。

硬件为 AMD EPYC 7542，使用同一物理核池的 1/2/4 核配额，容器 6g、堆 4G；这不是 N100 实测。
Minecraft 1.21.1 / Java 21 / Fabric 0.16.14，固定工具模组和 bench mod。主矩阵阶段计时关闭，计数保留。

| 区域 | 核数 | n | chunk/s 均值 | CV | CPU ms/chunk 均值 | 吞吐倍数 |
|---|---:|---:|---:|---:|---:|---:|
| ocean | 1 | 3 | 9.563 | 4.48% | 103.983 | 1.000 |
| ocean | 2 | 3 | 9.527 | 1.15% | 114.967 | 0.996 |
| ocean | 4 | 2 | 23.954 | 0.42% | 88.655 | 2.505 |
| mountain | 1 | 2 | 6.850 | 0.54% | 145.234 | 1.000 |
| mountain | 2 | 3 | 7.742 | 3.73% | 149.924 | 1.130 |
| mountain | 4 | 2 | 15.857 | 5.43% | 125.064 | 2.315 |
| jungle | 1 | 2 | 8.100 | 0.86% | 122.662 | 1.000 |
| jungle | 2 | 2 | 8.844 | 5.12% | 124.923 | 1.092 |
| jungle | 4 | 2 | 19.866 | 2.79% | 100.551 | 2.453 |

21 轮独立新世界。误差条为 run 间样本标准差，CV 门槛为 10%。原始慢样本未剔除。n=2 时 CV 只是粗略量级。

15 对预先固定的比较（同核两轮、跨 1/4 核同 repeat；粗测另把 2 核同核单列 repeat_2cpu）。三档为 0、1–4、5–256 列不同。
仅比较完整高度 block states，不包含实体、方块实体、光照或 tick；配对复用部分世界，chunk 不视为独立重复。

| 区域 | 比较组 | 对数 | 整块一样 | 只差几列 | 差很多 | 平均 chunk 差异率 | 平均列差异率 |
|---|---|---:|---:|---:|---:|---:|---:|
| ocean | repeat_1cpu | 1 | 181 | 17 | 58 | 29.297% | 2.879% |
| ocean | repeat_4cpu | 1 | 166 | 22 | 68 | 35.156% | 3.650% |
| ocean | cross_1_4cpu | 2 | 354 | 35 | 123 | 30.859% | 2.512% |
| ocean | repeat_2cpu | 1 | 168 | 22 | 66 | 34.375% | 3.073% |
| mountain | repeat_1cpu | 1 | 110 | 8 | 138 | 57.031% | 24.522% |
| mountain | repeat_4cpu | 1 | 86 | 8 | 162 | 66.406% | 30.077% |
| mountain | cross_1_4cpu | 2 | 151 | 23 | 338 | 70.508% | 31.825% |
| mountain | repeat_2cpu | 1 | 82 | 11 | 163 | 67.969% | 31.546% |
| jungle | repeat_1cpu | 1 | 14 | 2 | 240 | 94.531% | 79.692% |
| jungle | repeat_4cpu | 1 | 56 | 3 | 197 | 78.125% | 56.578% |
| jungle | cross_1_4cpu | 2 | 24 | 7 | 481 | 95.312% | 79.147% |
| jungle | repeat_2cpu | 1 | 8 | 0 | 248 | 96.875% | 79.477% |

不同的方块位置共 1772758 个。按方块类型（前 10）：

| 区域 | 一侧 | 另一侧 | 位置数 |
|---|---|---|---:|
| jungle | `minecraft:oak_leaves` | `minecraft:air` | 213419 |
| jungle | `minecraft:air` | `minecraft:oak_leaves` | 205337 |
| jungle | `minecraft:air` | `minecraft:jungle_leaves` | 171381 |
| jungle | `minecraft:jungle_leaves` | `minecraft:air` | 170800 |
| jungle | `minecraft:vine` | `minecraft:air` | 142179 |
| jungle | `minecraft:air` | `minecraft:vine` | 141923 |
| mountain | `minecraft:deepslate` | `minecraft:sculk` | 55041 |
| mountain | `minecraft:sculk` | `minecraft:deepslate` | 51221 |
| mountain | `minecraft:sculk_vein` | `minecraft:air` | 41847 |
| mountain | `minecraft:air` | `minecraft:sculk_vein` | 40961 |

按 Y 层：

- ocean：29092 个位置，Y -60～62，中位数 41
- mountain：285442 个位置，Y -63～170，中位数 -32
- jungle：1458224 个位置，Y -63～158，中位数 113

明细在 campaign 的 `block-differences.jsonl` 和 `difference-summary.json`。

阶段分析另做 1 轮 timing on（ocean 4 核），仅单次描述性分析。stages.csv 的 cpu_share 分母为已记录阶段 CPU 合计；wall_ns 为多次调用累计，light_engine.count 为批次数。

| 区域 | 核数 | 阶段 | CPU 占比 |
|---|---:|---|---:|
| ocean | 4 | noise | 63.1% |
| ocean | 4 | surface | 14.6% |
| ocean | 4 | biomes | 10.3% |
| ocean | 4 | features | 6.2% |
| ocean | 4 | light_engine | 2.0% |
| ocean | 4 | carvers | 1.9% |
| ocean | 4 | structure_starts | 1.9% |
| ocean | 4 | structure_references | 0.1% |
| ocean | 4 | initialize_light | 0.0% |
| ocean | 4 | spawn | 0.0% |
| ocean | 4 | light | 0.0% |
| ocean | 4 | full | 0.0% |

## 说明

- 1 核和 2 核时世界生成线程都只有 1 个（clamp(可用核数-1, 1, …)），4 核时 3 个；GC 1 核 Serial、2/4 核 G1；JIT 编译线程 2/2/3。所以 1→2 核提速很小，4 核跳一大截。这是 JVM 和 MC 默认行为，部署机器上同样如此。
- 预热按核数：1 核 4096，2/4 核 2048（pilot 校准见 pilot-20261108T105432 和 protocol.json 的 warmup_criterion）。1 核 jungle 在 2048→4096 仍快了 15.5%，1 核数据可能预热不充分；原计划的 8192 敏感性检查跳过。
- pilot 偏离：原定双侧 ≤5% 判据四轮都没过，改为单侧判据（调度批准，看过数据后修订，见 deviations.json）；我的监视脚本曾按已撤回的判据杀掉一次 pilot，同 campaign 内续跑恢复。
- 前两次正式 campaign 作废：baseline-20261108T142114 受 fish 并发测试干扰（ocean 2 核一轮停顿 69.5 秒），baseline-20261108T150513 被中断后残留的 Chunky 任务卡住。残留问题已修（换新世界前移走残留任务，chunky start 被拒时立刻报错）。

## 跳过 / 降级

- 45 轮只跑了 21 轮：每配置 2～3 轮，CV 只是粗略量级。
- 27 对确定性比较改为用已有轮次能配出的对（同核 1 对 2、跨 1/4 核同 repeat，另把 2 核单列）。
- 9 轮阶段分析降为 1 轮（ocean 4 核）。
- 1 核 jungle 预热 8192 的敏感性检查没做。
- 区域大小仍固定 16×16（256 chunk），没有做成配置；轮数（repeats）、预热量（warmup_by_cpu）、阶段分析槽位（profile_slots）已在 protocol 里可配。

X 的候选初始目标为 2 个 donor 时 p95 就绪延迟改善 2 倍，尚待第 4/6 步验证。采用该目标的条件是 donor 有效吞吐覆盖探索需求，且通信、排队、验证和写入开销没有耗尽收益；本次吞吐倍数不等于已测得的 X。Y/Z 仍待测。

运行归档保留协议、命令、日志、CPU 原始采样、完整快照、hash 和逐方块差异。离线重算不会访问 Docker。
