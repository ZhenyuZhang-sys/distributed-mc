# 第 4 步详细计划：benchmark 第 2 层和实时地图

依据：[开发总计划](../DEV_PLAN.md) 第 4 步、[README](../README.md) 第 2 层定义、[第 1 步计划](step1-plan.md) 已有的测量框架。

状态：**计划已通过（调度 11-08 有条件通过，两条补充已写入 3.3、4.3、4.6）**，等第 1 步合进 main 后开工；4.4、4.6 可以先做。负责人 kexu。

## 1. 目标和产物

测出原版服务器在玩家飞行探索时"眼前地形出来得够不够快"，作为第 6 步以后所有 donor 方案的对照组。

| 问题 | 指标 | 产物 |
| --- | --- | --- |
| 玩家前面有多少空白 | 每秒虚空率 | `analysis/results/layer2.csv`（逐秒） |
| chunk 进入视野后多久能用 | 就绪延迟 p50 / p95 / p99 | `layer2-chunks.csv`（逐 chunk）、`layer2-summary.csv` |
| 服务器卡不卡 | 每秒 MSPT 均值和最大值、容器 CPU | 同 `layer2.csv` |
| 同一配置的噪声多大 | 5 次之间的标准差和 CV | `layer2-summary.csv`、`baseline-layer2.md` |
| 谁生成了哪块 chunk | 按来源上色的地图，能实时看也能回放 | `bench/map/`（网页和小服务器） |

正式矩阵：**3 种速度 × 2 核 × 5 次 = 15 轮**。另加：

- **4 核鞘翅 5 次**（调度 11-08 加）：和 2 核同一套协议，结果单列在 `layer2-summary.csv`。第 6 步正式验收用 2 核，但部署目标 N100 是 4 核，这组作为补充对照。
- 2 组指标自检（见 5.3）。

## 2. 指标定义

所有定义写进 `bench/layer2-protocol.json`，正式跑之前冻结，之后不改。

### 2.1 视野和"就绪"

- **视野**：玩家所在 chunk 为中心、视距 10 的集合。直接用原版的 `ChunkTrackingView.of(center, 10).contains(x, z)` 判断，和原版给玩家发 chunk 的范围完全一样，不自己算方形。1.21.1 源码核实：`contains` 默认把视距边界外紧挨的一圈也算进去（`includeOuterChunksAdjacentToViewBorder = true`），视距 10 时是 **453** 个 chunk；不含外圈是 385 个。用 453，和原版发包一致。
- **视距用服务器视距 10，不用假玩家的请求值**：原版按 `clamp(玩家请求的视距, 2, 服务器视距)` 决定发多少 chunk，而 `ServerPlayer.requestedViewDistance` 默认是 2，要客户端发设置才会改；Carpet 假玩家可能一直是 2。加载和生成由服务器视距的票据决定，不受影响。所以统计里固定用 10，并在 4.2 里记下假玩家实际的请求视距。
- **就绪**：这个 chunk 的 `ChunkHolder.getChunkToSend()` 不为空。这正是原版决定"可以发给玩家"的条件。
- 不看网络：假玩家没有真客户端，发包节流和客户端渲染都不算。所以这里测的是**服务器那一侧的就绪**，也正是 donor 能改善的部分。报告里写清楚。

### 2.2 每秒记录的值

每个 tick 结束时扫一遍视野，每秒汇总一行：

| 列 | 含义 |
| --- | --- |
| `t_s` | 从起飞开始的秒数 |
| `x, z` | 这一秒结束时玩家的位置 |
| `view_chunks` | 视野里 chunk 总数 |
| `void_rate` | 这一秒内各 tick 的"未就绪数 / 视野总数"的平均值 |
| `void_rate_max` | 这一秒内最差的 tick |
| `mspt_mean`、`mspt_max`、`ticks` | 这一秒内 tick 耗时，和实际跑了几个 tick |
| `cpu_cores` | 宿主机读的容器 cgroup CPU（复用第 1 步 `bench/metrics.py`），按墙钟对齐 |

