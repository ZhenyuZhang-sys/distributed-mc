**第 1 步：基线测量实施计划**

依据：[DEV_PLAN.md](DEV_PLAN.md) 的测试约定、第 0 步和第 1 步，[第 0 步详细计划](docs/step0-plan.md)，以及 `CLAUDE.md` 引用的开发、测试规则。脚本接口已对照当前 `scripts/`、`tests/` 和 `docker/compose.yaml` 核查。本文件区分现有能力与待实现扩展，不代表已经完成第 1 步测量。

目标是得到可复查的三类结果：**生成吞吐、每个目标 chunk 的 CPU 成本、同 seed 下的方块生成不确定率**。本步骤使用 Chunky、Python 和 Docker，不开发 Java mod。

**1. 当前状态和进入条件**

现有配置已固定 Minecraft 1.21.1、Fabric Loader 0.16.14、Fabric API 0.116.6+1.21.1、Carpet 1.4.147、Chunky 1.4.23、spark 1.10.109，Java 使用 21。

第 0 步的脚本、测试服务器配置和 pytest 骨架已经实现，应直接复用。`docs/step0-plan.md` 记录的是 `scripts/test.sh step0 --allow-skip` 得到 **13 个通过、2 个等待读取器**，不是第 0 步完整验收通过；本次仅核对文件，没有重新运行服务器测试。

当前尚缺 `analysis/mca/` 及其自测、两个跳过项的实际检查，以及第 1 步实验代码。先完成读取器，补齐 `test_reader_sees_full_chunks` 和 `test_new_world_has_only_spawn_chunks`，再运行不带 `--allow-skip` 的 `scripts/test.sh step0`。读取器自测也必须纳入这个入口。

| 前置项 | 当前能力与第 1 步衔接 |
| --- | --- |
| `scripts/lib.sh` | 已有 `tc`、`prepare_heavy`、共享锁、游戏服停机检查和测试挂载检查；继续使用 |
| `scripts/rcon.sh` | 固定操作 `mc-test`；调用方补充超时、响应归档和脱敏，不增加任意生产项目切换能力 |
| `scripts/new_world.sh` | 已支持 `<名字> [seed]`、删除上一轮世界及同名世界、等待 healthy；runner 在调用前保证旧结果已归档且新名字未占用 |
| `scripts/test_server.sh` | 已提供 `up/down/status/logs`；`logs` 默认只取末尾，完整实验日志需通过公共 `tc` 另行采集 |
| `analysis/mca/` | 待实现；要求 chunk 状态、完整方块状态、列 hash、负坐标以及缺失/损坏区分；选区时补充 biome 读取 |
| pytest 与 `scripts/test.sh` | 已支持累计步骤、`slow` 标记、跳过算失败和退出时关闭测试服；需适配 benchmark 多轮换世界的日志归档 |
| 世界保护 | 已有 `/data` 挂载检查；日常 `docker/data/world` 不进入测试修改或删除路径 |

主机为 4 核、约 7 GiB 内存。当前开发规则要求：**测试或 benchmark 前游戏服必须已停止，构建、测试和 benchmark 共用一把重活锁**。执行时重新采集可用内存和 swap 活动，不沿用之前的资源快照。

EULA 由现有 `ensure_test_env` 检查 `docker/.env.test`；新文件默认 FALSE，脚本不自动改成 TRUE。已有本人同意不重复询问，也不根据游戏服的 `.env` 静默改写测试配置。

按照已有分工，kexu 负责读取器和第 1 步 Python 测量，fish 维护公共脚本并推进第 2 步。公共脚本和 fixture 的扩展先协调接口，再合入 `main`；开发在对应 worktree 进行。本计划不重复建设第 0 步已经完成的部分。

**2. 先固定测量口径**

| 项目 | 本计划的定义 |
| --- | --- |
| 基线 | 固定版本的 Fabric 服务器，使用原版生成器及现有工具模组，无 donor、C2ME 或其他生成优化 |
| 测量对象 | 主世界中固定的 16×16 个目标 chunk，共 256 个 |
| 世界条件 | 每个正式 run 都创建全新世界，seed 为 424242，不继承旧存档或 Chunky 任务 |
| CPU 配置 | Docker CPU 配额 1、2、4；不称作独占的物理核心 |
| 正式重复 | 每个“区域 × CPU 配额”运行 5 次，合计 45 次 |
| 预热 | 每次重启 JVM 后，在同一个 JVM 内生成远离目标的固定区域；预热不计入测量 |
| 计时窗口 | 主指标从发送生成命令之前，到收到当前任务完成事件；保存落盘另计 |
| CPU 成本 | 上述窗口内整个 Minecraft 容器的累计 CPU 时间增量 ÷ 256 |
| 不确定率 | 同坐标、同 seed、同协议下，完整方块状态不同的 chunk / 列比例 |
| 吞吐波动 | 将原计划的“小于 10%”明确为 5 次吞吐的样本变异系数 CV < 0.10；同时报告极差 |

“每个 chunk 的 CPU 成本”包含邻接 chunk 的必要生成、JVM/JIT、GC、服务器 tick 和测量期间的容器内辅助进程成本。它是**生成这批目标区域的摊销成本**，不是某个生成阶段或某个 chunk 独立消耗的精确 CPU 时间。

