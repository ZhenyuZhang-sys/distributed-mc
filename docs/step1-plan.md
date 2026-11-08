# 第 1 步详细计划：基线测量

依据：[开发总计划](../DEV_PLAN.md)、[第 0 步详细计划](step0-plan.md)，以及当前 `scripts/`、`tests/` 和 `docker/` 的实现。本文件是第 1 步的当前实施依据；根目录的 `STEP1_PLAN.md` 保留为早期草案。

状态：**计划，尚未实现、尚未完成测量**。下面新增的模块、命令和测试均为待实现内容。第 0 步已完成并合入 main，同步上游后的正式验收为 87 项通过、无 skip，记录见 [第 0 步](step0-plan.md)。

## 1. 目标、范围和最终产物

在固定 seed、区域和软件版本下，测清楚服务器自己生成地形的成本，为后续 donor 方案提供对照。

| 要回答的问题 | 指标 | 交付物 |
| --- | --- | --- |
| 每秒能生成多少目标 chunk？ | `chunks_per_s` | `analysis/results/layer1.csv` |
| 每个目标 chunk 摊销多少 CPU 时间？ | `cpu_ms_per_chunk` | 同一份逐轮 CSV，另有 9 组统计汇总 |
| 同一个 seed 的方块结果是否一致？ | 不同 chunk、列、方块的比例及三档分布 | `determinism.csv` 和差异明细 |
| 结果能否复查、能否支撑下一步？ | 波动、实验条件、原始证据 | 运行归档、`baseline.md`、README 更新 |

正式矩阵为 **3 个区域 × 1/2/4 核 × 5 次 = 45 轮**。每轮在全新世界中生成 16×16 个目标 chunk，即 256 个；默认 seed 为 `424242`。

本步骤使用 Chunky、Python、第 0 步的测试服务器和第 2 步已交付的 bench mod，不另开发测量 mod。主矩阵关闭阶段计时，另做阶段分析轮次；飞行虚空率和 p95 就绪延迟属于第 4 步；验证成本 Y 和客户端帧时间 Z 留在后续步骤。

这里的“原版基线”指固定 Fabric 和工具模组环境中的原版生成器，不能写成“完全没有模组的原版服务端”。

## 2. 开工条件与当前项目衔接

### 2.1 第 0 步必须先闭环

- [x] 实现 `analysis/mca/`，读取 chunk 状态、完整方块状态和每列 hash。
- [x] 读取器自测覆盖重复读取、缺失 chunk、损坏数据、负坐标及 palette 解码。
- [x] 补齐 `tests/step0/test_01_server.py::test_new_world_has_only_spawn_chunks`。
- [x] 补齐 `tests/step0/test_03_chunky.py::test_reader_sees_full_chunks`。
- [x] `scripts/test.sh step0` 退出码为 0，且没有 skip；读取器自测也被这个入口收集。

读取器自测已位于 `tests/step0/test_00_mca.py`，会被统一入口收集。新的 biome 解码自测也应纳入累计入口。

可以先写第 1 步纯计算部分的测试和代码，但正式基线实验必须等第 0 步完整通过。

### 2.2 复用与必要扩展

| 现有能力 | 第 1 步的使用方式 |
| --- | --- |
| `scripts/lib.sh::tc` | 唯一 Compose 入口，固定测试 env 和数据目录；项目为归属当前 checkout 的 `mc-test` 或 `mc-test-<名字>` |
| `prepare_heavy` | 复用共享锁、游戏服已停、EULA、挂载和实际实例归属检查 |
| `scripts/rcon.sh` | 所有游戏命令仍经此入口；Python 侧补超时和命令记录 |
| `scripts/new_world.sh` | 保留 `<名字> [seed]` 接口；runner 保证旧结果已归档，再允许删旧世界 |
| `scripts/test.sh` | 保留已实现的目标步骤存在性检查、累计回归及 `--slow` 语义；补 Step1 构建准备与 fixture 交接 |
| `tests/mcd.py` | 公共命令、等待和日志逻辑迁到可被脚本正常导入的模块，测试侧保留兼容入口 |
| `tests/log_whitelist.txt` | 每轮启动到停服的日志都用同一规则检查 |
| `Server.snapshot()` / `chunky_square()` | 复用已验证的保存恢复、CSV 目标及完成计数；迁入公共模块后加 campaign 身份与连续日志采集 |
| `/bench stats`、`/bench stages`、`/bench timing` | 已有计数与阶段计时；主矩阵 timing off，独立阶段分析 timing on |
| `scripts/bench_stages.py` | 已有单次在线 CSV 导出；扩展为逐 run 导出和离线汇总，不覆盖前一轮 |

新 runner 不直接导入依赖 pytest `pythonpath` 的 `tests/mcd.py`。建议把通用部分提取到 `bench/server.py`，由 `tests/mcd.py` 重新导出，避免从命令行运行和在 pytest 中运行时行为不同。

### 2.3 环境前置检查

前次检查发现过共享锁权限问题、旧会话未刷新 Docker 组，以及本 checkout 缺少测试配置。实现前重新检查实际状态，不把旧结果写死：

1. 当前执行进程能访问 Docker，不能只检查账户数据库里是否有 `docker` 组。
2. `/tmp/mc-donate-heavy.lock` 能被所有协作者使用；必要时修复统一的锁目录/组权限。所有入口必须仍使用同一把锁，不能改成各用户私有锁来绕过互斥，也不能删除正在使用的锁文件。
3. `docker/.env.test` 已准备，EULA 已由运行者本人同意。脚本不代替运行者修改 EULA。
4. 游戏服 `mc-donate` 已停；测试实例确实属于当前 checkout。发现其他人的测试实例时退出，不接管。
5. CPU、频率、内存、磁盘和 cgroup 计数接口满足下节要求。

