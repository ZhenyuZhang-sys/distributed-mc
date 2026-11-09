# 第 2 层基线：飞行时的卡住、虚空率和就绪延迟（冒烟）

**冒烟，在这台 EPYC 上限核测得，部署机器 N100 上要重测。** 降级（调度 11-08）：每种速度 1～2 次，只能看量级；部署机器上把 `bench/layer2-protocol.json` 的 repeats 改成 5、rerun_on_interference 改成 true 跑满。

用户决定（11-09）：用户 11-09："没必要这么精准，跑个大概测试，直接跑"：粗测，允许并行。有并发的轮次照常算、标"有并发"，不判无效、不重跑；两条自检有并发也照常判

campaign `layer2-20261109T031723`。速度 walk 4.3 格/秒, horse 14 格/秒, elytra 30 格/秒；视距 10，模拟距离 6；每轮 120 秒，wall 模式，高度 200，沿 +x；seed 424242，每轮全新世界。定义见 docs/step4-plan.md 第 2 节。

## 指标自检（计划 5.3，判据在看数据之前定）

| 自检 | 判据 | 虚空率均值 | 就绪 p95（毫秒） | 结论 |
| --- | --- | --- | --- | --- |
| 预生成后再飞（2 核鞘翅） | 虚空率 < 1%，p95 < 1000 毫秒 | 0.00619 | 298 | 通过 |
| 1 核鞘翅、不预生成 | 虚空率比上一条高至少 10 个百分点 | 0.82 | ∞（有删失） | 通过 |

## 每轮

| 槽位 | 核数 | availableProcessors | 卡住（秒，占比） | 实际 / 理想距离 | 虚空率均值 | 虚空率 p95 | 就绪 p50 / p95 / p99（毫秒） | 删失比例 | MSPT 均值 / 最大 | 服务器 CPU（核） | 同步加载 | 并发 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| selfcheck-pregen | 2 | 2 | 0（0） | 1 | 0.00619 | 0.0132 | 0 / 298 / 444 | 0 | 7.38 / 95 | 0.837 | 0 | 有（15 个事件） |
| selfcheck-one_core | 1 | 1 | 44.3（0.369） | 0.631 | 0.82 | 0.873 | ∞（有删失） / ∞（有删失） / ∞（有删失） | 0.76 | 3.96 / 133 | 0.999 | 0 | 无 |
| elytra-c2-r1 | 2 | 2 | 35.9（0.299） | 0.701 | 0.816 | 0.871 | ∞（有删失） / ∞（有删失） / ∞（有删失） | 0.751 | 3.15 / 136 | 1.22 | 0 | 有（13 个事件） |
| elytra-c4-r1 | 4 | 4 | 0.513（0.00427） | 0.996 | 0.573 | 0.704 | 6.11e+03 / ∞（有删失） / ∞（有删失） | 0.464 | 5.45 / 102 | 2.34 | 0 | 有（2 个事件） |
| horse-c2-r1 | 2 | 2 | 0（0） | 1 | 0.558 | 0.733 | 1.82e+04 / ∞（有删失） / ∞（有删失） | 0.492 | 3.67 / 111 | 1.25 | 0 | 无 |
| elytra-c2-r2 | 2 | 2 | 40.4（0.336） | 0.664 | 0.817 | 0.872 | ∞（有删失） / ∞（有删失） / ∞（有删失） | 0.751 | 2.96 / 155 | 1.23 | 0 | 无 |
| walk-c2-r1 | 2 | 2 | 0（0） | 1 | 0.0125 | 0.0243 | 0 / 2.3e+03 / 2.47e+03 | 0 | 2.88 / 51 | 0.817 | 0 | 有（6 个事件） |

## 每个配置（只算冒烟矩阵，不含自检）

1 次的配置不报波动；2 次的报两次之差。差值能不能算显著（> 5 次标准差的 2 倍，计划 5.2）要等部署机器上的完整矩阵。