CPU 配额限制可用 CPU 时间，不保证没有其他进程争用 CPU；容器 `cpu.stat` 记录的是整个 cgroup 的 CPU 使用。实现依据见 [Docker 资源限制](https://docs.docker.com/engine/containers/resource_constraints/) 和 [Linux cgroup v2 文档](https://docs.kernel.org/admin-guide/cgroup-v2.html)。

第 1 步不测生成阶段占比，不测玩家飞行的 p95 就绪延迟，不测验证成本 Y 或客户端帧时间 Z。这些分别属于第 2、4、8、9 步。

**3. 文件与模块交付清单**

```text
STEP1_PLAN.md                       本实施计划
scripts/
  lib.sh                           已有：mc-test、共享锁、环境和挂载检查
  rcon.sh                          已有：统一命令入口
  new_world.sh                     已有：新世界生命周期，删除上轮世界
  test_server.sh                   已有：测试服 up/down/status/logs
  test.sh                          已有：累计回归，--slow 才包含性能测试
  bench.sh                         新增：复用 lib.sh，覆盖整个实验的持锁入口
docker/
  compose.yaml                     已有：测试实例也通过 tc 使用此配置
  .env.test                        已有生成机制：本 worktree 测试配置，不提交
  test-data/                       已有：本 worktree 测试运行数据，不提交
    world.env                      new_world.sh 写入的当前世界名和 seed
bench/
  __init__.py
  areas.json                       冻结后的三个测量区域及预热区域
  protocol.json                    测量协议、阈值、版本和随机顺序种子
  discover_areas.py                候选区域定位、验证与坐标输出
  layer1.py                        单轮状态机和 45 轮矩阵
  metrics.py                       cgroup 采样、时间窗口和指标计算
  determinism.py                   方块规范化比较和差异定位
analysis/
  mca/                             复用第 0 步读取器
  summarize_layer1.py              离线汇总、统计、图表和报告
  results/
    layer1.csv                     最近一次正式 campaign 的逐轮结果
    layer1-summary.csv             9 个配置的统计汇总
    determinism.csv                逐对比较的差异指标
    baseline.md                    结论、条件、局限和 X 的初始目标
    runs/<campaign_id>/<run_id>/    原始日志、采样、manifest、区域快照
tests/
  conftest.py                      已有：server fixture、日志检查、跳过判失败
  mcd.py                           已有：Server、compose、run、wait_until
  log_whitelist.txt                已有：统一日志白名单
  step0/                          已有：补齐读取器自测和两条跳过项
  step1/
    test_01_metrics.py             CPU 单位和统计公式
    test_02_areas.py               区域边界、负坐标与目标集合
    test_03_lifecycle.py           锁、归档、恢复、异常清理
    test_04_determinism.py         方块规范化、差异定位
    test_10_smoke.py               小范围真实服务器端到端测试
    test_90_matrix.py              标记 slow，45 次矩阵及报告验收
```

测试分别放在 `tests/step0/` 和 `tests/step1/`，沿用 `test_NN_xxx.py` 编号。当前 `test.sh` 只收集步骤目录，读取器自测建议放在 `tests/step0/test_00_mca.py`；若放在 `analysis/mca/` 内，必须显式接入累计测试入口，不能只依赖 pytest 默认搜索。

Python 环境复用现有 pytest 环境，NBT 库随第 0 步读取器选定并锁定版本；当前读取器和依赖锁定尚待落地。第 1 步直接复用其解码结果，不再实现第二套 MCA/NBT 解码器。若使用 NumPy 保存方块数组、Matplotlib 出图，也纳入同一份依赖锁定文件。

当前 `.gitignore` 已排除 `.env.test` 和 `test-data/`，继续沿用；实施时补充原始实验归档和大体积快照的忽略规则。小型 CSV、报告、协议和区域定义可以提交。导出的环境信息使用字段白名单，不能直接保存含 RCON 密码的完整 `docker inspect` 或 Compose 展开结果。

**4. 测试实例与可复现环境**

**复用第 0 步的 `mc-test`，不另建第三套服务器。** 当前约定如下：

| 配置 | 约定 |
| --- | --- |
| Compose 项目 / 服务 | `mc-test` / `minecraft` |
| Compose 入口 | `scripts/lib.sh` 的 `tc`；当前使用 `docker/compose.yaml` |
| 测试环境 | 当前 worktree 的 `docker/.env.test` |
| 世界参数 | 当前 worktree 的 `docker/test-data/world.env`，由 `new_world.sh` 写入 |
| 存档 | 当前 worktree 的 `docker/test-data/` 挂到 `/data` |
| 端口 | `127.0.0.1:25566`，RCON 仍通过容器内命令使用 |
| 重启策略 | `MC_RESTART=no`，保留崩溃证据，不自动重启掩盖失败 |

所有服务器操作经过现有脚本或复用 `tc` 的公共适配器。CPU 配额等本轮参数通过受控子进程环境传入，核查 Compose 展开值和实际容器限制；不用修改游戏服 `.env`，也不把额外参数写进会被 `new_world.sh` 覆盖的 `world.env`。拒绝外部环境意外覆盖项目、数据目录和世界名。

`new_world.sh <名字> [seed]` 限制世界名匹配 `[a-z0-9_-]{1,32}`，并拒绝 `world`。使用短名字，例如 `l1_4f2a9b7c_o_c2_r3_a1`，完整 campaign 信息放 manifest。现有脚本会删除同名世界，因此“名字不重复、没有覆盖未归档数据”的检查由 runner 在调用前完成，不能假设脚本已经拒绝同名。

调用前也检查 `world.env` 中的旧世界名与实际路径；所有清理目标必须登记在 manifest 中，且 realpath 位于当前 worktree 的测试根目录内。拒绝越界路径和指向测试根目录外的符号链接，不只检查新传入的名字。

生命周期最重要的顺序是：**停服 → 导出完整日志与存档快照 → 校验归档 → 再调用下一次 `new_world.sh`**。归档放在 `analysis/results/runs/`，不放进会被删除的测试世界。失败时先归档证据，归档失败就停止 campaign，不能换世界继续。最后一轮没有下一次换世界，需显式清理已归档的测试世界和本轮 Chunky 任务文件，再关闭测试服；保留公共 jar/依赖缓存。

新增 `scripts/bench.sh` 只包装现有基础设施：调用 `prepare_heavy`，启动 Python runner，并在成功、失败或中断时执行测试服关闭与必要清理。父进程持续持有 `/tmp/mc-donate-heavy.lock`，直到整次入口执行结束。不能只在 `new_world.sh` 启动的短时间持锁，随后无锁测量。

`scripts/test.sh step1 --slow` 已在 pytest 外层持锁；pytest 调用 runner 时沿用这把锁，避免重入死锁。现有 `MCD_LOCK_HELD=1` 只能由仍在运行的持锁父进程传递，不能手动设置来跳过互斥。直接启动有服务器副作用的 Python 子命令应拒绝没有受管锁上下文的调用；离线汇总不需要此锁。

两个 worktree 共用项目名 `mc-test`。取得锁后，还要检查已有测试容器的实际 `/data` 挂载归属。若是另一 worktree 的手动保留实例，明确退出并由该实例的使用者收尾，不能直接 `down` 或重建。当前手动 `test_server.sh up` 返回后锁不再持续持有，这也是 runner 不能只检查锁文件的原因。

`require_play_server_stopped` 是强制前置检查，不以“暂时有空闲内存”绕过。游戏服仍在运行时退出；执行实验需要停服且尚未获得授权时，再请求停服授权。测试入口不自动关闭游戏服或其他家庭服务。

第 2 步若调整测试 Compose 或挂载 `/mods`，沿用公共 `tc` 接口，不在 Python 内另写一套选服逻辑。正式第 1 步检查模组文件清单，确保没有残留的 `mcdonate-*` 或生成优化模组；“计时开关关闭”不等于原基线环境。后续 `/bench stages` 数据使用单独协议和结果文件，不混入这 45 次基线。

正式测量前冻结以下配置，改变任意一项都生成新的 `protocol_id`：

- 镜像 digest、Java 完整版本、Minecraft/Fabric/模组版本与文件 hash。
- Python 依赖锁定摘要、脚本版本或源码摘要、MCA/hash 格式版本。
- seed、维度、区域坐标、Chunky 遍历顺序、预热区域和预热量。
- JVM 堆、GC 参数、容器内存、swap 策略、视距 10、模拟距离 6。
- CPU quota/period、允许的 CPU 集合及 JVM 可见 CPU 数的核查结果。
- 服务器和 Chunky 配置、spark 采样状态、日志频率、测量采样周期。

当前 `.env.test` 生成模板没有 `MC_IMAGE`，不能假设它自动继承了游戏服 `.env` 中的镜像摘要。正式运行前明确固定测试实例使用的 digest，并记录其实际值；不复制整份游戏服配置或密码。

内存先以现有最大堆 1536 MiB、容器上限 2304 MiB 做预跑，测试实例建议固定 `Xms=Xmx`，减少堆扩容差异。若预跑发现不足，统一调整后重新冻结协议；不能给 4 核配置更大的堆而把结果全部归因于核数。

正式运行设置以下环境控制，记录实际值：

- 无真人和假玩家在线，无其他 Chunky 任务，无额外 datapack。
- 所有试验保持相同的出生区加载规则和 gamerule。
- 为减少生成后演化，测试世界固定 `randomTickSpeed=0`、`doMobSpawning=false`、`doDaylightCycle=false`、`doWeatherCycle=false`；记录时间和天气。它们不能保证所有 scheduled tick 都停止，因此差异仍须分类分析。
- 关闭 spark 后台 profiler，保留模组加载和健康指标；诊断采样另开 run，不混入正式结果。spark 支持 `backgroundProfiler` 配置，具体方式须在固定的 1.10.109 版本上验证。[配置文档](https://spark.lucko.me/docs/Configuration)
- 不在正式测量期间升级软件、下载依赖、运行构建或并行执行其他 benchmark。

主机检查包含 MemAvailable、swap 换入换出速率、CPU 背景负载、温度/频率（可读取时）和磁盘余量。已有 swap 占用本身不等于测量无效；**测量期间持续换页或内存压力**才是需要处理的证据。

4 核配额会覆盖这台主机的全部 CPU 容量，必须串行测量。游戏服停止后仍然资源不足时，先处理资源条件或换主机；不自动停止其他服务或修改主机调频策略。

**5. 选定海洋、山地、丛林区域**

先创建单独的勘探世界，所有定位、试生成和 biome 检查都在这里完成。勘探世界不进入正式样本。

执行流程：

1. 确认主世界、seed=424242，并保存出生点坐标。
2. 通过 RCON 从固定搜索原点执行 `locate biome`。初始候选为 `minecraft:deep_ocean`、`minecraft:jagged_peaks`、`minecraft:jungle`。找不到时使用预先记录的其他搜索原点。
3. 将返回的方块坐标转换为 chunk 坐标：`cx=floor(x/16)`、`cz=floor(z/16)`；负数必须向下取整。
4. 围绕候选位置确定连续 16×16 chunk，明确写入包含边界的 `min_cx/min_cz/max_cx/max_cz`。
5. 在勘探世界中试生成，检查区域内的实际 biome 分布，而不是把 `locate` 的单个命中点当作整片区域的证明。
6. 记录地表 biome 组成、地形高度分布及可见结构。biome 组成按固定的地表采样网格计算，方法写入协议。
7. 选择远离出生区、预热区和其他测试区的区域。建议区域之间至少隔 64 个 chunk，并额外核查实际生成边界。
8. 冻结坐标，之后每轮只读取 `areas.json`，不再运行 `locate`。

“山地”可能包含多种相邻 biome。先定义各类别允许的 biome 清单，再选区；建议要求地表采样点至少 70% 属于对应类别。该阈值是本计划建议的选区标准，须在看吞吐结果之前冻结。若当前 seed 无法找到符合条件的区域，明确报告混合比例或修订选区协议，不按生成速度挑选地点。

`areas.json` 示例结构如下，`null` 是待定位值，正式运行必须拒绝未填坐标：

```json
{
  "schema_version": 1,
  "seed": 424242,
  "dimension": "minecraft:overworld",
  "size_chunks": 16,
  "areas": [
    {
      "id": "ocean",
      "locate_biome": "minecraft:deep_ocean",
      "min_cx": null,
      "min_cz": null,
      "max_cx": null,
      "max_cz": null,
      "biome_allowlist": [],
      "surface_biome_fractions": {},
      "warmup_area_id": null
    }
  ]
}
```

正式文件需包含 ocean、mountain、jungle 三项，以及单独定义的预热区域。

**6. 精确生成 256 个目标 chunk**

对最小 chunk 坐标 `(cx0, cz0)`，目标集合为：

```text
cx ∈ [cx0, cx0+15]
cz ∈ [cz0, cz0+15]
N = 16 × 16 = 256

候选方块边界：
x1 = 16 × cx0                 z1 = 16 × cz0
x2 = 16 × (cx0+16) - 1        z2 = 16 × (cz0+16) - 1
```

通过 `chunky world minecraft:overworld`、`chunky shape square`、`chunky corners` 设置区域，固定 `chunky pattern loop`，执行 `chunky selection` 保存最终选择，再开始任务。这里的 `minecraft:overworld` 沿用第 0 步已使用的 Fabric 维度标识，不替换为 `MC_LEVEL` 的存档目录名。`corners` 和 `radius` 使用方块坐标/距离，不能把 16 chunk 直接作为 radius。[Chunky 命令文档](https://github.com/pop4959/Chunky/wiki/Commands)

上述方块边界是候选输入，必须针对 **Chunky 1.4.23** 验证其端点和取整语义。先用 2×2、跨 region 边界、负坐标区域做选择测试，再验证 16×16 的任务总数确实为 256；不以最新版文档替代固定版本行为测试。

正式 run 的双重验收：Chunky 的目标任务总数为 256，且读取器确认预定 256 个坐标全部存在、全部为 `minecraft:full`。额外生成的外围 chunk 单独统计，不计入吞吐分母，也不能要求整个世界恰好只有 256 个 chunk。

**7. 单轮测量状态机**

每个 run 按以下顺序执行。所有阶段记录开始、结束、状态和错误原因；整次调用由外层入口持有共享重活锁。

| 阶段 | 动作 | 通过条件 |
| --- | --- | --- |
| PRECHECK | 复核外层锁、游戏服已停、测试容器归属、资源、协议和上轮归档 | 上轮证据完整；运行目标为本 worktree 的 mc-test |
| CREATE | 通过受控环境设置配额，调用 `new_world.sh`，写 run manifest | 名字合法且未占用；上轮已归档；配置展开正确 |
| START | 启动，等待 healthy、启动完成日志、RCON 就绪 | seed、版本、模组正确；在线玩家数为 0 |
| CONFIGURE | 设置固定 gamerule、关闭额外采样，清除本轮之外的任务引用 | 配置与协议一致，无待恢复任务 |
| WARMUP | 在固定远端区域执行固定数量的生成，使用同一个 JVM | 任务完成；没有触及正式目标或其检查缓冲区 |
| SETTLE | `save-all flush`，等待固定稳定窗口，记录空闲 CPU 和内存 | 无遗留 Chunky 任务；没有持续后台生成 |
| VERIFY_EMPTY | 从一致的预测量快照检查目标及预设缓冲区 | 目标没有任何已持久化 chunk，包括半成品 |
| SELECT | 设置正式区域和遍历顺序，记录 selection | 目标集合精确对应 256 个 chunk |
| MEASURE | 建立日志游标；采样 CPU/时间；发 start；等当前任务完成 | 完成事件属于当前任务；采样连续，容器未重启 |
| FLUSH | 保存结束采样，再执行 `save-all flush`，另记落盘窗口 | 保存成功，无异常 |
| STOP | 正常停服，等待进程退出 | 无超时强杀，无崩溃 |
| VERIFY | 离线读存档、校验状态、生成 hash 和差异所需快照，检查完整日志 | 256 个目标 chunk 全部 full；日志检查通过 |
| ARCHIVE | 原子写入结果、完整日志与快照，校验并更新 manifest | 输出可重读；文件摘要一致；不能只留下 hash |
| CLEANUP | 在归档后清理本轮任务；由下一次 `new_world.sh` 删旧世界，末轮显式清理 | 只处理本 run 登记的测试数据；最后关闭测试服，外层再释放锁 |

预测量快照可以在没有活动生成任务时，使用 `save-off` → `save-all flush` → 复制所需 region 文件 → `save-on` 取得。失败路径也必须恢复保存状态或停服。正式 hash 一律从正常停服后的文件读取，避免边写边读 `.mca`。

固定稳定窗口和预热数量由 pilot 确定后写入协议。预热至少覆盖一批 256 chunk，检查继续预热是否仍明显改变吞吐；最终正式运行使用同一套固定预热流程。不能在一个 JVM 预热后重启 JVM 再测量。等待就绪、任务完成、保存完成和连续稳定窗口都使用带超时的 `wait_until` 条件检查，不用固定 `sleep` 假定操作已完成。

日志监听应在发 start 之前建立，区分预热完成和正式任务完成，处理日志轮转。RCON 返回空字符串不等于任务完成，也不等于失败，尤其要考虑 spark 的异步输出。

启动超时初始设为 600 秒；生成超时和无进度超时由 pilot 校准，并在正式 campaign 前冻结。超时后停止任务、保存诊断、正常停服，不能把已生成的一半作为新一轮继续计时。

**与现有 pytest fixture 的交接**

现有 `server` fixture 是 session 级别，缓存第一轮的 `Server.level`、`started_at`，结束时才读 Docker 日志。矩阵会多次换世界、变更 CPU 配额并重建容器，不能直接沿用这个对象，否则 `world_dir` 指向已删除的世界，最终日志检查也可能只看到最后一轮。

- 保留第 0 步既有测试顺序和共享世界行为；首次切入第 1 步换世界之前，封存并检查第 0 步日志。
- 为公共 `Server`/fixture 增加日志归档后的结束机制，使 teardown 检查已经封存的日志；即使容器已删除也不能把空日志当作通过。
- 第 1 步每个 run 建立新的 `Server(level, seed, started_at)` 上下文；`started_at` 在调用 `new_world.sh` 前记录，避免漏掉启动错误。通过 `server.rcon(...)`、`server.world_dir` 和 `wait_until` 工作。勘探、pilot、冒烟也遵循同样生命周期。
- 逐 run 在下一次 `new_world.sh` 或容器重建前归档并调用同一套错误检查，复用 `tests/log_whitelist.txt`。Python runner 可抽取公共适配器，但底层仍调用既有脚本和 `tc`。
- 将来的第 2 步测试应通过受管的新世界上下文接续，不复用第 0 步缓存的已失效世界；公共 fixture 的调整和 fish 一起确定。

这些是现有测试骨架的必要扩展，实施时先补“早期 run 出错、后续 run 正常仍必须失败”和“容器删除后可从归档检查错误”的测试。

**8. CPU 与时间采样**

优先在宿主机读取目标容器的 cgroup v2 文件。用容器 PID 和 `/proc/<pid>/cgroup` 动态解析路径，验证归属，不能硬编码容器 ID 或误读宿主机根 cgroup。若只能容器内采样，明确记录采样进程也会进入计数，并在 pilot 中校准开销。

采集 `cpu.stat` 中的 `usage_usec`、`user_usec`、`system_usec`，以及可用的 `nr_periods`、`nr_throttled`、`throttled_usec`；同时记录 `cpu.max`、内存事件和 swap/压力指标。cgroup v2 的这些 CPU 时间单位为微秒。[内核接口定义](https://docs.kernel.org/admin-guide/cgroup-v2.html)

```text
t0：发 chunky start 前的 monotonic_ns 时间
c0：t0 附近的 usage_usec
t1：收到当前任务完成事件后的 monotonic_ns 时间
c1：t1 附近的 usage_usec

wall_s = (t1 - t0) / 1_000_000_000
cpu_ms = (c1 - c0) / 1_000
chunks_per_s = 256 / wall_s
cpu_ms_per_chunk = cpu_ms / 256
avg_cpu_cores = (cpu_ms / 1_000) / wall_s
quota_utilization = avg_cpu_cores / cpu_quota
```

CPU 累计值已经汇总多个核心的运行时间，**不能再乘核数**。CPU 时间和墙钟时间也不能互相替代。每次读数记录前后时间，以便估计采样边界误差；容器 ID 变化、计数倒退、非正耗时都判无效。

同时记录 Chunky 自报耗时用于交叉检查。主指标保留命令派发和日志通知的观察开销，不包装成游戏内部的精确计时。pilot 估计空 RCON 往返和完成通知延迟，建议边界不确定度不超过测量窗口的 2%；达不到时先优化采集，不能暗中把目标从 256 个 chunk 扩大后仍按原协议报告。

`save-all flush` 完成后再取一次 CPU 和时间样本，给出 `durable_wall_s`、`durable_cpu_ms_per_chunk`，表示从 start 到强制保存完成的成本。它们与主指标分列展示，不将保存或停服成本混入主指标。

空闲 CPU 速率只作为噪声诊断。可另报估计的扣除值，但主报告保留原始容器 CPU 成本，不把空闲扣除当作准确隔离了世界生成。

CPU quota 引起的 throttling 是限核实验的一部分，不自动视为无效。报告其原始增量；`throttled_usec / wall_s` 不能直接解释成丢失的 CPU 百分比。

**9. 预跑与正式实验矩阵**

按三个阶段推进：

| 阶段 | 规模 | 用途 | 是否进入正式 CSV 统计 |
| --- | --- | --- | --- |
| 冒烟 | 一个新世界中的 2×2 chunk | 检验命令、完成判定、存档读取和清理 | 否 |
| Pilot | 3 区域 × 3 CPU 配额 × 1 次 | 检验 256 目标、内存、预热、超时、采样开销 | 否 |
| 正式 | 3 区域 × 3 CPU 配额 × 5 次 | 生成最终基线 | 是，共 45 次 |

正式运行采用 5 个重复轮次。每个轮次包含全部 9 个配置，使用固定随机种子打乱顺序。这样减少“所有 1 核先跑，所有 4 核最后跑”引入的热状态和时间趋势。保存实际顺序；所有实验串行执行。

同一正式 campaign 的区域、软件、内存、预热和采样协议保持一致。实际 CPU quota 每轮核查，不只是设置 `MC_CPUS`；更换配额导致容器重建之前，必须已归档上一轮日志。JVM 可能随可见 CPU 数改变 GC 或线程配置，这属于当前容器配置实验的一部分，必须记录，不能把结果解读成仅改变了硬件核心数。

支持断点续跑，但只复用 manifest 标记为成功且产物 hash 验证通过的 run。这里的“复用”是 runner 的断点行为，不调用 `pytest.skip`。未完成或失败的 run 从新世界重跑；不使用 Chunky 的 continue 继续统计。重复的 campaign/run/attempt 不覆盖旧文件。`new_world.sh` 仅删除世界，不自动保证清空 Chunky 任务和测试配置，runner 需要另外检查本轮任务状态及模组清单。

预跑后用实际记录估算总耗时与磁盘需求：

```text
总耗时 ≈ Σ45轮(启动 + 预热 + 稳定 + 测量 + 保存 + 验证 + 清理)
磁盘预算 ≈ 45轮快照 + 日志/采样 + 一轮活动世界 + 失败诊断余量
```

时间估算以 pilot 为依据，不预先承诺固定小时数。

**10. 不确定率比较方案**

直接复用 45 次正式运行的落盘快照，不额外生成一套不可对应的世界。每个区域至少进行以下比较：

| 比较类型 | 配对规则 | 每区域对数 |
| --- | --- | --- |
| 1 核重复性 | repeat 1 对 2；repeat 3 对 4 | 2 |
| 4 核重复性 | repeat 1 对 2；repeat 3 对 4 | 2 |
| 1 核对 4 核 | 同 repeat 编号配对，共 5 组 | 5 |

三个区域共 27 对主比较，每对检查 256 个 chunk。2 核内部比较可作为补充结果，预先登记，不和以上组别混算。

逐 chunk 先检查坐标、DataVersion、维度高度和 `minecraft:full` 状态，然后比较规范化方块状态：

- 比较完整方块注册名及排序后的 `Properties`，不能只比较方块名。
- 按固定 `(y,z,x)` 顺序编码；列 hash 覆盖同一 `(x,z)` 的完整高度。
- 主世界本版本按 `y=-64..319` 验证；维度高度写入快照 schema，不能将此范围静默套用到所有维度。
- 不依赖 NBT 键顺序、palette 索引或压缩字节；单元素 palette 和缺省空气 section 规范化后应得到相同结果。
- 排除 `LastUpdate`、`InhabitedTime`、光照缓存、实体 UUID 等不属于本次方块比较的数据。
- 损坏、缺失、未 full 都是运行/读取失败，不能归入“原版不确定”。

本步骤的“整块完全一样”专指**全高度 block states 一致**，不宣称实体、方块实体内容和整个 NBT 完全一致。保存原始区域快照，让第 3 步可以扩展比较范围。

每个 chunk 用以下规则分档。少量差异的阈值是本计划建议值，须在正式测量前固定：

| 档位 | 判定 |
| --- | --- |
| 完全一致 | 256 列的 hash 全部相同 |
| 少量列不同 | 1–4 列不同 |
| 多列不同 | 5–256 列不同 |

另外始终报告连续指标，避免只看分档：

```text
chunk_diff_rate = 不同 chunk 数 / 256
column_diff_rate = 不同列数 / (256 × 256)
block_diff_rate = 不同方块位置数 / (256 × 16 × 16 × 384)
```

block_diff_rate 包含大量空气，只作补充；chunk/column 差异率为主要结果。

对 hash 不同的列解码具体差异，输出 `cx, cz, local_x, local_z, global_x, y, global_z, state_a, state_b`，并按方块类型、Y 层、距目标边界距离汇总。这样能区分边缘植被、流体/方块更新和需要进一步调查的生成差异。

零差异也是有效结果，只能表述为“该 seed、这些区域、该协议下未观测到差异”。不能推广成所有 seed 均确定。不同配对共享同一批运行，不能把 27×256 个 chunk 当成互相独立的实验样本来生成极窄置信区间。

出现差异时保留双方 region 快照和日志。快照应足以离线重算 hash、恢复方块状态；只有 hash 文件不足以解释“什么方块、在哪一层”。诊断用的新试验单独编号，不混入正式矩阵。

第 3 步引用该结果时，应按位置、方块类型和协议解释差异，不能简单减掉一个总体百分比就放过 donor 篡改。

**11. 数据文件和汇总方法**

`analysis/results/layer1.csv` 每个正式 run 一行，建议字段：

```text
campaign_id, protocol_id, run_id, attempt, status, invalid_reason
area_id, seed, dimension, cpu_quota, repeat, order_index
min_cx, min_cz, max_cx, max_cz
target_chunks, preexisting_target_chunks, verified_full_chunks
outer_full_chunks, outer_partial_chunks
wall_s, chunky_elapsed_s, cpu_usage_start_usec, cpu_usage_end_usec
cpu_ms, chunks_per_s, cpu_ms_per_chunk, avg_cpu_cores
durable_wall_s, durable_cpu_ms_per_chunk
nr_periods_delta, nr_throttled_delta, throttled_usec_delta
memory_peak_bytes, oom_kill_delta, swap_activity, timing_uncertainty_ms
manifest_path, log_path, snapshot_path
```

外围数量按预定义检查范围统计，并在字段说明中写明范围；若无法测量，填缺失值及原因，不能填 0 冒充没有外围生成。

每个 run 目录保留：

- `manifest.json`：有效配置、软件版本、主机摘要、区域、协议、阶段状态和文件摘要。
- `server.log`、`commands.jsonl`、`events.jsonl`：完整服务器日志、命令响应、带单调时间的事件。
- `cpu_samples.csv`、`resource_samples.csv`：原始计数、时间和主机资源证据。
- `snapshot/`：正常停服后的目标相关 region 文件及解码所需元数据；不得包含 RCON 密码。
- `hashes.jsonl`：逐 chunk 和逐列的规范化 hash，注明 schema/hash 算法。

`determinism.csv` 每一对比较一行，记录双方 run_id、比较类型、三档 chunk 数、差异率、差异明细路径。块级明细另存压缩文件，避免主 CSV 失控。

按区域和 CPU 配额生成 9 行 `layer1-summary.csv`：样本数、均值、中位数、样本标准差、CV、最小值、最大值、95% 置信区间。

```text
CV = sample_std(chunks_per_s, ddof=1) / mean(chunks_per_s)
相对极差 = (max(chunks_per_s) - min(chunks_per_s)) / mean(chunks_per_s)
S2 = mean_throughput_2cpu / mean_throughput_1cpu
S4 = mean_throughput_4cpu / mean_throughput_1cpu
```

均值置信区间可使用以 run 为单位的 t 区间，同时展示全部 5 个点并注明小样本。扩展倍数按区域分别报告，不将不同 biome 混成一个无权重说明的平均数。

报告图表以静态、可导出文件为主：吞吐随 CPU 配额变化、CPU ms/chunk 随配额变化、三档差异比例，以及出现差异时的 Y 层/边界分布。图中显示原始点，不能只给柱形均值。

**12. 噪声、失败和重跑规则**

以下情况直接将 run 标成无效并返回失败：目标预先存在、任务数错误、目标缺失或非 full、seed/版本不符、容器重启、OOM、CPU 采样不完整、任务超时、日志出现未豁免的 ERROR 或异常堆栈。

复用 `tests/mcd.py` 的错误检查口径和 `tests/log_whitelist.txt`。白名单必须具体到已核查的消息、模组版本和原因。不能使用“忽略所有 WARN/ERROR”这样的宽泛规则。日志检查覆盖每个 run 从启动到正常关闭的完整过程，不只检查最后几十行或 pytest session 最后一个容器。

未实现的检查按现有规则写成说明原因的 `pytest.skip`，保持可见；有 skip 的正式验收必须失败，不能传 `--allow-skip` 放行。`slow` 在日常回归中被 marker 排除属于未选择，不应伪装成一次已通过的性能验收。

吞吐 CV ≥10% 时，该配置的正式验收失败。按以下顺序排查：

1. 同期其他服务 CPU、磁盘负载和玩家活动。
2. 内存压力、swap 换页、GC、容器内存上限。
3. CPU 温度、频率和运行顺序的时间趋势。
4. JVM 预热不足、测量窗口太短、命令/日志观察开销。
5. 目标重叠、缓存复用、Chunky task 恢复或落盘口径不一致。

不能为了降低 CV 删除最慢的一次，也不能只重跑较差样本直到通过。若发现预先规定的环境异常，保留全部原始结果及排除理由；环境或协议改变后开启新 campaign，重新跑完整可比矩阵。只因统计波动而失败时，先记录失败结论，再决定是否补充更多样本，不能修改原先 5 次的验收口径。

机器在共享负载下无法满足阈值时，报告“当前环境尚不满足基线验收”，保留已有结果，不伪造通过。

**13. 先测试、后实现的工作顺序**

| 顺序 | 先写的测试 | 随后实现 | 完成条件 |
| --- | --- | --- | --- |
| A：完成第 0 步剩余项 | 读取器自测、补齐两条 skip；保留已有 13 项回归 | `analysis/mca/` 与现有测试的读取检查，不重写脚本骨架 | `scripts/test.sh step0` 通过，零 skip，读取器自测已被入口收集 |
| B：参数与公式 | 负坐标、边界、256 目标、CPU 单位、统计公式 | 区域模型、`metrics.py`、协议 schema | 已知输入的期望值正确；非法输入被拒绝 |
| C：单轮状态机 | 共享锁/跨 worktree/完成/超时/旧日志/归档失败/fixture 交接 | `bench.sh`、`layer1.py`，协调公共 fixture 扩展 | 全程持锁；归档在删世界之前；每轮日志可检查；游戏世界不变 |
| D：真实区域 | 2×2 和跨 region 选择；16×16 总数；biome 采样 | `discover_areas.py`、冻结 `areas.json` | 三类区域有坐标、组成与选择证据 |
| E：不确定率 | palette 重排、属性差异、单方块变化、缺失/损坏 | `determinism.py`、快照和差异输出 | 差异定位到正确坐标与方块状态 |
| F：矩阵与统计 | 45 个组合唯一；固定随机顺序；断点续跑；CV 失败 | 矩阵调度、汇总、验收报告 | 失败样本可追溯，断点续跑不复用旧世界 |
| G：正式验收 | `scripts/test.sh step1 --slow`：第 0 步回归 + 第 1 步功能/端到端/性能测试 | 执行 pilot，冻结协议，跑 45 次与比较 | 零 skip，所有验收项满足，README 回填结果 |

关键测试样例需有独立预期值：

- `Δusage_usec=2,560,000`、`wall_s=2`、`N=256`，应得 `10 CPU ms/chunk`、`128 chunk/s`、平均 `1.28` 核。
- 同样方块、不同 palette 索引顺序，hash 相同；只改方块朝向，hash 不同。
- 改一个已知坐标的方块，恰好一列和一个 chunk 不同，明细的 Y 和状态正确。
- 删除一个目标 chunk，验收失败，不能以 `255/256` 成功率继续出“成功”结果。
- 插入上轮 Chunky 完成日志，不得结束本轮；只看到 RCON 命令成功也不得结束。
- 请求 `world`、`../world` 或测试根目录外的清理路径，必须拒绝。
- 已归档结果文件损坏，resume 必须发现，不得直接跳过。
- 已有同名世界或上一轮归档失败时，不调用会删除它们的 `new_world.sh`。
- 超过 32 字符的世界名在 runner 侧被拒绝，合法短名与完整 run_id 映射可追溯。
- 两个 worktree 的重活入口共用同一把锁；父进程等待测量时锁仍有效，子进程不因重入永久等待。
- 发现游戏服仍在运行，或 `mc-test` 的实际数据挂载属于另一 worktree，入口拒绝继续。
- 前一轮日志有异常、最后一轮日志正常时，完整验收仍失败；fixture 不读取已删除的旧世界。
- 在只收集到第 0 步测试、或者带 `--allow-skip`、`MCD_KEEP_SERVER=1` 等诊断设置时，不签发第 1 步完整验收结果。

第 1 步不引入 Gradle 或 Java 测量 mod。MCA 解码器对当前样本遇到的压缩/外部存储格式必须支持或明确报错，不能把不支持的格式默认为空气。

**14. 计划中的命令入口**

`scripts/test.sh`、`scripts/new_world.sh` 和测试服管理命令已存在；`scripts/bench.sh`、Python 模块和第 1 步测试是待实现扩展。所有有服务器副作用的入口都沿用第 0 步的锁、停机和挂载检查。

```bash
# 第 0 步的完整验收
scripts/test.sh step0

# 日常开发：累计第 0、1 步非 slow 回归，不执行正式 45 次
scripts/test.sh step1

# 以下 bench.sh 子命令待实现；都通过 lib.sh 管理实验生命周期
# 检查游戏服已停、资源、配置和测试实例归属，不开始生成
scripts/bench.sh preflight

# 定位并冻结区域；只在独立勘探世界运行
scripts/bench.sh discover --seed 424242 --output bench/areas.json

# 预跑，结果标为 pilot
scripts/bench.sh pilot --areas bench/areas.json

# 第 1 步完整验收：包含累计回归、正式矩阵和结果验收，不允许 skip
scripts/test.sh step1 --slow

# 中断后继续同一个 campaign，成功 run 校验后跳过，其他 run 新建世界
scripts/bench.sh run --resume <campaign_id>

# 仅从归档重新计算统计与图表，不启动服务器
python -m analysis.summarize_layer1 --campaign <campaign_id>
```

沿用 `.claude/rules/testing.md` 和当前脚本：**`scripts/test.sh step1` 是日常非 slow 回归，`scripts/test.sh step1 --slow` 才是第 1 步完整验收**。这也落实 `DEV_PLAN.md` 中“性能测试只在当前步骤验收及第 10 步运行”的要求，不新增 `--quick`，不改变现有 `--slow` 的含义。

完整入口包含第 0 步回归、第 1 步功能测试、正式矩阵、差异比较和数据质量验收。区域/协议未准备好必须明确失败；不把 pilot 或诊断 run 当正式结果，也不使用 `--allow-skip`。报告必须验证 45 个正式 run 和第 1 步测试都实际执行或经可信归档恢复，防止当前 `test.sh` 自动忽略不存在的 `tests/step1/` 后，仅第 0 步通过就被当成本步完成。

后续 `scripts/test.sh step2` 跑第 0、1、2 步非 slow 回归；`scripts/test.sh step2 --slow` 按现有语义包含这些步骤的 slow 测试。第 10 步再跑完整实验。使用 `-k`、`--allow-skip` 或 `MCD_KEEP_SERVER=1` 的运行只用于诊断，不签发完整验收；正常退出后由持锁外层入口关闭测试服。

**15. 最终验收清单**

- [ ] 第 0 步自动验收通过，读取器自测和两条原 skip 均实测通过；验收无 skip。
- [ ] 全部服务器实验复用 `mc-test`、本 worktree 的 `test-data/` 和共享重活锁；游戏服已停，`world` 和原服务器配置未被测试修改。
- [ ] 每轮在删世界或重建容器前归档；旧 fixture 不访问失效世界，所有 run 的日志均接受检查。
- [ ] 三个区域坐标、biome 组成、预热区、协议和软件版本已固定。
- [ ] pilot 证明采样边界、内存、预热和超时设置可用。
- [ ] 正式结果恰好覆盖 3 区域 × 3 配额 × 5 次，各选定 run 均成功，无未处理失败。
- [ ] 每个 run 开始前目标不存在，结束后 256 个目标 chunk 全为 full。
- [ ] 9 个配置的吞吐 CV 均小于 10%；全部原始点和异常历史可见。
- [ ] 吞吐、原始容器 CPU 成本、落盘成本和 CPU 扩展倍数口径清楚。
- [ ] 不确定率覆盖 1 核重复、4 核重复、1 对 4 核三种比较，提供三档分布和连续指标。
- [ ] 差异可定位到方块状态和 Y 层，保留双方可离线复查的快照；零差异有明确适用范围。
- [ ] 日志、资源数据、manifest、CSV、图表和报告完整；离线重算结果一致。
- [ ] README 回填实测值与初始研究目标，`scripts/test.sh step1 --slow` 退出码为 0；测试服已关闭，无遗留重活。

**16. README 回填和 X 的初始目标**

回填三组内容：各区域在 1/2/4 核下的吞吐与 CPU ms/chunk；三种比较的不确定率及差异特点；运行条件、重复次数、波动和结果文件链接。

需要明确区分：第 1 步测的是“生成容量”，研究假设里的 X 是“玩家场景的 p95 就绪延迟改善倍数”。CPU 扩展倍数或吞吐倍数不能直接填成已经证实的 X。

本计划建议用基线数据论证可行性后，将 **X 初始目标暂设为 2×、2 个 donor**，明确标为待验证的研究目标，而非本步骤测量结果。这个数是建议的目标，不是从当前数据推导出的结论；若实测生成容量或后续开销证据不支持，应给出依据后修订目标。

第 4 步测得无 donor 的玩家延迟，第 6 步有 donor 后才计算实际 `X = p95_without_donor / p95_with_donor`。Y 和 Z 继续标为待测。README 原有“X、Y、Z 在阶段 0 后都定”的表述也应随实际开发顺序调整。

最终交付应让下一步开发能回答：在固定区域上，本机生成 256 个新 chunk 的成本是多少；增加 CPU 配额能获得多少吞吐；同 seed 的方块结果在哪些条件下会不同；这些结论能否由另一人仅凭归档重算。