当前工作目录是 `/home/kexu/distributed-mc`，开发分支为 `kexu`，存在 GitHub 远程。旧文档中的 `/home/fish/...` 和“没有远程”是历史环境，不能照抄作为当前路径或同步方式。

## 3. 固定实验环境

### 3.1 CPU 与内存

以当前 [docker/n100.env](../docker/n100.env) 为默认资源来源：

| 配置 | `MC_CPUS` | `MC_CPUSET` | 容器内存 | JVM 堆 |
| --- | --- | --- | --- | --- |
| 1 核 | `1` | `12` | `6g` | `-Xms4G -Xmx4G` |
| 2 核 | `2` | `12-13` | `6g` | `-Xms4G -Xmx4G` |
| 4 核 | `4` | `12-15` | `6g` | `-Xms4G -Xmx4G` |

12–15 为同一 L3、NUMA 节点 0 上的四个物理核；对应的 SMT 线程为 76–79，不分给测试容器。运行时重新核验拓扑。CPU 配额和绑定分别控制可用 CPU 时间与允许执行的 CPU 集合，二者都要核查实际容器值。[Docker 资源限制说明](https://docs.docker.com/engine/containers/resource_constraints/)

runner 从配置展开出四核池，再取前 1/2/4 个物理核；不要在多个 Python 文件中另写固定编号。每轮通过受控子进程环境同时设置 quota 与 cpuset，并在该轮的所有 Compose 调用中保持一致，不能只在启动命令上临时设置。

正式实验前固定完整四核池及其 SMT 兄弟的频率，用 `scripts/test_config.sh check` 验证。只固定旧核不算通过。如果已有恢复文件只记录旧 CPU，必须先正确处理恢复记录，保证新 CPU 的原设置也被记录；不要直接覆盖原记录或依赖一个不能恢复新 CPU 的 `unpin`。

绑核不提供独占：记录所选核及 SMT 兄弟的背景负载。实验期间串行运行，不并行构建或跑其他 benchmark。EPYC 上的资源限制只是限制核数和内存，**不等同于测到了 N100 的真实性能**，报告中须写清实际硬件。

### 3.2 软件、世界与协议

固定 Minecraft 1.21.1、Java 21、Fabric Loader 0.16.14、Fabric API 0.116.6+1.21.1、Carpet 1.4.147、Chunky 1.4.23、spark 1.10.109-fabric。保存镜像 digest、Java 完整版本、实际 mod 文件 hash、Python 依赖版本和代码版本。

复用 `.env.test` 配置的隔离实例；本 checkout 为 `mc-test-kexu`、`127.0.0.1:25567`，默认配置才是 `mc-test`、25566。挂载当前 checkout 的 `docker/test-data/`，不修改 `docker/data/world`，不复用正常游戏世界，不加载 donor 运行功能或 C2ME。

`tc` 已叠加 `docker/compose.test.yaml`，会把 `build/mods/` 挂到 `/mods`。不能以“未主动安装”为由声称没有测量 mod。正式基线固定构建产物和实际加载清单：使用现有 bench mod，**45 轮主矩阵显式执行并核验 `bench timing off`**，现有生成计数保持启用。骨架 mod 如随 `collectMods` 一起加载，也记录其名称与 hash。jar 缺失、与准备记录不符或意外多出 mod 均在起实验世界前失败。

构建在实验前通过 `scripts/gradle.sh build collectMods` 完成并保存源码/产物摘要，不在预热或计量窗口内构建。当前 `test.sh` 仅在 step2 及以后构建；实施时扩展为 step1 及以后先准备这些产物，GameTest 的既有触发条件仍为 step2 及以后。`bench.sh prepare` 负责独立实验入口的同等准备；只读 preflight 检查已有产物，不代替构建。

新增 `bench/protocol.json`，包含以下冻结字段：

| 字段组 | 内容 |
| --- | --- |
| 身份 | `schema_version`、`protocol_id`、代码/依赖摘要、镜像及 mod 摘要、`timing_enabled` |
| 世界 | seed、维度、视距 10、模拟距离 6、gamerule、时间与天气 |
| 区域 | `areas.json` 摘要、目标坐标、预热区域、遍历顺序 |
| 资源 | 四核池、各配额、内存/堆、频率、实际 swap 策略 |
| 采样 | 预热量、稳定条件、采样周期、超时、完成事件解析版本 |
| 矩阵 | 重复数 5、顺序随机种子、固定的配置顺序表 |
| 验收 | CV 阈值、差异分档阈值、协议预先声明的无效条件 |

模板可有待确定值；正式运行拒绝缺失坐标、版本或阈值。协议 hash 用规范化内容计算，计算时排除 `protocol_id` 自身。CPU、区域、软件、预热或采样规则发生变化，都建立新协议和新 campaign。

每个世界统一设置 `randomTickSpeed=0`、`doMobSpawning=false`、`doDaylightCycle=false`、`doWeatherCycle=false`，固定时间与天气；无真人/假玩家。关闭额外 profiler，保留已有工具模组。配置方式先在固定版本中验证。这些设置只能减少演化噪声，不能证明所有 scheduled tick 都停止。

## 4. 文件和模块清单

以下为目标结构；除第 0 步已有文件外，均需新增或扩展。

```text
scripts/bench.sh                  持锁入口：preflight / prepare / discover / pilot / run / profile
bench/__init__.py
bench/models.py                  区域、协议、运行记录的数据结构与校验
bench/server.py                  公共脚本适配、日志封存、世界上下文
bench/areas.json                  3 个正式区域和独立预热区域
bench/protocol.json               冻结协议
bench/discover_areas.py           locate、区域验证、坐标输出
bench/metrics.py                  cgroup 解析、采样、CPU/时间指标
bench/layer1.py                   单轮状态机、45 轮矩阵、恢复
bench/determinism.py              规范化 hash 比较与方块差异定位
analysis/__init__.py
analysis/mca/                    第 0 步交付的读取器
analysis/summarize_layer1.py      离线统计、报告、静态图表
analysis/results/
  layer1.csv                     最近一次完整验收通过的正式矩阵
  layer1-summary.csv             9 组吞吐与 CPU 成本汇总
  determinism.csv                不确定率比较结果
  stages.csv                     独立阶段分析的逐 run 数据与阶段 CPU 占比
  baseline.md                    实验结论、局限和 X 的初始目标
  runs/<campaign_id>/             每次 campaign 的不可覆盖归档
tests/step1/                     本步骤纯逻辑、端到端、slow 验收
```

`bench/` 已是第 2 步 Gradle 模块，Python 文件与现有 `src/`、`build.gradle` 分开；保留 Java 构建职责。NBT 库沿用读取器依赖，不再实现第二套解码器。新增统计/绘图库统一锁定依赖。

原始世界、日志和大快照加入忽略规则；协议、区域、小型汇总 CSV 和报告可以提交。结果导出按字段白名单构建，不保存含 RCON 密码的完整 env 或 Docker inspect。

## 5. 读取器接口与区域选择

### 5.1 第 0 步交付给本步骤的契约

以下接口已交付，直接复用，不另定义一套命名：

```python
read_chunk(world_dir, cx, cz) -> Chunk | None
iter_chunk_positions(world_dir) -> Iterable[tuple[int, int]]
read_world_metadata(world_dir) -> WorldMetadata  # seed、spawn、data_version、level_name
Chunk.status: str
Chunk.data_version: int
Chunk.y_min: int
Chunk.y_max_exclusive: int
Chunk.block_state(x, y, z) -> BlockState
Chunk.block_hash() -> str
Chunk.column_hashes() -> tuple[str, ...]  # 256 列，索引 z * 16 + x
HASH_SCHEMA == "mca-block-states-v1"
```

`None` 只表示不存在；数据损坏、格式不支持、压缩失败单独报错。方块状态为注册名和按键排序的全部属性，不能只返回 palette 索引或方块名称。全 chunk hash 按固定坐标顺序生成，与列 hash 共用带版本号的规范化规则。

第 1 步选区额外需要地表 biome 查询，可扩展读取器或提供适配器；这项新增能力不伪装成第 0 步已经实现。建议每个 chunk 在局部 x/z 各取 `2,6,10,14`，共 16 个采样点；每点取最高非空气方块所在 Y（包括水和树叶）处的 biome。整个区域共 4096 点，按点数计算比例，采样规则随协议固定。

### 5.2 选区流程

1. 在独立勘探世界中，通过 RCON 从记录过的搜索原点执行 `locate biome`，初始候选为 `minecraft:deep_ocean`、`minecraft:jagged_peaks`、`minecraft:jungle`。
2. 方块坐标转 chunk 坐标时用 `x // 16`、`z // 16`，负数向下取整；围绕命中点建立 16×16 候选区。
3. 勘探世界试生成，检查全部 chunk full，统计 biome 组成及高度范围。单个 locate 命中点不代表整片区域。
4. 在查看吞吐数据之前定义三类 biome 白名单；建议地表采样中至少 70% 属于目标类别。达不到时继续找区或公开修改选区标准，不能按生成速度挑选区域。
5. 区域及预热区远离出生点、彼此分离；初始最小间隔设 64 chunk，并通过预测量快照验证实际未污染目标区域。
6. 将 seed、维度、搜索命令、命中点、包含边界的 `min_cx/min_cz/max_cx/max_cz`、biome 比例和预热区域写入 `areas.json`。正式实验不再执行 locate。

每个正式区有稳定 ID：`ocean`、`mountain`、`jungle`。校验 `max_cx-min_cx+1 == 16`、`max_cz-min_cz+1 == 16`，目标坐标恰好 256 个且无重复。

### 5.3 Chunky 选择的边界验证

最小 chunk 坐标为 `(cx0, cz0)` 时，候选方块边界为：

```text
x1 = cx0 * 16           z1 = cz0 * 16
x2 = (cx0 + 16)*16 - 1  z2 = (cz0 + 16)*16 - 1
```

通过统一 RCON 入口发送 `chunky world minecraft:overworld`、`chunky shape square`、`chunky corners x1 z1 x2 z2`，再设置 `chunky pattern csv <run专属名字>`。CSV 每行是一个 `cx,cz`，列出预定的 256 个唯一坐标，按 `(cx,cz)` 升序遍历；记录文件摘要、selection 输出和完成事件。corners 只提供外围边界，CSV 决定实际目标。

Step0 已实测 Chunky 1.4.23 的 loop 对偶数边长会多一行/列，不能继续用 corners + loop 推断 16×16。复用 `Server.chunky_square()` 的 CSV 方案；现有自测覆盖正负/跨 region 的 2×2，真实验收覆盖 6×6，Step1 仍需验证完整 16×16 的实际任务数及落盘结果。

任务成功后恢复 `chunky pattern loop`，避免后续工具继承旧 CSV；输入文件在持久化任务仍可能引用时不得删除。归档后、正常停服并确认相关任务引用已移除，才清理本 run 的任务和 CSV，不清空其他 session 的配置。

正式验收同时要求 Chunky 目标数为 256、读取器确认预定的 256 个坐标全部 full。外围附带生成的 chunk 单独记录，不加入吞吐分母，也不能断言整个世界只有 256 个 chunk。

## 6. 单轮生命周期和现有测试的交接

### 6.1 单轮状态机

| 阶段 | 动作 | 失败条件或必须保留的证据 |
| --- | --- | --- |
| PRECHECK | 拿共享锁，检查权限、实例归属、协议、CPU 和上一轮归档 | 不满足即退出，不创建/删除世界 |
| CREATE | 建合法唯一世界名，设置本轮环境，调用 `new_world.sh` | 拒绝同名已存在、越界旧路径和未归档旧世界 |
| READY | 等 healthy、启动完成、RCON 可用；核验 seed/版本/资源 | 超时、配置不符、玩家存在或残留任务即失败 |
| CONFIGURE | 统一 gamerule、时间/天气、工具配置，核验 timing off | 保存实际值；不能只记录预期值 |
| WARMUP | 同一个 JVM 中完成固定远端预热任务 | 预热区不能触及目标，完成事件不能被正式任务复用 |
| SETTLE | 保存，等待固定的连续稳定窗口，记录空闲负载 | 条件未满足则超时，不用 sleep 代替就绪判断 |
| VERIFY_EMPTY | 读取一致的预测量快照，核验目标和保护缓冲区 | 目标已有任何 chunk，包括半成品，均判失败 |
| MEASURE | 选区，建立日志游标，取起始采样，发送 start，等待本任务结束 | 任务数、事件、容器身份、计数连续性均需正确 |
| FLUSH | 取结束采样，再 `save-all flush`，另记保存窗口 | 不把保存耗时混入主指标 |
| STOP_VERIFY | 正常停服，确认退出；离线校验 256 个 full chunk、生成 hash | 强杀、OOM、缺失、损坏和日志错误均失败 |
| ARCHIVE | 写完整日志、快照、指标及 manifest，校验文件摘要 | 归档失败即中止，不开始下一轮 |
| CLEANUP | 清理已归档且归属于本 run 的世界及 Chunky 任务 | 最后一轮同样清理；关闭测试服后才释放外层锁 |

整个 campaign 持有同一把锁；不能让 `new_world.sh` 返回后锁就释放。`MCD_LOCK_HELD` 只由真实持锁的父进程传递。直接运行有服务器副作用的 Python 入口时，必须经受管上下文，不能靠用户手动设置该变量跳过锁。

`new_world.sh` 会删除旧世界及同名世界；第 0 步已修复停止失败时继续删除的问题，并接入旧世界名、路径、符号链接和实例归属核验，以及替换前日志归档。第 1 步 runner 仍需保证存档和测量结果先归档。世界名限制 32 字符，例如 `l1_a1b2_o_c2_r3_a1`，完整身份保存在 manifest。

取得锁后先核对所配置测试项目的真实挂载和 Compose 工作目录。只有确认属于本 checkout 后才能安装会执行 down/cleanup 的退出处理，避免失败退出时误关闭别人的实例。

失败或 SIGINT/SIGTERM 时：停止测量、保存可获得的诊断、正常停服、归档并标记失败。归档失败保留现场，禁止继续换世界。宿主机重启或强杀后，由 resume 检查残留状态，不能假定 finally 已执行。

### 6.2 预热、快照与日志

pilot 初值建议：预热 256 chunk，连续稳定窗口 5 秒，宿主机 CPU 采样间隔 250 ms，启动超时 600 秒，生成超时 900 秒，无进度超时 120 秒。这些是**待 pilot 校准的参数**，正式矩阵前统一冻结。另做预热量对照，确认继续预热不会明显改变吞吐。

预热和正式生成必须在同一个 JVM 内完成。预测量快照在无活动生成任务时通过 `save-off → save-all flush → 复制所需 region → save-on` 获取；异常路径保证恢复保存或停服。正式比较一律使用正常停服后的存档。

正式 start 前建立连续日志监听和事件游标；只接受游标之后、当前容器、当前维度的任务完成事件。预热日志、旧完成行、RCON 返回成功都不能替代完成判据。处理日志轮转与流断开，原始日志同时归档。

### 6.3 pytest fixture 交接

当前 `server` 是 session fixture：先创建 A 世界和远处哨兵，再切为 B，并缓存 B；它已有初始快照和日志归档能力。但 finally 仍会无条件对当前 Compose 项目执行 stop，再用旧对象归档，因此 benchmark 换世界后仍可能误停新实例、把日志归错世界。`sealed` 只影响 `error_lines()`，不等于生命周期已交接。

约定实现 `WorldSession.finish()`：保存并停服、封存日志、执行日志检查、记录归档路径；重复调用返回同一完成结果。失败也要保留失败结果，不能第二次调用就变成通过。

- 第 0 步内部保留 A/B 准备流程，测试仍共用 B 世界。
- 第一个第 1 步服务器实验取得控制权前，显式完成第 0 步 session；之后旧 session 禁止再发 RCON 或读取活动世界。
- 第 0 步 fixture teardown 检查已封存结果，不再查询后来新建的容器；缺少归档必须失败。
- 第 1 步每个 smoke、pilot、正式 run 都使用独立上下文，在下一次换世界之前完成自身检查。
- 后续第 2 步通过新上下文取得服务器。更换世界不能只依赖测试文件名排序，应由管理器显式交接。

具体采用 session 级 `server_manager` 持有所有权；`server` 改为 module 级 fixture，按 `step0/step1/step2` 阶段向管理器取得有效对象。同阶段模块共享受管世界，Step1 各实验另建独立 `WorldSession`，进入 Step2 时创建新测试世界。这样兼容现有 module 级 `generated` fixture，也避免 session 级 `server` 缓存已退休对象。阶段只能显式前进，旧阶段被再次请求必须报错；累计入口的退出清理也通过管理器完成，不能由旧 fixture 再 stop 当前项目。

优先测试“早期日志出错、末轮正常仍失败”“容器删除后仍能验证历史日志”和“旧 session 被使用时明确报错”。共享逻辑的调整与公共脚本维护者协调后落地。

## 7. CPU 和时间的测量口径

### 7.1 cgroup 采样

首版要求 Linux cgroup v2。由容器 PID、`/proc/<pid>/cgroup` 和宿主机挂载信息解析实际 cgroup，不硬编码路径。核验容器 ID、cgroup 身份和 cpuset/quota；不支持或无读取权限时，preflight 明确失败。

从宿主机读 `cpu.stat` 的 `usage_usec`、`user_usec`、`system_usec`，记录可用的 `nr_periods`、`nr_throttled`、`throttled_usec` 及 `cpu.max`。CPU 时间字段为微秒，计数覆盖整个 cgroup；quota/period 决定时间配额。[Linux cgroup v2 接口](https://docs.kernel.org/admin-guide/cgroup-v2.html)

不通过高频 `docker exec cat` 采样，避免把采样进程成本不断加入容器。RCON 自身仍可能有容器内开销，主指标明确包含它。每次读计数记录前后 monotonic 时间，保留原始值和边界误差。

### 7.2 公式和单位

`t0/c0` 为正式 start 之前相邻采集的时间/CPU 值，`t1/c1` 为当前任务完成事件到达后立即采集的值；时间使用 `monotonic_ns`，CPU 使用 `usage_usec`。

```text
N = 256
wall_s = (t1 - t0) / 1_000_000_000
cpu_ms = (c1 - c0) / 1_000
chunks_per_s = N / wall_s
cpu_ms_per_chunk = cpu_ms / N
avg_cpu_cores = (cpu_ms / 1_000) / wall_s
quota_utilization = avg_cpu_cores / MC_CPUS
```

CPU 累计时间已汇总多个核，不能再乘核数。该指标包括生成外围依赖、JIT/GC、服务器 tick 和命令等开销，是生成目标区域的**容器摊销成本**，不冒充 chunk 独立 CPU 耗时。

`save-all flush` 后再取 `t2/c2`，另报从 start 到保存完成的 `durable_wall_s`、`durable_cpu_ms_per_chunk`。停服时间单独作为生命周期数据，不混入以上两组指标。

Chunky 自报时间用于交叉检查。主墙钟包含命令派发及完成通知的观察开销；pilot 测 RCON 往返、采样时长和通知延迟，目标是边界不确定度不超过正式窗口的 2%。达不到时改善采集或明确修订协议，不能静默扩大区域后仍按 256 计算。

计数倒退、容器重启、非正时长、采样缺失直接使 run 失败。空闲 CPU 仅作诊断，不从主指标中偷偷扣除；quota 导致的 throttling 不自动判失败，也不把 throttled 时间直接解释成“损失的 CPU 百分比”。

## 8. 实验矩阵、恢复和统计

| 阶段 | 规模 | 是否计入正式 45 轮 |
| --- | --- | --- |
| 冒烟 | 新世界内 2×2 chunk，含边界验证 | 否 |
| pilot | 3 区域 × 3 配额 × 1 次，必要时增加独立预热对照 | 否 |
| 正式 | 3 区域 × 3 配额 × 5 次 | 是 |
| 阶段分析 | 主矩阵通过后，3 区域 × 3 配额 × 1 次，timing on | 否，单独协议和 campaign |

正式实验按 5 个重复轮次执行；每个轮次包含全部 9 个配置，以固定随机种子打乱顺序。保存实际顺序，全部串行运行。依据 pilot 的启动、预热、测量和归档耗时估算总时间及磁盘，不预先承诺小时数。

一个 campaign 固定一个协议和一套环境摘要。每个逻辑槽位由 `(area_id, cpu_count, repeat)` 唯一确定；每次尝试有独立 `attempt_id`。先保存 `running` manifest，再执行；产物写入临时路径并完成校验后，原子提交 `passed/failed/interrupted` 状态。

resume 只复用协议、代码、配置一致且所有产物摘要验证通过的成功槽位；这是恢复调度，不调用 `pytest.skip`。未完成 run 必须从新世界开始，不用 `chunky continue` 接着计时。保留全部失败尝试；重跑前登记原因，不能保留更快的样本来替换有效的慢样本。

每个“区域 × 核数”统计 5 个有效结果，定义：

```text
CV = sample_std(chunks_per_s, ddof=1) / mean(chunks_per_s)
relative_range = (max(chunks_per_s) - min(chunks_per_s)) / mean(chunks_per_s)
S2 = mean_throughput_2cpu / mean_throughput_1cpu
S4 = mean_throughput_4cpu / mean_throughput_1cpu
```

把总计划中的“波动小于 10%”明确为 **9 组吞吐 CV 全部 < 0.10**，同时展示极差、全部原始点和样本数。CPU 成本也报告均值、中位数和标准差。若给均值置信区间，按 run 计算并说明 n=5 的小样本限制，不把 chunk 当独立重复。

CV 不达标就失败：先查背景负载、SMT 争用、频率、swap/GC、预热与通知延迟。不删除最慢一次、不反复重跑到“凑齐稳定的五次”。环境或协议改变后新建 campaign；失败 campaign 也保留统计报告。

### 8.1 阶段分析与已有 bench mod 对接

主矩阵的 cgroup 摊销 CPU 成本保持为主指标。另做 9 轮阶段分析，固定相同 jar、区域、资源和预热规则，仅改变阶段计时开关；使用新的 `profile_protocol_id`，记录 `parent_protocol_id` 指向主协议，结果不能补入 45 个正式槽位。每组仅一次的阶段结果作为描述性分析，不宣称已有重复测量精度。

每轮在同一 JVM 内完成预热并等待稳定后，先 `bench timing off`、`bench reset`，再 `bench timing on`；完成目标生成后，在停服前保存原始 `bench stats` / `bench stages` JSON 和 cgroup 边界值，再关闭计时。确认读取时 timing 为 true、必要阶段非空，按 run 归档，避免现有 `scripts/bench_stages.py` 默认覆盖单个 `stages.csv`；该脚本目前只告警而不拒绝 timing off，runner 必须自行严格校验。

沿用现有字段 `count,cpu_ns,wall_ns,max_wall_ns`，离线生成带 run/区域/核数/协议身份的 `stages.csv`。`cpu_share` 的分母是记录到的各阶段 CPU 合计，并非容器 CPU；另报阶段 CPU 合计与同一采样窗口 cgroup CPU 的比值及未覆盖开销。先核查 Mixin 计时范围是否重叠，不能默认比例一定小于等于 1。阶段 wall time 是多次调用的累计，不能当作整轮墙钟占比；`light_engine.count` 是批次数，外围依赖也参与其他阶段，不能要求各阶段 count 都等于 256。

## 9. 不确定率与差异定位

复用正式运行的停服快照，不额外创造无法对应吞吐数据的世界。每个区域预先固定配对：

| 比较组 | 配对 | 每区域对数 |
| --- | --- | --- |
| 1 核重复 | repeat 1 对 2，3 对 4 | 2 |
| 4 核重复 | repeat 1 对 2，3 对 4 | 2 |
| 1 核与 4 核 | 相同 repeat 的两种核数 | 5 |

共 3×9=27 对主比较，每对 256 个 chunk。2 核比较可另作补充，不混入上述组别。

比较前核对 seed、坐标、维度、DataVersion、协议和 chunk full 状态。主世界本版本预期高度为 `[-64, 320)`，运行中由元数据和读取器能力确认；禁止套用到未知维度。完整 block state 按稳定的 `(y,z,x)` 次序编码，列按相同高度范围比较。

不比较 palette 下标、压缩字节或 NBT 键顺序。方块朝向等属性必须参与比较。缺省空气 section 与显式同种空气的表示需规范化一致，但不同空气方块类型不能随意合并。时间戳、实体 UUID、光照缓存等不属于本次 block-state 指标。

| 档位 | 每个 chunk 的判据 |
| --- | --- |
| 完全一致 | 0 列不同 |
| 少量列不同 | 1–4 列不同 |
| 多列不同 | 5–256 列不同 |

4 列阈值是本计划选择，写入协议后固定；同时输出连续指标，避免分类阈值掩盖差异：

```text
chunk_diff_rate = 不同 chunk 数 / 256
column_diff_rate = 不同列数 / (256 * 256)
block_diff_rate = 不同方块位置数 / (256 * 16 * 16 * world_height)
```

第三项含大量空气，仅作补充。对 hash 不同的列逐方块解码，输出两侧 run ID、chunk 坐标、局部 x/z、全局 x/y/z、两侧完整方块状态，并按方块类型、Y 层和距边界距离汇总。

缺失、损坏或非 full 是失败，不能计入“不确定率”。“整块一样”只指全高度 block states 一样，不宣称方块实体内容、实体或整个 NBT 一样。保留原始快照，为第 3 步扩展比较留下数据。

零差异只能表述为“该 seed、区域和协议下未观察到差异”。配对复用了部分 run，不能把所有 chunk 当互相独立的证据。后续 donor 比较需使用相匹配的区域和条件，不用一个全局百分比简单抵扣任意差异。

## 10. 数据归档与离线重算

每个 campaign 保存协议快照、矩阵、源码/配置摘要及自己的 CSV/报告。每个 attempt 至少包括：

| 文件 | 内容 |
| --- | --- |
| `manifest.json` | 身份、配置、container/cgroup ID、阶段状态、错误、产物摘要 |
| `server.log` | 从启动到停止的完整日志；逐 run 检查而非仅最后一轮 |
| `commands.jsonl`、`events.jsonl` | 脱敏命令/响应、事件游标、单调时间 |
| `cpu_samples.csv`、`resource_samples.csv` | 原始 CPU 计数、采样边界、内存/频率/背景负载 |
| `snapshot/` | `level.dat`、`region/` 及所选 region 的必要引用文件，符合读取器世界根目录契约 |
| `hashes.jsonl` | 坐标、状态、整块/逐列 hash、hash schema |
| `result.json` | 该次结果、主窗口与落盘窗口指标、失败理由 |

`.mca` 文件可能含目标外的 chunk，保留原始文件并以目标坐标集合限定比较。若格式引用外部 chunk 文件，快照必须包含引用文件；只有 hash 不足以复查方块差异。

当前读取器明确不支持外部 `.mcc` 和 LZ4；若勘探或 pilot 实际遇到，先扩展并验证读取器，再冻结协议，不以“引用文件已复制”代替解码成功。阶段分析的 attempt 还保存原始 `bench-stats.json`、`bench-stages.json` 与对应 CSV，所有文件都带同一 run 身份。

`layer1.csv` 至少包含：

```text
campaign_id, protocol_id, run_id, attempt_id, area_id, repeat, order_index
seed, dimension, cpu_count, cpuset, world_name
min_cx, min_cz, max_cx, max_cz
target_chunks, preexisting_target_chunks, verified_full_chunks
wall_s, cpu_usage_start_usec, cpu_usage_end_usec, cpu_ms
chunks_per_s, cpu_ms_per_chunk, avg_cpu_cores, quota_utilization
durable_wall_s, durable_cpu_ms_per_chunk, chunky_elapsed_s
nr_throttled_delta, throttled_usec_delta, memory_peak_bytes
timing_uncertainty_ms, status, invalid_reason, manifest_path
```

未知或不可用的诊断字段写 null 和原因，不填 0。每个 campaign 的 attempts 索引保存失败/中断记录；正式 45 行结果只引用通过全部单轮验证的槽位。

先写 campaign 内的输出。只有完整验收通过后才原子更新 `analysis/results/layer1.csv` 等顶层“最近一次通过”文件；失败结果不能覆盖上次已验收结果。

离线汇总只读归档，不连接 Docker、不启动服务器。生成 9 组统计、27 对差异结果、吞吐/CPU 成本随核数的静态图、三档差异图及 `baseline.md`。相同输入重复汇总应产生相同数值、顺序和差异定位。

## 11. 测试设计与实施顺序

继续采用先写测试、观察失败、实现、重跑累计回归的方式。测试独立预期结果，不只检查输出文件存在。

| 小任务 | 实现内容 | 先写的验证 | 完成条件 |
| --- | --- | --- | --- |
| 1.0 前置闭环 | 第 0 步读取器、权限/锁、CPU 与测试配置 | 原 skip、自测及 preflight 失败原因 | step0 无 skip 通过 |
| 1.1 数据模型 | `models.py`、协议和区域 schema | 负坐标、256 集合、非法/缺失配置 | 错误配置在启动服务器前被拒绝 |
| 1.2 公共生命周期 | `server.py`、fixture 交接、`bench.sh` | 互斥、跨 checkout、旧 session、早期日志错误、归档失败、停止失败 | 无数据先删后存；失败历史不会丢失 |
| 1.3 CPU 计量 | `metrics.py`、容器/cgroup 绑定 | 单位、时间边界、错误 cgroup、倒退计数 | 人工输入算出准确指标 |
| 1.4 单轮与选区 | `layer1.py` 单轮、`discover_areas.py` | 正负/跨 region 的选择、旧日志、任务超时、缺失 chunk | 小范围端到端通过，区域可冻结 |
| 1.5 不确定率 | `determinism.py`、快照/hash | palette 重排、属性变化、单方块变化、损坏数据 | 差异数和定位准确 |
| 1.6 矩阵与恢复 | 45 槽位、顺序、manifest、resume | 唯一性、随机可复现、半写文件、产物损坏、恢复不复用世界 | 模拟中断可恢复且不覆盖证据 |
| 1.7 pilot | 实跑 9 轮及预热校准 | 256 个新 full chunk、观察开销、超时、资源 | 冻结可运行的协议和区域 |
| 1.8 正式矩阵 | 实跑 45 轮、27 对比较、汇总 | CV 阈值、完整覆盖、日志、归档 | 所有正式验收满足 |
| 1.9 收尾 | 报告、README、计划勾选 | 离线重算、回归结果、链接 | 结果可复查后提交/合并 |

1.0 已有 Step0 完成证据，本步骤只重新执行环境检查和累计回归，不重写读取器。1.8 同时完成上面的 9 轮独立阶段分析及 `stages.csv`；它们不计入 45 轮吞吐矩阵或 27 对不确定率比较。

建议测试文件与职责：

| 文件 | 覆盖 |
| --- | --- |
| `tests/step1/test_01_metrics.py` | CPU 单位、公式、采样异常 |
| `tests/step1/test_02_areas.py` | 坐标、集合、协议校验 |
| `tests/step1/test_03_lifecycle.py` | 锁、实例归属、日志封存、清理、fixture 交接 |
| `tests/step1/test_04_determinism.py` | hash 规范化、三档阈值、方块定位 |
| `tests/step1/test_05_matrix.py` | 45 槽位、恢复、CV 与产物完整性 |
| `tests/step1/test_10_smoke.py` | 新世界中的真实小区域生成和离线读取，非 slow |
| `tests/step1/test_90_matrix.py` | `@pytest.mark.slow`：正式矩阵、比较、独立阶段分析与报告验收 |

必须包含的独立测试样例：

- `usage_usec` 增加 2,560,000，墙钟 2 秒，256 chunk：应得 10 CPU ms/chunk、128 chunk/s、平均 1.28 核。
- 同样的方块状态但 palette 顺序不同：hash 相同；只改朝向属性：hash 不同。
- 改一个已知坐标的方块：恰好一个 chunk、一列、一个方块不同，Y 和两侧状态准确。
- 缺一个目标 chunk、少一个正式槽位、读到旧任务完成行：验收必须失败。
- 世界路径越界、停止失败、归档失败：不得执行后续删除。
- 第一轮有 ERROR、最后一轮正常：整轮仍失败；容器删掉后仍能从归档判定。
- resume 发现产物损坏或协议不同：明确失败，不静默跳过；中断 run 新建世界重跑。
- 配额切换后，启动、采样、保存、停止使用同一轮的配置；下一轮不残留上一轮环境。
- CSV 恰好 256 个唯一目标、pattern 恢复和引用文件清理顺序；不能因 loop 的实际目标变多而修改吞吐分母。
- 主轮 timing off、阶段轮 timing on，协议不混用；阶段 JSON、纳秒单位、CPU 分母、光照批次与产物身份正确。

分工沿用现有方向：kexu 负责读取器、Python 测量、分析和第 1 步测试；公共脚本、fixture、Compose 接口与 fish 协调。第 1、2 步的代码可分别推进，重活仍串行。

## 12. 命令入口与验收语义

已有命令：

```bash
scripts/test_config.sh check
scripts/test.sh step0
```

以下是实现后的目标接口，现在不能把它们当成已经可用：

```bash
# 只检查环境、权限和归属，不创建/删除世界，也不修改 EULA/CPU 频率
scripts/bench.sh preflight

# 实验前构建、测试并固定本地 mod 产物；持共享锁
scripts/bench.sh prepare

# 独立勘探世界，保存固定区域
scripts/bench.sh discover --seed 424242 --output bench/areas.json

# 9 个预跑配置，结果标记为 pilot
scripts/bench.sh pilot --areas bench/areas.json --protocol bench/protocol.json

# 累计第 0、1 步的非 slow 回归
scripts/test.sh step1

# 完整验收：累计回归 + 45 轮 + 27 对比较 + 9 轮阶段分析 + 报告检查
scripts/test.sh step1 --slow

# 恢复实验进度；不等同于自动补签累计测试通过
scripts/bench.sh run --resume CAMPAIGN_ID

# 独立的 9 轮阶段分析，关联已完成的主 campaign
scripts/bench.sh profile --baseline CAMPAIGN_ID

# 离线重算
python3 -m analysis.summarize_layer1 --campaign CAMPAIGN_ID
```

完整入口可通过新增的 `MCD_CAMPAIGN_ID` 指定恢复 campaign；重新执行累计回归，并验证已完成 run 的全部归档。未指定时生成新 ID。仅 `bench.sh run` 成功不代表累计回归已经通过。

`test.sh` 已在缺少请求的步骤目录时失败；保留这一行为并添加回归覆盖。完整验收要求实际执行所有必需测试，不接受 `-k/-m` 过滤、`--collect-only`、`--allow-skip` 或保持服务器运行的诊断模式作为完成证据。

保留既有规则：`step1` 为日常非 slow 回归，`step1 --slow` 为当前步骤完整验收；后续步骤日常回归不重跑 45 轮。第 10 步再次执行完整实验矩阵。pytest 的 deselected 不等于已通过，缺失检查不能用 skip 签发完整验收。

## 13. 最终验收清单

- [ ] 第 0 步完整通过，读取器自测和两条存档检查无 skip。
- [ ] 当前账户权限、共享锁、测试实例归属、CPU 12–15 及频率检查通过。
- [ ] 三个区域、预热、软件和资源条件已冻结；pilot 有独立记录。
- [ ] 45 个正式槽位全部通过，且每轮都是全新世界、目标预测量为空。
- [ ] 每轮 Chunky 目标数为 256；停服后 256 个目标 chunk 全部存在且 full。
- [ ] 原始 CPU/时间采样及计量边界完整，主指标与保存指标分开。
- [ ] 9 个配置的吞吐 CV 均小于 10%，保留全部原始点及失败历史。
- [ ] 主矩阵核验 timing off；9 轮独立阶段分析有原始 JSON、`stages.csv`、正确单位和分母，并明确单次测量的局限。
- [ ] 完成 27 对预先规定的比较，给出三档分布及连续差异率。
- [ ] 差异能定位到具体方块状态和 Y 层；快照可以离线重新解码。
- [ ] 每轮日志都检查，没有未解释的 ERROR、异常堆栈、OOM 或重启。
- [ ] 离线重算结果一致；CSV、图表、报告和 manifest 互相对应。
- [ ] README 写入实际硬件/软件、吞吐、CPU 成本、不确定率及 X 的初始目标。
- [ ] `scripts/test.sh step1 --slow` 返回 0，无 skip，正常清理测试世界和测试容器。

## 14. README 与后续步骤交付

README 汇总三类区域在 1/2/4 核下的吞吐和 CPU 成本，以及三种配对的不确定率；链接到协议、汇总、图表和归档说明。明确所有数值来自 EPYC 上的受限资源实验。

X 是玩家场景的 p95 就绪延迟改善倍数，不能把本步骤的吞吐倍数直接填成已实现的 X。完成基线后，需要给出一个有依据、明确标为待验证的初始目标；可从“2 个 donor、X=2”作为候选目标评估，是否采用由实测容量和后续开销假设决定，不提前写成结论。第 4、6 步才测真实延迟改善，Y/Z 继续待测。

回交第 2 步：固定区域、seed、生成顺序、资源协议、容器 CPU 计量接口、阶段 JSON/CSV 和结果 schema。主矩阵与阶段分析使用同一组已记录 jar，分别冻结 timing off/on 协议；将来若要测不加载 bench mod 的对照，须另建协议和 campaign，不能把当前主矩阵称为“无测量 mod 基线”。

交给第 3 步：读取器/hash 契约、规范化规则、参考快照和不确定率的适用范围，用于导出/导入正确性比较。实体与方块实体内容若需验证，另加对应指标，不沿用“方块一致”结论替代。
