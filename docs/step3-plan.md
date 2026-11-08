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

所以 worker 把一个 4×4 tile 生成到 `spawn` 后：

- **tile**：状态 `spawn`，只差最后一步 `full`。
- **第 1 圈**：状态 `initialize_light`，自己的地物已经放好，还带着 tile 写过来的方块。
- **第 2 圈**：状态 `carvers`，带着第 1 圈写过来的方块（比如树冠）。
- 更外圈：只有结构起点和生物群系，便宜、每次算都一样，不导出。

导出 tile 和这两圈，一共 8×8 = 64 个 chunk。只有 16 个是成品，其余 48 个是半成品；服务器之后会接着把它们生成完。

**为什么导出到 `spawn` 而不是 `full`**：`spawn` 之前的 chunk 是"半成品"格式（`ProtoChunk`），生物、方块实体、光照、待处理的方块更新都在同一份 NBT 里。成品 chunk 的生物存在另一个目录（`entities/`），处理起来麻烦得多。服务器读到 `spawn` 状态的 chunk，只需要跑 `full` 这一步，2.5 实测它几乎不花 CPU。

## 3.1 worker 服务器

- `docker/compose.test.yaml` 加一个 `worker` 服务：
    - 同一个镜像，同一个 seed，端口不对外；
    - 自己的存档目录 `docker/test-data-worker/`；
    - 绑另一组核（12-15），模拟玩家的电脑，不抢服务器的 8-11；
    - 和服务器共用一个交换目录 `docker/test-data/exchange/`（worker 写，服务器读）。
- mod 分开装：服务器装 coordinator + bench；worker 装 worker + bench（bench 用来数 worker 生成了多少）。Gradle 加 `collectWorkerMods`，产物在 `build/mods-worker/`。
- `scripts/rcon.sh` 加 `--worker`，给 worker 发命令；`new_world.sh` 同时给两台换全新世界。
- pytest 加 `worker` fixture。

验收：

- [ ] 两台一起起来，worker 日志里有 `mcdonate-worker loaded`，没有 `mcdonate-coordinator`
- [ ] 两台的 `seed` 都是 424242；服务器的 25566 端口不受影响
- [ ] 第 0、2 步的测试全部仍然通过

## 3.2 导出文件格式（common）

- 一个 tile 一个文件，压缩 NBT：`{format: 1, dataVersion, seed, tile: [tx, tz], chunks: [{x, z, status, data}]}`，`data` 就是原版 `ChunkSerializer.write` 的结果。
- tile 坐标：tile (tx, tz) 包含 chunk x ∈ [4tx, 4tx+3]、z ∈ [4tz, 4tz+3]；负数向下取整。
- 读文件时拒绝：格式版本不对、`dataVersion` 和服务器不同、seed 不同、chunk 不在 tile 和两圈之内。

验收（JUnit）：

- [ ] 写入再读出，内容不变
- [ ] 上面每一种拒绝情况都会报错，不会读进半个文件
- [ ] 负数 tile 坐标换算正确

## 3.3 worker mod：`/donor export <tx> <tz>`

- 在主线程上把 tile 的 16 个 chunk 生成到 `spawn`（`ServerChunkCache.getChunk(x, z, SPAWN, true)`）。
- 生成完立刻在主线程上用 `ChunkSerializer.write` 把 tile 和两圈序列化，写到交换目录（先写临时文件再改名，避免服务器读到半个文件）。
- 记录每个 chunk 的状态，日志打一行汇总。

验收：

- [ ] 导出文件里正好 64 个 chunk：16 个 `spawn`，第 1 圈 20 个状态至少是 `initialize_light`，第 2 圈 28 个状态至少是 `carvers`
- [ ] 同一个 tile 在两个全新的 worker 世界里各导出一次，tile 内的方块逐列 hash 一致（扣除第 1 步测出的不确定率）

## 3.4 coordinator mod：`/donor import <文件名>`

- 只读交换目录里的文件，文件名不能带路径。
- 在主线程上逐个 chunk 检查后写入（`ChunkMap.write`，走原版的存档读写线程，读写顺序有保证）：
    - 服务器内存里有这个 chunk（已加载、正在生成、等待卸载），**永远不写**；
    - 存档里已有的，只有导入的版本状态更靠后才覆盖；
    - 写完更新 `ChunkMap` 里记录 chunk 类型的缓存（`markPosition`，用 Mixin 调私有方法）。
- 每个 chunk 的结果（写入 / 跳过及原因）汇总成一行 JSON 返回，日志里也记一条。

验收：

- [ ] 导入后存档里 tile 内的 chunk 和导出文件逐列 hash 完全一致，状态是 `spawn`
- [ ] 对已加载的 chunk 导入：被拒绝，记一条日志，存档不变
- [ ] 同一个文件导入两次：第二次全部跳过，存档不变
- [ ] 文件名带 `../`、文件格式不对、seed 不对：拒绝，不写任何东西

## 3.5 计数口径

2.4 的 `full_generated` 只看 chunk 进入 `full` 时是不是成品。导入的 chunk 是 `spawn` 状态的半成品，进入 `full` 时会被算成"新生成"，但服务器其实只做了最后一步。

- 改法：bench 记下哪些半成品是从存档读进来的（读的时候打个标记）。从存档读进来的 `spawn` 状态 chunk 完成 `full` 时，算 `full_loaded`。
- 另外 `/bench stats` 加 `features` 计数：服务器自己跑了多少次地物阶段。验收靠它和 `noise` 判断服务器有没有重复生成。

验收：

- [ ] 2.4 的测试全部仍然通过
- [ ] 导入 tile 后传送假玩家到 tile 中心：这 16 个 chunk 不增加 `noise`、`features`

## 3.6 端到端（关卡）

对照世界：服务器换一个全新世界（同一个 seed），用 Chunky 生成同一块区域，作为原版的标准答案。

- [ ] 导入区域和对照世界比，差异率不高于第 1 步测出的原版不确定率
- [ ] 把假玩家传送到 tile 中心：这 16 个 chunk 的"新生成"计数不增加
- [ ] 假玩家走出 tile，服务器自己生成邻居。邻居和对照世界比，差异率同样不高于不确定率：树、矿、洞穴在边界不断开
- [ ] 停服重启，导入的 chunk 正常加载，日志里没有 ERROR

**关卡判断**：只是边界接不上，先试"只导入 tile 内部、两圈只用来算"。整体过不了，就切路线 B，只换 worker 和写入两块。

## 依赖和风险

- **存档读取器**：3.3、3.4、3.6 的比较都要用 kexu 的 `analysis/mca/`（读 `.mca`，算逐列 hash）。读取器到之前，这几项会 `skip`。
- **原版不确定率**：3.6 的阈值来自第 1 步。拿到之前先用"完全一致"当目标，不一致的地方记下来，等第 1 步的数出来再判断。
- **相邻 tile 的半成品冲突（留到第 5、6 步）**：两个相邻 tile 的外圈会重叠，同一个半成品 chunk 里带着不同邻居写过来的方块。现在的规则"状态更靠后才覆盖"会丢掉一边写过来的方块。第 3 步只导单个 tile，不碰这个问题；第 5 步调度时要么让相邻 tile 不同时生成，要么按方块合并。
- **主线程阻塞**：导入时要先读存档看已有状态，第 3 步先在主线程上等读取结果。这只是调试命令，第 5 步再改成异步。

## 顺序

3.1 → 3.2 → 3.3 → 3.4 → 3.5 → 3.6。3.2 和 3.5 互不依赖，可以穿插着做。