| 速度 | 核数 | 次数 | 卡住时长均值（秒） | 两次之差 | 实际 / 理想距离 | 虚空率均值 | 两次之差 | 就绪 p95 均值（毫秒） | 两次之差 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| elytra | 2 | 2 | 38.1 | 4.48 | 0.682 | 0.816 | 0.000762 | ∞（有删失） | — |
| elytra | 4 | 1 | 0.513 | — | 0.996 | 0.573 | — | ∞（有删失） | — |
| horse | 2 | 1 | 0 | — | 1 | 0.558 | — | ∞（有删失） | — |
| walk | 2 | 1 | 0 | — | 1 | 0.0125 | — | 2.3e+03 | — |

## 背景和局限

- 1.21.1 的后台生成线程数是 `clamp(可用核数 − 1, 1, 上限)`：1 核和 2 核都只有 1 个生成线程，4 核有 3 个（第 1 步发现，计划 5.5）。2 核基线上原版生成只用到大约一个核；第 6 步解读 donor 的效果时要记住这一点。每轮容器里实际的 availableProcessors 见上表。
- 就绪是服务器那一侧的：`ChunkHolder.getChunkToSend()` 不为空（sendSync 完成且到 BLOCK_TICKING）。假玩家没有真客户端，不算网络和渲染。
- **假玩家像真实客户端一样飞**（11-09）：客户端不会走进自己还没收到的 chunk（`LocalPlayer.tick` 先查 `hasChunkAt`），所以飞行按速度积分前进，前面的 chunk 没就绪就停在边缘等。**卡住**的时长（玩家停在边缘的时间）是真实玩家最直接的感受，和实际距离对比理想距离一起列出；第 10 步的 X 仍按就绪 p95 算。原来按"起点 + 速度 × 时间"直接放玩家，假玩家会跑到生成前面，实体 tick 查脚下流体（`Entity.updateFluidHeightAndDoFluidPushing` → `Level.getFluidState`）时在主线程上同步生成，2 核鞘翅一个 tick 卡满 60 秒被看门狗杀掉（fish 6.6）。
- **同步加载**是守护计数：飞行期间主线程上 `ServerChunkCache.getChunk(load=true)` 耗时 ≥ 1 毫秒的次数。玩家实体 tick 里的（上表"同步加载"列）真实玩家不会造成，不为 0 的一轮判无效重跑。其余的是原版自己的（流体 tick 或 chunk 变成可 tick 时的后处理读到还没做完的邻居），真实玩家的服务器上也有，只记录在 layer2-runs.csv 的 sync_loads_world。
- 扫视野的间隔 scan_every_ticks = 1。扫描耗时不计进 MSPT，但占服务器时间：每 tick 平均扫描 0.05～0.42 毫秒，是 MSPT 均值的 1.2%～10.5%（MSPT 本身只有几毫秒），占 50 毫秒 tick 预算的 0.1%～0.8%。计划 5.1 的判据是 pilot 里开 / 关逐 tick 扫描比 MSPT、差别 < 2%；冒烟没做这组对照（降级），部署机器上跑满前要补，超过就改成每 2 tick 扫一次。
- 就绪延迟只算起飞后进入视野、离结束超过 10 秒的 chunk；删失（出视野时还没就绪）按无穷大算，分位数是保守的上界。
- 冒烟测量不要求机器安静（降级）。
- **有并发**的轮次（飞行期间别的容器有生命周期事件），照样保留：selfcheck-pregen：mc-test-f3-minecraft-1（stop、die、destroy）, mc-test-f3-worker2-1（stop、die、destroy）, mc-test-f3-worker-1（stop、die、destroy）, funny_shtern（create、start、die、destroy）, mc-test-f3-minecraft-run-cc4480207efe（create、start）；elytra-c2-r1：mc-test-f3-worker2-1（die）, mc-test-f3-worker-1（die）, mc-test-minecraft-1（stop、die、destroy、start）, 4aade4ae33ed_mc-test-worker2-1（create）, 907b26a8a221_mc-test-worker-1（create）, aaa99ef0f84a_mc-test-minecraft-1（create）, mc-test-worker2-1（destroy、start）, mc-test-worker-1（destroy、start）；elytra-c4-r1：mc-test-worker-1（die、start）；walk-c2-r1：wonderful_swanson（die、destroy）, mc-test-minecraft-run-e9fb72f6c25b（create、start）, mc-test-f3-worker-1（start）, mc-test-f3-worker2-1（start）。完整事件在 layer2-runs.csv 的 concurrent 列。是否重跑由读者决定。
