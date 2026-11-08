# 第 3 步详细计划：手动打通导出 → 导入（关卡）

负责人：fish（分支 `fish`）。依赖 kexu 的存档读取器（`analysis/mca/`）和第 1 步的原版不确定率。

这一步不写网络、不写调度，只用调试命令证明最难的一环可行：**worker 生成的 chunk 写进正在运行的服务器后，和服务器自己生成的没有区别**。过不了就切路线 B。

## 关键事实（已在 1.21.1 源码里确认）

生成一个 chunk 会连带改动周围的 chunk（`ChunkPyramid.GENERATION_PYRAMID`）：

| 阶段 | 需要邻居 | 往邻居写方块 |
| --- | --- | --- |
| noise、surface、carvers | 结构起点（半径 8）、生物群系（半径 1） | 不写 |
| features（树、矿、地物） | 半径 1 的邻居到 carvers | **写，半径 1** |
| light | 半径 1 的邻居到 initialize_light | 不写 |
| spawn | 半径 1 的邻居到 biomes | 不写 |

**导出目标是 `initialize_light`**（做 3.3 时改的，原计划是 `spawn`）：

- 原版的票等级只能让 chunk 停在 `initialize_light`，再往上就直接做成 `full` 了（`ChunkLevel.byStatus`：`spawn` 和 `full` 是同一级）。要停在 `spawn` 得用 Mixin 拦原版的"自动做成成品"，不值得。
- 不导出成品：成品 chunk 的生物存在另一个目录（`entities/`），半成品的生物、方块实体、光照、待处理的方块更新都在同一份 NBT 里。
- 服务器拿到后只剩光照、生怪、`full` 三步，按 2.5 实测约占生成 CPU 的 2%。地形噪声、表面、洞穴、地物都在 worker 上做完。

worker 把一个 4×4 tile 生成到 `initialize_light` 后：

- **tile**：16 个，状态 `initialize_light`，自己的地物已经放好。
- **紧挨着的一圈**：20 个，状态 `carvers`，带着 tile 写过来的方块（树冠、矿脉）。
- 更外圈：只有结构起点和生物群系，便宜、每次算都一样，不导出。

导出 6×6 = 36 个 chunk。服务器要把 tile 做成成品时，光照阶段要求这一圈也放完地物，所以这一圈的地物由服务器自己做（或者由相邻 tile 的导入提供）。

## 3.1 worker 服务器

- `docker/compose.test.yaml` 加一个 `worker` 服务：
    - 同一个镜像，同一个 seed，端口不对外；
    - 自己的存档目录 `docker/test-data-worker/`；
    - 绑另一组核（8-11），模拟玩家的电脑，不抢服务器的 12-15；
    - 和服务器共用一个交换目录 `docker/test-data/exchange/`（worker 写，服务器读）。
- mod 分开装：服务器装 coordinator + bench；worker 装 worker + bench（bench 用来数 worker 生成了多少）。Gradle 加 `collectWorkerMods`，产物在 `build/mods-worker/`。
- `scripts/rcon.sh` 加 `--worker`，给 worker 发命令；`new_world.sh` 同时给两台换全新世界。
- pytest 加 `worker` fixture。

验收：

- [x] 两台一起起来，worker 日志里有 `mcdonate-worker loaded`，没有 `mcdonate-coordinator`
- [x] 两台的 `seed` 都是 424242；服务器的 25566 端口不受影响
- [x] 第 0、2 步的测试全部仍然通过

## 3.2 导出文件格式（common）

- 一个 tile 一个文件，压缩 NBT：`{format: 1, dataVersion, seed, tile: [tx, tz], chunks: [{x, z, status, data}]}`，`data` 就是原版 `ChunkSerializer.write` 的结果。最多一圈（`TileFile.MAX_RING = 1`）。
- tile 坐标：tile (tx, tz) 包含 chunk x ∈ [4tx, 4tx+3]、z ∈ [4tz, 4tz+3]；负数向下取整。
- 读文件时拒绝：格式版本不对、`dataVersion` 和服务器不同、seed 不同、chunk 不在 tile 和一圈之内、坐标或状态和数据里的对不上、重复。

验收（JUnit）：

- [x] 写入再读出，内容不变
- [x] 上面每一种拒绝情况都会报错，不会读进半个文件
- [x] 负数 tile 坐标换算正确

## 3.3 worker mod：`/donor export <tx> <tz>`

- 在主线程上把 tile 的 16 个 chunk 生成到 `initialize_light`（`ServerChunkCache.getChunk(x, z, INITIALIZE_LIGHT, true)`）。
- 生成完立刻在主线程上用 `ChunkSerializer.write` 把 tile 和一圈序列化，写到交换目录（先写临时文件再改名，避免服务器读到半个文件）。
- 有成品 chunk（玩家附近、出生点）就拒绝。
- 记录每个 chunk 的状态，日志打一行汇总。

验收：

- [x] 导出文件里正好 36 个 chunk：16 个 `initialize_light`，一圈 20 个状态至少是 `carvers`、不是成品
- [x] tile 里有成品 chunk 时拒绝导出，不留文件
- [x] ~~同一个 tile 在两个全新的 worker 世界里各导出一次，tile 内的方块逐列 hash 一致~~：由 3.6 覆盖。3.6 拿服务器（接着做完导入的 tile）和另一个全新世界（原版从头生成）逐列比，检查的是同一件事，而且更直接

## 3.4 coordinator mod：`/donor import <文件名>`