### 2.3 就绪延迟

- 一个 chunk **进入视野**（上一 tick 不在、这一 tick 在）时记下时间；第一次扫到它就绪时记下时间；两者之差就是延迟。
- 进入时已经就绪的，延迟记 0。
- 出视野时还没就绪的，记为**删失**（censored）。算分位数时把删失当成无穷大，所以 p95 是保守的上界；同时单独报删失比例。删失超过 5% 时 p95 直接写"> 本轮最长停留时间"。
- 只统计起飞之后进入视野的 chunk。起飞前的初始视野单独报"初始加载时间"，不混进分位数。
- 同一 chunk 出去又进来，算新的一次。
- 时间用墙钟（`System.nanoTime()`），不是 tick 数：服务器卡的时候，真实玩家照样在动。

### 2.4 飞行怎么动

- 位置 = 起点 + 速度 × 起飞后的墙钟时间，每个 tick 开始时把假玩家放到这个位置。
- DEV_PLAN 写的是"每 tick 前进固定距离"。这里改成按墙钟算：服务器卡到 10 TPS 时，按 tick 走的话玩家也跟着慢一半，虚空率反而被低估；真实客户端不会因为服务器卡就飞慢。**这一条请审阅者确认。**两种方式都实现，协议里选一个；默认墙钟。
- 速度三档：走路 4.3、骑马 14、鞘翅 30 格/秒。方向固定 +x，高度固定 y=200，不受地形碰撞影响。
- 每轮 120 秒，鞘翅一轮飞 3600 格，约 225 个 chunk。
- 起点离出生点和第 1 步区域都远，写进协议。

## 3. 要写的东西

### 3.1 bench mod（Java）

| 类 | 做什么 |
| --- | --- |
| `Flight` | 一次飞行的状态：玩家名、起点、方向、速度、时长、移动方式。纯逻辑，给定时间算位置 |
| `ViewTracker` | 维护视野集合、进入时间、就绪时间、删失。输入是"中心 chunk"和"这个 chunk 就绪没"的函数，纯逻辑，可以单测 |
| `FlightRecorder` | 每 tick 调 `Flight` 和 `ViewTracker`，每秒写一行；chunk 事件写到文件 |
| `ProvenanceLog` | 记录每个 chunk 的来源事件，给地图用（见 3.3） |
| 命令 | `/bench fly start <玩家> <速度> <秒数> <run名> [tick\|wall]`、`/bench fly status`、`/bench fly stop` |

输出文件放在服务器数据目录下 `bench-runs/<run名>/`，宿主机上就是 `docker/test-data/bench-runs/`：

- `seconds.jsonl`：每秒一行（2.2）
- `chunks.jsonl`：每个 chunk 的进入 / 就绪 / 删失事件
- `provenance.jsonl`：每个 chunk 变成完整状态的时间和来源
- `done.json`：飞行结束标记，带汇总计数

用 tick 事件（Fabric API 的 `ServerTickEvents`）驱动，不用 Mixin 改移动逻辑。文件写入放后台线程，主线程只往队列里放。

### 3.2 Python 测量（`bench/layer2.py`）

复用第 1 步的框架：`WorldSession`、`resource_environment`、`Cgroup`/`Sampler`、`LogStream`、归档和 manifest。单轮流程：

1. 新世界，配置 gamerule（和第 1 步一样），核验 `bench timing off`。
2. 预热：远处 Chunky 生成 256 个 chunk（和第 1 步同样的量），等 CPU 稳定。
3. 在起点上方放假玩家（`player bot spawn at ... in creative`，再开飞行），等初始视野全部就绪，记"初始加载时间"。
4. `bench fly start`，同时宿主机开始 cgroup 采样。
5. 等 `done.json`，超时 180 秒判失败。
6. 踢掉假玩家，保存，正常停服，日志检查，归档 `bench-runs/<run>/` 和采样。
7. 离线合成 CSV。