- 只读交换目录里的文件，文件名不能带路径。
- 在主线程上逐个 chunk 检查后写入（`ChunkMap.write`，走原版的存档读写线程，读写顺序有保证）：
    - 服务器内存里有这个 chunk（已加载、正在生成、等待卸载），**永远不写**；
    - 存档里已有的，只有导入的版本状态更靠后才覆盖；
    - 写完更新 `ChunkMap` 里记录 chunk 类型的缓存（`markPosition`，用 Mixin 调私有方法）。
- 每个 chunk 的结果（写入 / 跳过及原因）汇总成一行 JSON 返回，日志里也记一条。

验收：

- [x] 导入后存档里 tile 内的 chunk 和导出文件逐列 hash 完全一致，状态是 `initialize_light`（36 个 chunk 全部一致；读取器加了 `incomplete=True` 读半成品）
- [x] 对已加载的 chunk 导入：被拒绝，记一条日志，存档不变
- [x] 同一个文件导入两次：第二次全部跳过，存档不变
- [x] 文件名带 `../`、文件格式不对、seed 不对：拒绝，不写任何东西

## 3.5 计数口径

2.4 的 `full_generated` 只看 chunk 进入 `full` 时是不是成品。导入的 chunk 是 `initialize_light` 状态的半成品，进入 `full` 时会被算成"新生成"，但服务器其实只做了光照、生怪和最后一步。

- 改法：bench 记下哪些半成品是从存档读进来的（读的时候打个标记），完成 `full` 时单独算 `full_imported`。
- 另外 `/bench stats` 加 `features` 计数：服务器自己跑了多少次地物阶段。验收靠它和 `noise` 判断服务器有没有重复生成。

验收：

- [x] 2.4 的测试全部仍然通过
- [x] 导入 tile 后传送假玩家到 tile 中心：这 16 个 chunk 不增加 `noise`、`features`（`/bench track on` + `/bench chunk x z`；导入的 chunk 完成时算 `full_resumed`）

## 3.6 端到端（关卡）

对照世界：服务器换一个全新世界（同一个 seed），用 Chunky 生成同一块区域，作为原版的标准答案。

- [x] 导入区域和对照世界比，按下面的两层比较（原来是临时阈值 3%，第 1 步的不确定率出来后换掉）
- [x] 把假玩家传送到 tile 中心：这 16 个 chunk 的"新生成"计数不增加（由 3.5 的按 chunk 计数验证）
- [x] 假玩家走出 tile，服务器自己生成邻居。邻居和对照世界比，差异率同样不高于不确定率：树、矿、洞穴在边界不断开（实测边界 0.68%、外圈 1.6～1.9%，差异是地下矿团，判断是原版放地物先后不同；另查"边界不比外圈差"）
- [x] 停服重启，导入的 chunk 正常加载，日志里没有 ERROR

两层比较（`analysis/mca/decor.py`，11-08 起替换临时阈值 3%）。原版同一个 seed 生成两次，差异几乎全在地物阶段（kexu 第 1 步：jungle 73.6% 的列不同），所以：

- **严格层，必须是 0**：地物放的"装饰类"方块（树、草、矿团、sculk、海带、矿井结构件……）和列出的几对地形互换不算，其余逐块一致。空气对石头（导入丢块、错位、截断的样子）永远是严格差异。清单用 kexu 的 177 万处原版差异和本地对照补全；每轮测试里没导入的部分本身就是原版互比，也要求严格层为 0，等于每轮复查清单。
- **统计层**：
    - 装饰类：导入部分的方块差异率 ≤ 同一轮没导入部分的 1.5 倍（下限取原版最小值 0.023%）。
    - 地形互换（石头/泥土/沙砾/水等列出的组合）：很少、成团（一个矿团几十个方块），按方块比例比全看一个矿团落在哪，所以**按"处"数**（6 邻接相连算一处）：导入部分处数 ≤ max(没导入部分按面积折算 × 1.5, 1)，横跨两边的算导入部分的。
    - **每一处不超过 66 个方块**（原版单个矿团大小的 2 倍：1.21.1 `OreFeatures` 里 `ore_dirt`、`ore_gravel` 的 size 都是 33），超过算严格差异。理由：处数下限是 1，不限大小的话，导入写错的一整块也能混过去。（调度 11-08 定）
- tile 中间 2×2 和 donor 逐块一致（同一份数据，不受原版噪声影响），保留。

**关卡判断**：只是边界接不上，先试"只导入 tile 内部，那一圈只用来算、不导入"。整体过不了，就切路线 B，只换 worker 和写入两块。

## 依赖和风险

- **存档读取器**：3.3、3.4、3.6 的比较都要用 kexu 的 `analysis/mca/`（读 `.mca`，算逐列 hash）。读取器到之前，这几项会 `skip`。
- **原版不确定率**：3.6 的阈值来自第 1 步。拿到之前先用"完全一致"当目标，不一致的地方记下来，等第 1 步的数出来再判断。
- **相邻 tile 的半成品冲突（留到第 5、6 步）**：两个相邻 tile 的那一圈会和对方的 tile 重叠，同一个半成品 chunk 里带着不同邻居写过来的方块。现在的规则"状态更靠后才覆盖"会丢掉一边写过来的方块。第 3 步只导单个 tile，不碰这个问题；第 5 步调度时要么让相邻 tile 不同时生成，要么按方块合并。
- **主线程阻塞**：导入时要先读存档看已有状态，第 3 步先在主线程上等读取结果。这只是调试命令，第 5 步再改成异步。

## 顺序

3.1 → 3.2 → 3.3 → 3.4 → 3.5 → 3.6。3.2 和 3.5 互不依赖，可以穿插着做。