矩阵按第 1 步的方式：固定随机种子打乱顺序，逐槽位 manifest，能 resume，失败尝试全部保留。

### 3.3 实时地图（`bench/map/`）

- 一个静态网页（canvas，一格一个 chunk）加一个 Python 小服务器（标准库 `http.server`，不加依赖），只绑 127.0.0.1。
- 服务器读 `provenance.jsonl` 和 `chunks.jsonl`，提供 `/events?since=N`；网页每 0.5 秒拉一次新事件。
- 颜色三类：**原版**（服务器自己生成）、**donor**（按编号分色）、**被验证打回**。另外用描边标出玩家当前视野和位置。
- 回放：选一个已归档的 run，按事件时间重放，可调速、可拖进度条。
- 第 4 步只有原版来源的真实数据。donor 和打回两类，先用调试命令 `/bench mark <cx> <cz> donor <id>|rejected` 造事件来验证上色。

来源怎么拿：

- 原版生成：用 bench 已有的 `ChunkStatusTasks.full` 注入点（fish 第 3 步已把它细分成 GENERATED / LOADED / RESUMED）。
- donor 导入和打回：只有 coordinator 知道。在 common 里加一个很小的接口 `mcdonate.common.Provenance`：

```java
public final class Provenance {
    public enum Kind { VANILLA, DONOR, REJECTED }
    public interface Listener { void on(int cx, int cz, Kind kind, int donorId); }
    public static void addListener(Listener l);
    public static void report(int cx, int cz, Kind kind, int donorId); // donorId 对原版填 -1
}
```

bench 注册监听写文件；coordinator 写入成功时报 `DONOR`，验证打回时报 `REJECTED`（第 6、8 步 fish 加调用，第 4 步不动 coordinator）。没有 bench 时调用为空操作，不影响正式服务器。

**common 的打包方式和静态状态**：

- bench 和 coordinator 都用 Loom 的 `include project(':common')`，把 common 作为**嵌套 Fabric mod**（jar-in-jar，`META-INF/jars/mcdonate-common-<版本>.jar`，mod id `mcdonate-common`）带进去。不用 shadow，不重定位包名。
- Fabric Loader 按 mod id 去重嵌套 mod：两个 jar 里各带一份时只加载一份（版本不同取最高）。所有 mod 都由同一个 Knot 类加载器加载，所以 `mcdonate.common.Provenance` 只有一个 `Class`，静态监听器列表也只有一份。
- 约束写进计划：任何模块都**不许** shadow / relocate common；common 的版本号由根项目统一。改打包方式必须先过下面的检查。
- 检查（4.3 的 GameTest，装上 bench 和 coordinator 两个 mod 跑）：
  1. `FabricLoader.getAllMods()` 里 `mcdonate-common` 恰好一个。
  2. 用 coordinator 入口类的类加载器 `Class.forName("mcdonate.coordinator.Coordinator").getClassLoader().loadClass("mcdonate.common.Provenance")` 拿到的类，和 bench 里引用的 `Provenance.class` 是同一个对象。
  3. 通过上面拿到的（coordinator 一侧的）类反射调用 `report(cx, cz, DONOR, 7)`，bench 的监听器收到这条事件，并写进 `provenance.jsonl`。
  4. `/bench mark` 也走 `Provenance.report`，不绕过 common 直接写文件。

  这样第 4 步不改 coordinator，也能证明第 6 步 coordinator 一调 `report`，bench 就收得到。

## 4. 拆成小步和验收

先写测试，看它失败，再写功能。

| 小步 | 内容 | 先写的测试 | 完成条件 |
| --- | --- | --- | --- |
| 4.1 | `Flight`、`ViewTracker` 纯逻辑 | JUnit：位置随时间；进入 / 就绪 / 删失；进出再进算两次；初始视野不计入；视野集合和 `ChunkTrackingView` 一致 | JUnit 通过 |
| 4.2 | `FlightRecorder`、命令、文件输出 | GameTest：在测试结构里放一个 ServerPlayer，`fly start` 后位置按速度变化，`seconds.jsonl` 行数和时长对得上 | GameTest 通过 |
| 4.3 | `Provenance` 接口和 `ProvenanceLog` | JUnit：无监听时 report 不报错；监听收到正确事件。GameTest：Chunky 生成后 `provenance.jsonl` 里原版事件数等于 `full_generated`；跨 mod 检查（3.3 的 1～4 条） | 通过 |
| 4.4 | Python 离线合成和统计 | pytest：已知事件算出的虚空率、分位数（含删失）、CSV 列；时间对齐 | 通过 |
| 4.5 | 单轮端到端 | pytest 冒烟（非 slow）：新世界里走路速度飞 15 秒，CSV 有 15 行左右，每行视野数等于 453，日志无 ERROR | 通过 |
| 4.6 | 地图 | pytest（合成数据）：`since` 增量正确；三类上色映射正确。pytest（真实 run，**这条是 DEV_PLAN"能回放生成顺序"的验收**）：4.5 冒烟飞行归档后，用地图服务器的回放接口回放这次 run，返回的事件顺序和 `provenance.jsonl` 的时间顺序一致，其中原版事件数等于这轮 `bench stats` 的 `full_generated`。网页截图只作附加证据，不算验收 | 通过 |
| 4.7 | 指标自检（slow） | 见 5.3 | 两条都满足 |
| 4.8 | 正式矩阵（slow） | 15 轮 + 4 核鞘翅 5 轮全部通过；每配置 5 个有效结果；输出 p50/p95/p99 和 5 次波动 | `scripts/test.sh step4 --slow` 退出码 0 |
| 4.9 | 收尾 | 报告、README 第 2 层数字、DEV_PLAN 勾选 | 文档对上数据 |

测试文件：

```
bench/src/test/java/mcdonate/bench/FlightTest.java、ViewTrackerTest.java
bench/src/main/java/mcdonate/bench/gametest/FlightGameTest.java
common/src/test/java/mcdonate/common/ProvenanceTest.java
tests/step4/test_01_layer2_math.py    纯逻辑
tests/step4/test_02_map.py            地图服务器（合成数据）
tests/step4/test_11_map_replay.py     回放 4.5 的真实 run，自动判定
tests/step4/test_10_smoke.py          短飞行端到端
tests/step4/test_90_baseline.py       slow：自检 + 15 轮
```

fish 加的占位 `tests/step4/test_00_pending.py` 在 4.5 时删掉。

## 5. 实验协议

### 5.1 固定条件

- 资源和第 1 步一样：12–15 核池，2 核用 12-13；内存 6g、堆 4G；CPU 固定 2.9GHz。
- 视距 10、模拟距离 6；seed 424242；每轮全新世界。
- 主矩阵 `bench timing off`。
- 记录 tracker 本身的开销：pilot 里同一配置分别开 / 关逐 tick 扫描，比较 MSPT，差别要小于 2%。

### 5.2 统计

- 每轮先算本轮的虚空率均值、p95 虚空率，以及就绪延迟 p50 / p95 / p99（按 chunk）。
- 每配置对 5 个"本轮值"求均值、标准差、CV 和极差。chunk 不当独立样本。
- 第 4 步只**记录**波动，不设 CV 门槛（DEV_PLAN 原文是"记录"）。走路速度下虚空率可能接近 0，这时 CV 没意义，改报绝对标准差。
- 后面判断"有没有显著变化"：差值要大于对应配置 5 次标准差的 2 倍，这条写进报告给第 6 步用。

### 5.3 指标自检（先于正式矩阵）

两条判据在看数据之前定好：

1. **预生成后再飞**：先用 Chunky 把整条航线（含两侧视距）生成好，重启，2 核鞘翅飞一轮。要求虚空率均值 < 1%，就绪延迟 p95 < 1 秒。
2. **1 核鞘翅**：不预生成，1 核鞘翅飞一轮。要求虚空率均值比第 1 条高出至少 10 个百分点。

过不了说明指标没反映问题，先查指标，不进正式矩阵。

### 5.4 耗时估计

每轮约 1.5 分钟起服 + 预热（按第 1 步冻结的预热量）+ 2 分钟飞行 + 1 分钟停服归档，约 6～8 分钟。15 轮 + 4 核 5 轮 + 自检约 2.5～3 小时，全程持重活锁。

### 5.5 报告里必须带上的背景

第 1 步发现：1.21.1 的后台生成线程数是 `clamp(可用核数 − 1, 1, 上限)`（`Util.makeExecutor`），**1 核和 2 核都只有 1 个生成线程**，4 核有 3 个。所以 2 核服务器上原版生成只用到大约一个核。第 4 步报告的 2 核基线和 4 核对照都要写明这一点，第 6 步解读 donor 的效果时要用：2 核上 donor 的提升，有一部分来自原版本来就没用上的那个核。报告里同时记下每轮容器内 `availableProcessors` 的实际值。

## 6. 和 fish 的接口与分工

| 东西 | 谁 | 说明 |
| --- | --- | --- |
| `mcdonate.common.Provenance` | kexu 加 | common 里新文件，不碰 TileFile、proto 以外的东西 |
| coordinator 报 DONOR / REJECTED | fish，第 6、8 步 | 写入成功、验证打回时各调一次 `Provenance.report` |
| bench mod 计数 | 已有（fish 第 3 步改过） | 第 4 步只新增类，不改 `Counters` 的口径 |
| `tests/mcd.py`、`analysis/mca/` | 不改 | 按调度要求，避免和 fish 冲突 |
| 第 6 步对照 | fish 用 | `layer2-protocol.json` 和 `bench/layer2.py` 就是第 6 步测 donor 效果时的入口，加一个"接 donor"的开关即可 |

## 7. 什么时候开工、在哪做

- bench 的 Java 和 Python 改动会改变第 1 步的代码摘要。所以第 4 步不在第 1 步的目录里做，等第 1 步 45 轮跑完、合进 main 后再开。
- fish 第 3 步也改了 bench mod（计数细分）。第 4 步从**合完第 1 步和 fish 第 3 步之后的 main** 拉出来，在单独的 worktree 里做，避免 bench mod 冲突。
- 如果 fish 第 3 步合 main 晚于第 1 步，先做不依赖 bench mod 的部分（4.4 Python 统计、4.6 地图），再接 Java。

## 8. 风险

| 风险 | 对策 |
| --- | --- |
| 每 tick `teleportTo` 后原版不更新假玩家的视野中心 | 4.2 的 GameTest 先验证；不行就在移动后显式调 `ServerChunkCache.move(player)` |
| `getChunkToSend()` 在 1.21.1 的语义和预想不同（比如还要等邻居） | 4.2 里对照原版实际发包时机核验；定义以原版发包条件为准，写进协议 |
| 1 核时 MSPT 远超 50，按墙钟移动时玩家一 tick 跳很远 | 这是真实情况（客户端照样飞），如实记录；同时记每秒实际 tick 数 |
| 逐 tick 扫约 453 个 chunk 拖慢服务器 | 只查哈希表，预计微秒级；pilot 用开 / 关对照测出来，超过 2% 就改成每 2 tick 扫一次 |
| 走路速度虚空率接近 0，噪声基线没意义 | 改报绝对标准差（5.2） |
| 假玩家的区块加载行为和真玩家不同 | Carpet 假玩家走原版 `ServerPlayer` 和 `ChunkMap` 的路径；第 9 步有真客户端后再抽查一次 |
