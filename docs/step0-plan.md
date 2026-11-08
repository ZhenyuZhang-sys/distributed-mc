# 第 0 步详细计划：服务器和测试环境

目标：一条命令起一台干净的测试服务器，自动确认各组件都正常，生成一小块地形再从存档里读出来。

和 `DEV_PLAN.md` 的一处不同：`new_world.sh` 不改平时玩的服务器，而是操作一台单独的测试服务器（compose 项目 `mc-test`，端口 25566，存档在 `docker/test-data/`）。所有测试操作都必须核验项目和真实挂载，保护平时玩的 `world`。

**当前状态：第 0 步完成。** 2026-11-08，同步上游后在提交 `5663920` 执行 `scripts/test.sh step0`，87 项通过、无 skip，退出码 0，容器和活动测试世界清理成功。0.8、0.9 的实现、验收、证据归档和 Git 交付均已完成，发布到 `kexu` 和 `main`。末尾记录完整证据。总要求见 [DEV_PLAN.md](../DEV_PLAN.md)，向后接口对齐 [第 1 步计划](step1-plan.md)。以下保留实施约定，具体实现以仓库代码为准。

## 分工

| 谁 | 做什么 | 状态 |
| --- | --- | --- |
| fish | `scripts/` 下的脚本、测试服务器配置、pytest 骨架、第 0 步测试 | 基础代码已有，本轮完成生命周期修补并通过完整回归 |
| kexu | `analysis/mca/` 存档读取器、自测和存档验收接入 | 已实现；完整入口 87 项通过，无 skip |

## 小任务

- [x] 0.1 `docker/compose.yaml` 的存档目录和重启策略改成可配置（默认值不变）
- [x] 0.2 `scripts/lib.sh`：重活锁、检查平时的服务器已停、生成 `.env.test`、核对存档目录
- [x] 0.3 `scripts/rcon.sh`、`scripts/test_server.sh`
- [x] 0.4 `scripts/new_world.sh`
- [x] 0.5 `scripts/test.sh`、`pytest.ini`、`tests/conftest.py`、`tests/mcd.py`
- [x] 0.6 第 0 步测试（`tests/step0/`）
- [x] 0.7 历史环境已同意 EULA，并实跑 `scripts/test.sh step0 --allow-skip`：13 个通过，2 个等读取器
- [x] 0.8 在开发分支完成读取器、自测和下面两个跳过项，纳入统一入口
- [x] 0.9 `scripts/test.sh step0` 全过（不加 `--allow-skip`），保存验收证据后合进 `main`

0.1–0.7 的勾选保留历史记录，不表示当前 checkout 的权限、EULA 或运行状态已经确认。当前代码另有 5 项资源限制测试，之前收集到 20 项；补完后测试数量会增加，不以“恰好 20 项”作为通过标准。

## 验收和对应的测试

| 验收 | 测试 |
| --- | --- |
| 容器 healthy；Fabric Loader 0.16.14、Fabric API、Carpet、Chunky、spark 都已加载 | `test_01_server.py::test_healthy`、`test_mod_loaded` |
| `seed` 返回 424242；`spark tps`、`chunky help` 有正常输出 | `test_01_server.py::test_seed`、`test_spark_tps`、`test_chunky_help` |
| 假玩家 bot1 能生成、能删掉 | `test_02_carpet.py` |
| Chunky 生成一块方形区域，读取器读到每个 chunk 都是 `minecraft:full` | `test_03_chunky.py`，36 个明确的 CSV 目标均已验收 |
| 读取器自测：同一个 chunk 读两次 hash 相同；不存在的 chunk 返回"不存在" | 新增 `tests/step0/test_00_mca.py`，确保统一入口会收集 |
| 新世界里只有出生点附近的 chunk | `test_01_server.py::test_new_world_has_only_spawn_chunks`，A/B 哨兵与固定 profile 检查已通过 |
| 停服再启动，世界还在，日志里没有 ERROR | `test_09_restart.py` |

## 补完后的交付标准

从仓库根目录执行 `scripts/test.sh step0`，应自动准备隔离的全新测试世界，验证服务端与工具模组、Chunky 生成、存档读取、世界隔离和重启持久化，退出码为 0，且没有 skip。

本轮交付分成三块：

1. 可独立测试的只读 `analysis/mca/`，能提供状态、方块状态、整块和逐列 hash。
2. 两个原 skip 变成有实际存档断言的测试；补强重启检查并保证读到一致快照。
3. 现有框架能在当前账户、CPU 12–15 配置下完成验收，日志和清理错误不会被吞掉。

本轮不实现第 1 步的 45 轮矩阵、biome 选区、统计报表或测量 mod；第 1 步的多轮 session 管理也不在这里整体重构。

## 0.8A：确认运行前提和实现边界

先完成只读检查，不启动服务器来掩盖前置条件问题：

| 检查 | 通过条件 | 不通过时如何处理 |
| --- | --- | --- |
| Docker | 当前进程能列出容器，而不只是账户已加入 docker 组 | 刷新会话/组身份后重试，不修改全局 socket 为任意用户可写 |
| 共享锁 | 所有协作者使用同一把可访问的锁，且整轮测试持续持锁 | 修复统一锁目录/组权限；不另建私有锁，不删除使用中的锁 |
| 测试配置 | 本 checkout 的 `.env.test` 有有效配置，EULA 由运行者本人同意 | 使用现有生成机制；不自动把 EULA 改为 TRUE |
| 实例归属 | `mc-test` 的真实 `/data` 挂载属于当前 checkout，游戏服已停 | 归属不符就退出，不接管别人的实例 |
| CPU | `MC_CPUSET=12-15`、`MC_CPUS=4`；核在线且拓扑符合配置 | 读实际配置，不沿用 8–11 的历史数据 |
| 固频 | 12–15 及 SMT 兄弟 76–79 的 `test_config.sh check` 通过 | 检查旧恢复记录是否覆盖新核，再安排固频；不能覆盖原恢复记录 |
| 内存与工具 | 容器 6g、堆 4G；Python、pytest、Compose 及所需依赖可用 | 明确报告缺项，统一依赖版本 |

环境状态会变化，前次发现的锁权限或配置缺失必须复查，不能在实现时直接认定已经修复。当前项目在 `/home/kexu/distributed-mc`，分支为 `kexu`，已有 GitHub 远程；不照抄旧 `/home/fish/...` 路径或“没有远程”的同步步骤。

读取器首版只承诺当前 Minecraft Java 1.21.1 默认主世界格式。读取真实样本确认 `DataVersion`、压缩标识及 section 结构，并写入兼容性说明；版本不支持时明确报错，不静默按另一版本解码。

## 0.8B：文件结构、依赖和公共接口

建议新增：

```text
analysis/__init__.py
analysis/mca/__init__.py          对外接口
analysis/mca/errors.py            缺损、格式不支持、未完成 chunk 等异常
analysis/mca/region.py            region 定位、索引和压缩数据读取
analysis/mca/chunk.py             section/palette 解码、坐标访问
analysis/mca/hashing.py           规范化与稳定 hash
analysis/mca/world.py             枚举 chunk、level.dat 元数据
analysis/mca/README.md            格式支持范围、接口、hash schema
requirements-test.txt             固定 pytest、NBT 库及所需依赖
tests/step0/test_00_mca.py         不依赖 Docker 的读取器自测
tests/step0/test_00_harness.py     停服/日志/清理失败路径的轻量测试
tests/fixtures/mca/               小型合成样本、真实样本说明、预期结果
```

采用现成 NBT 库，建议先验证 `nbtlib` 再锁定兼容版本。region 的索引、边界和压缩分派自己实现；NBT 标签解析交给库，不重写 NBT parser。`nbtlib` 可以解析文件对象中的大端 NBT；解压 region payload 后再交给它。[nbtlib 源码与用法](https://github.com/vberlier/nbtlib/blob/main/nbtlib/nbt.py)

公共接口与第 1 步约定保持一致：

```python
read_chunk(world_dir, cx: int, cz: int) -> Chunk | None
iter_chunk_positions(world_dir) -> Iterable[tuple[int, int]]
read_world_metadata(world_dir) -> WorldMetadata

Chunk.cx: int
Chunk.cz: int
Chunk.status: str
Chunk.data_version: int
Chunk.y_min: int
Chunk.y_max_exclusive: int
Chunk.block_state(x: int, y: int, z: int) -> BlockState
Chunk.column_hashes() -> tuple[str, ...]  # 索引 z * 16 + x，共 256 项
Chunk.block_hash() -> str
```

`world_dir` 指主世界根目录，包含 `level.dat` 和 `region/`，也可以是一致快照的根目录。`block_state` 的 x/z 是 chunk 内局部坐标 0–15，y 是绝对高度；越界抛异常。默认主世界高度为 `[-64, 320)`，由固定版本配置验证，不从“实际出现了几个非空 section”推断高度。

`BlockState` 用注册名和排序后的属性键值元组表达。`WorldMetadata` 至少包括 seed、SpawnX/SpawnY/SpawnZ、DataVersion；解析 level.dat 也使用 NBT 库。

### 缺失与错误必须分开

| 情况 | 接口结果 |
| --- | --- |
| 有效世界目录内，对应 region 不存在 | `read_chunk` 返回 `None` |
| region 索引中该槽位为空 | 返回 `None` |
| 世界路径不存在、无法访问或不是约定的世界/快照 | 路径或 I/O 错误，不能伪装成 chunk 缺失 |
| region 截断、长度/偏移非法、解压或 NBT 损坏 | `CorruptRegionError` / `CorruptChunkError`，附文件和坐标 |
| 未支持的 DataVersion、压缩或外部存储格式 | `UnsupportedFormatError`，明确具体格式 |
| chunk 存在但不是 full | 返回其真实状态；要求完整方块 hash 时抛 `IncompleteChunkError` |

所有生产读取接口只读文件，不创建 region，不修复或补写存档，也不吞异常返回全空气。文件内容及修改时间不应因读取而改变。NBT 对象不能使用退出时会自动保存的上下文管理方式；用只读文件对象解析，尤其注意 level.dat 的读取。

## 0.8C：region 读取与方块解码

### region 定位与边界检查

对 chunk `(cx, cz)`：

```text
rx = cx // 32                 rz = cz // 32
lx = cx % 32                  lz = cz % 32
region = region/r.<rx>.<rz>.mca
slot = lx + 32 * lz
```

使用向下取整和非负余数，特别测试 `-1`、`-32`、`-33`。读取 region 头部 location table，核对 sector 偏移、长度和文件边界，再读 chunk payload。常规 sector 为 4096 字节，头部两张表共 8192 字节；slot 的 location entry 包含 sector 偏移和占用数量。可对照独立 Anvil 实现核查格式，但不要照搬其“损坏也返回 null”行为。[Anvil region 参考实现](https://github.com/PrismarineJS/prismarine-provider-anvil/blob/master/src/region.js)

首版至少覆盖当前服务器实际产生的 zlib payload，并测试 gzip 和无压缩分派；每种支持的格式都需要独立样本。未知压缩标识、LZ4 或外部 `.mcc` 引用若未实现就明确报不支持；如果当前验收样本实际包含这些格式，必须补支持后才能通过，不能跳过对应 chunk。

索引检查至少覆盖：头部不足、offset 指向头部、sector count 为零但 offset 非零、payload 长度越界、sector 越过文件末尾、重复/重叠分配。只对合法空槽位返回不存在。

`iter_chunk_positions` 读取索引而不是按 region 文件数量估计 chunk 数，输出确定顺序的全局坐标；坏索引同样报错。不能只枚举 full，因为换世界检查也需要发现遗留的半成品 chunk。

### section、palette 和 bit packing

解析当前格式的根字段 `xPos/zPos/Status/DataVersion/sections`，验证 NBT 坐标与请求坐标一致；section 按 `Y` 定位，不能依赖列表排列。`block_states.palette` 中的 `Name` 和全部 `Properties` 参与解码。

重点分支：

- 单元素 palette：允许没有 data，整段取同一个状态。
- 多元素 palette：按固定版本的位宽规则展开 4096 个索引；检查数据长度及索引是否越界。
- long 数组中的负数先按 64 位无符号值解释；不能让 Python 符号扩展污染结果。
- 当前格式使用每个 long 内打包、不跨 long 延续一个值的布局；例如 5-bit 索引每个 long 容纳 12 项，余位留空。旧版的连续跨 long 算法不能直接复用。[独立 bit array 实现](https://github.com/PrismarineJS/prismarine-chunk/blob/master/src/pc/common/BitArrayNoSpan.js)
- section 内坐标顺序固定为 `index = local_y * 256 + z * 16 + x`；负 Y section 和 chunk/region 边界都要测。
- 仅对已确认的 full 格式，把合法缺省的空气 section 还原为空气；缺关键字段、重复 section Y 或不支持的布局不能直接当空气。
- 区分仅含光照的边界 section 与实际方块高度范围，不能把光照数据误解码为额外方块层。

对多元素 palette（大小 P > 1），首版按以下公式实现并用固定样本验证；单元素走上面的专门分支。最小 4-bit 位宽可对照独立 Anvil 解码实现。[palette 解码参考](https://github.com/PrismarineJS/prismarine-provider-anvil/blob/master/src/1.18/chunk.js)

```text
bits = max(4, (P - 1).bit_length())
values_per_long = 64 // bits
word = i // values_per_long
shift = (i % values_per_long) * bits
value = ((data[word] & ((1 << 64) - 1)) >> shift) & ((1 << bits) - 1)
```

特别用 P=17 的样本检查第 11、12 个索引附近的 long 边界，确认没有把填充位当下一项数据；其余数据长度与索引合法性检查不能省略。

这些规则要由 1.21.1 实际样本和有独立预期值的合成样本共同确认。不要为了“跑通读取”而接受任意 DataVersion。

## 0.8D：稳定 hash 的定义

定义 `hash_schema = mca-block-states-v1`，使用 SHA-256。不要使用进程间不稳定的 Python `hash()`，也不要直接 hash 压缩字节或 NBT 序列化结果。

1. 每个方块规范化为注册名与按键排序的全部属性；缺省属性集合视为空集合。
2. 为规范化状态选择唯一字节表示，例如紧凑 JSON 的 `[name, sorted_properties]`，固定编码；串联前加长度前缀，避免边界歧义。
3. 列 hash 遍历固定 `(x,z)` 下从 `y_min` 到 `y_max_exclusive-1` 的全部方块。
4. 整块 hash 按 `(y,z,x)` 遍历整个高度；hash 输入包含 schema 和高度范围。
5. 坐标、seed、时间戳、光照、实体、palette 顺序不进入方块内容 hash；比较时由调用方另行核验坐标、版本和协议。

相同方块、不同 palette 排序或 NBT 键顺序应得到相同 hash；仅改变方块朝向也必须改变对应列和整块 hash。`air`、`cave_air`、`void_air` 不随意合并。这里只保证 block states 的一致性，不声称实体或方块实体内容相同。

可缓存 palette 项的规范化编码，避免每个方块重复生成字符串，但先保证正确性，不在第 0 步做专门性能优化。

## 0.8E：先完成不依赖服务器的自测

所有读取器自测放进 `tests/step0/test_00_mca.py`，保证 `scripts/test.sh step0` 会收集。单独开发时直接运行这个文件，不需要 Docker、锁或 EULA。

| 测试组 | 必须覆盖的样例 |
| --- | --- |
| 坐标与索引 | 正负坐标、`31/32`、`-1/-32/-33`、空 region/空槽位、枚举顺序 |
| region 数据 | 实际支持的压缩方式、截断、非法 offset/length、解压失败、未知格式 |
| NBT/schema | 错误坐标、缺必要字段、未知 DataVersion、full 与半成品的区别 |
| palette | 单元素无 data、两个状态、5-bit 等非整除位宽、负 long、索引越界 |
| 方块坐标 | x/z/Y 顺序、负 Y、上下边界、乱序 section、合法空气省略 |
| hash 稳定性 | 两次独立读取及不同 Python 进程结果相同，palette/属性键顺序变化不影响内容 |
| hash 敏感性 | 单个已知方块变化恰好影响一个列 hash 和整块 hash，属性变化同样有效 |
| 错误语义 | `None` 仅表示不存在；权限错误、损坏和未支持格式不会被吞掉 |
| 只读性 | 读取前后输入文件字节和 mtime 不变，无新文件生成 |

合成 fixture 可用 NBT 库构造，但 region 头、关键打包字和预期坐标必须有手工核对的固定值；不能用生产解码器自己的逆算法生成全部“正确答案”。至少保留一份由当前服务器生成的小型真实样本及来源说明，交叉检查已知坐标/方块。

fixture 只包含最小测试数据，不提交整个世界、玩家数据、服务器密码或 jar。依赖缺失应报错，不用 `importorskip` 隐藏未完成项。

## 0.8F：一致快照与第 0 步生命周期

在 `tests/mcd.py` 添加 `Server.snapshot(destination)`，返回包含 `level.dat`、`region/` 及必要引用文件的一致快照；读取器只读取该快照，不在服务器写入 `.mca` 时直接扫描活动文件。

在线快照顺序：等目标生成结束 → `save-off` → `save-all flush` 并确认完成 → 复制数据 → `save-on`。异常路径必须通过 finally 恢复保存；恢复失败则停服并令测试失败。存档落盘不以任意 sleep 或文件存在代替完成条件。

正常停服后的读取无需在线冻结。源文件无法复制、引用文件缺失或快照不完整均失败，不能得到“空世界检查通过”。快照和日志放在活动世界目录外，后续 `new_world.sh` 不会删掉它们。

只做完成第 0 步必需的框架修补：

- `new_world.sh` 停服失败时中止，不再用 `|| true` 继续删除世界。
- 清理前核验旧世界名、realpath 和归属；拒绝 `world`、路径越界和指向测试根目录外的符号链接。
- 在重建/删除容器之前封存日志；启动失败也收集当时的日志，不能只有 fixture 成功 yield 后才检查。
- 保留统一错误正则和白名单；时间戳/颜色应先规范化，避免堆栈行因格式变化漏检。
- 测试结束先正常停服、收集停服阶段日志并检查，再删除容器；之后才清理本轮世界。
- 当前 `test.sh` 会忽略 `tc down` 失败；改为记录并使本来成功的验收转为失败，原测试已失败则保留原失败并补充清理错误。

完整路径由最外层脚本持锁到收尾结束。纯逻辑的 harness 测试使用假的命令适配器验证停止/归档失败，不为它们启动真实服务器。

## 0.8G：补齐“新世界只有出生点附近 chunk”

保留测试位置 `test_01_server.py::test_new_world_has_only_spawn_chunks`，检查必须发生在当前验收世界的 Carpet/Chunky 测试之前。

### 正式定义“出生点附近”

从 B 世界 `level.dat` 读取实际 SpawnX/SpawnZ，不能假定出生点是 `(0,0)`。记录 `spawnChunkRadius`、服务端版本及相关启动配置。

为固定 seed/版本配置建立 `tests/fixtures/mca/spawn_profile.json`：保存允许的出生区生成边界及必要的生成依赖外圈说明。边界通过独立干净对照世界和服务端出生区生成规则确认后固定；不能从被测世界的最大坐标反推一个一定通过的半径，也不能只统计 full 而忽略远处半成品。profile 未确定或与版本不匹配时明确失败，不能继续 skip。

这是实现前的小型校准工作，不按主观猜测写死 chunk 数。若正常启动包含额外的出生点搜索生成范围，应解释并固定该范围，不能静默扩大阈值。

首次校准使用已有脚本和受管测试上下文单独创建参考世界，不依赖尚待补齐的 A/B fixture；在正常停服后取样并检查日志。先确认解码结果、固定 profile 和样本来源，再启用依赖 profile 的完整验收，避免“先通过验收才能生成验收数据”的循环。

### 用 A/B 世界证明换世界隔离

仅检查一个新目录的存在不足以证明没有复用旧存档。完整验收按以下顺序建立证据：

1. 在 session fixture 的准备阶段创建临时世界 A，通过统一脚本在远离出生区的位置生成一个很小的区域，保存并用读取器证明哨兵 chunk 确实存在且 full。
2. 哨兵位置在已确定的出生区允许范围之外，记录到准备信息；不靠“挑一个坐标然后假定它从未存在”。
3. 正常停 A，封存并检查 A 的日志和必要快照，再调用 `new_world.sh` 创建同 seed 的 B。
4. B healthy 后、任何假玩家或新 Chunky 命令之前生成初始快照，读取元数据和全部已持久化 chunk 坐标。
5. 断言 B 的世界名/路径和 seed 正确，A 的活动世界按脚本约定已删除；B 的区块非空，有出生区 full chunk，所有坐标符合固定 profile，A 的哨兵在 B 中返回 `None`。
6. fixture 对外只 yield B 的 `Server`，把 A/B 准备证据和初始快照交给此测试。后续既有测试继续使用 B，不在测试中途修改一个仍被其他测试持有的世界对象。

A 的错误不能因后来切到 B 而丢失。这个准备过程会多一次起服和一小块生成，但不增加新的 Compose 项目；本步骤不引入第 1 步的 campaign 管理器。

## 0.8H：补齐“Chunky 区域全部 full”

修改 `tests/step0/test_03_chunky.py`，保留“生成完成”和“落盘”的检查，再让 `test_reader_sees_full_chunks` 真正读取每个目标坐标。

1. 明确目标 chunk 集合。现有注释把 `radius=48` 推成 6×6=36，尚不能据此直接写断言；先在固定的 Chunky 1.4.23 上确认边界语义。
2. 优先使用明确的 chunk 方形范围，换算为方块 corners，保存 selection 输出，并独立计算 expected 坐标。配置仍是出生点附近的小区域，不能为了保证新生成而扩成 benchmark。
3. start 前记录日志游标，只接受本次任务完成事件；A 世界或上轮完成日志不得结束当前任务。
4. 断言 Chunky 报告的目标数量与 expected 数量相符，完成后创建一致快照。
5. 对 expected 中每个 `(cx,cz)` 调用 `read_chunk`，断言非 None、坐标一致、状态为 `minecraft:full`；报告全部失败坐标。
6. 从同一不可变快照独立读取同一个 chunk 两次，比较 256 个列 hash 和整块 hash。
7. 对快照中经索引确认不存在的远处坐标调用读取器，断言返回 None；损坏另由自测覆盖。

不要只检查 `r.0.0.mca` 存在：实际目标可能在负坐标或其他 region，文件存在也不代表目标 chunk 存在。外围附带生成允许存在，不要求整个世界的 chunk 数等于 expected 数量。

生成 fixture 返回结构化结果：目标坐标、任务数、selection、完成事件和快照路径。计数、落盘及 full 检查均复用这份明确的数据，避免各测试分别推算不同区域。

## 0.8I：加强重启持久化与日志收尾

保留 `test_09_restart.py` 最后执行。现有“level.dat 存在、seed 相同”可能在同 seed 重建世界后仍成立，补一条可区分旧世界与重新生成的证据：

1. 在 B 世界的出生区附近选择一个已生成坐标，通过 RCON 显式加载该 chunk，写入一个非自然生成的方块状态标记；用命令和快照读取同时确认写入成功。
2. 另外在 B 中生成一小块远离活跃出生区的区域，取得至少一个 full chunk 作为不加载的持久化对照。不能把已经删除的 A 世界哨兵当作 B 中已有数据。记录标记和对照坐标，释放准备过程中增加的加载票据，正常保存并停服。
3. 从停服快照记录所选 chunk 的状态、列 hash、整块 hash，保存重启前日志。
4. 重启同一个 B 世界；断言 healthy、seed 正确，从一致快照验证标记仍在、远处 chunk 仍存在且 full，不先传送玩家或重新加载远处对照区域。
5. 对没有加载/演化的远处 chunk 比较重启前后 hash；对标记所在活跃 chunk 验证标记本身，不把正常流体/植物变化误判为存档丢失。
6. 检查重启前后及最终停服日志，所有错误都接受统一判定。

标记和临时加载只作用于本轮测试世界。最终日志与快照验证后删除本轮活动世界；如果收集证据失败则保留现场并返回失败。调试模式 `MCD_KEEP_SERVER=1` 可保留现场，但不作为完整验收通过的证据。

## 实施顺序和小任务清单

每项先写独立预期测试，确认失败，再实现并回归。读取器纯逻辑开发可先于环境修复进行，真实服务器验收需要两条工作都完成。

| 顺序 | 任务 | 主要文件 | 完成标志 |
| --- | --- | --- | --- |
| 1 | 确认依赖和接口，准备手工可核查 fixture | requirements、mca README、test_00_mca | 测试能收集，因功能未实现而失败 |
| 2 | region 定位、索引、压缩、错误语义 | region.py、errors.py | 正负坐标、缺失/损坏测试通过 |
| 3 | section/palette 解码和元数据 | chunk.py、world.py | 已知坐标状态、负 Y、packing 测试通过 |
| 4 | 逐列及整块 hash | hashing.py | 跨进程稳定、单方块/属性变化测试通过 |
| 5 | 环境、锁、停服/清理和快照 | lib.sh、new_world.sh、test.sh、mcd.py | 失败路径不删除数据或伪装成功 |
| 6 | 固定 spawn profile，接入 A/B 准备与出生区检查 | conftest.py、test_01_server.py、fixtures | 第一个 skip 替换为真实断言 |
| 7 | 固定 Chunky 目标集合、补存档检查 | test_03_chunky.py | 第二个 skip 替换，全部目标 full |
| 8 | 重启标记、停服日志与清理 | test_09_restart.py、conftest.py | 能检测同 seed 重建导致的持久化失败 |
| 9 | 当前环境完整回归，整理证据和文档 | step0、DEV_PLAN、CLAUDE/README 相关状态 | 无 skip、退出 0，再按协作流程合并 |

实施清单：

- [x] 0.8.1 读取器模块、依赖和异常语义落地。
- [x] 0.8.2 region、palette、坐标、hash 自测全部通过。
- [x] 0.8.3 固定版本真实样本及 spawn profile 已验证。
- [x] 0.8.4 一致快照、A/B 世界准备及历史日志检查接入。
- [x] 0.8.5 两个原 skip 均替换为实际存档断言。
- [x] 0.8.6 重启测试能证明已保存内容保留。
- [x] 0.9.1 当前账户 Docker/共享锁/CPU 12–15 固频条件满足。
- [x] 0.9.2 正式入口全过，无 skip，收尾和清理成功。
- [x] 0.9.3 验收记录、状态和第 1 步接口已交付；Git 提交、同步上游后复验并合入 main 已完成。

## 验证命令与完成证据

以下命令均已实现；依赖固定在 `requirements-test.txt`，本 checkout 使用 `.venv/bin/python`。

```bash
# 只开发读取器，不启动 Docker
python3 -m pytest tests/step0/test_00_mca.py -q

# 只跑框架失败路径的模拟测试
python3 -m pytest tests/step0/test_00_harness.py -q

# 检查所有新测试确实被收集；这不是验收通过
python3 -m pytest tests/step0 --collect-only -q

# 当前核的固频状态检查；需要固频时先正确处理恢复记录
scripts/test_config.sh check

# 第 0 步正式验收，包含读取器、服务器、资源、重启和日志
scripts/test.sh step0
```

正式验收不能带 `--allow-skip`、`-k` 过滤、`--collect-only` 或保留服务器的调试配置。缺依赖、未支持实际样本、没有 spawn profile 都应明确失败，不转换为 skip。

保存 Git 版本及未提交修改摘要、依赖版本、有效 CPU/内存配置、测试数量与结果、A/B 世界名、目标坐标、必要快照和完整日志。先保存证据，再删除活动世界和容器。证据目录放在世界目录外并按体积加入忽略规则，不混入服务器密码。

只有所有检查通过后才勾选 0.8/0.9 和 `DEV_PLAN.md` 对应验收项。向第 1 步交付读取器接口、hash schema、已验证的格式范围和快照能力；biome 解码及多轮实验管理按 [第 1 步计划](step1-plan.md) 继续实现。

## 2026-11-08 验收记录

- 正式命令：`sg docker -c 'scripts/test.sh step0'`。账户已在 docker 组，但当前父会话尚未刷新组身份，使用 `sg` 刷新；未修改 Docker socket 权限。
- 结果：**87 passed，0 skipped，121.82 秒，脚本退出码 0**。离线读取器及框架测试共 67 项，其余覆盖服务器、模组、A/B 世界隔离、Chunky、资源限制和重启。
- 软件：Python 3.12.3、pytest 8.3.4、nbtlib 2.0.4；Minecraft 1.21.1 / DataVersion 3955、Fabric Loader 0.16.14、Fabric API 0.116.6+1.21.1，Carpet、Chunky 1.4.23 和 spark 加载成功。
- 资源：CPU 12–15，配额 4 核，容器 6g、堆 4G；12–15 及 SMT 兄弟 76–79 固定为 performance / 2.9 GHz，睿频关闭。EULA 已由运行者预先同意，沿用现有配置。
- 实例：`mc-test-kexu` / `127.0.0.1:25567`；真实 `/data` 挂载属于本 checkout。整轮持共享锁，等待另一 checkout 的测试结束后启动。
- A 世界：`a1794127651723960852`；远处哨兵 `(142,0)` 经读取器确认 full。B 世界：`b1794127692018961944`；初始快照无该哨兵，A 的活动目录已删除。出生区依据独立参考世界的全部 841 个索引固定边界，不再添加主观余量，也不要求 SpawnY 完全相同。
- Chunky：CSV 明确指定出生区附近 6×6、共 36 个目标；完成计数及全部 full 状态通过。保留 CSV 输入，避免持久化 selection 在后续配置或重启时引用已删文件。RCON 使用 `--` 支持负坐标。
- 重启：出生区的 diamond block 标记仍在，B 的远处对照 chunk 重启前后整块及 256 列 hash 相同。启动、A/B 切换、重启和最终停服日志全部检查，无未豁免错误。成功后容器、网络及 B 的活动世界已删除。
- 证据：`artifacts/step0-20261108T084728-3587638/`，包含 `junit.xml`、`exit-code.txt`、`run.json`、依赖版本、A/B 和重启前后快照、CSV 目标及各阶段完整日志；该目录被 git 忽略。前一轮 84 项通过的证据也保留在 `artifacts/step0-20261108T084248-3560649/`。
- 首轮 Git 状态：上述验收基于 `1c29aeb` 及本轮修改，当时因缺少提交身份未能同步上游。该阻碍已在下面的最终交付中解决。

### 同步上游后的最终交付

- 按用户指定，仅为当前仓库配置 `user.name=locky` 和对应邮箱；实现提交为 `df9dc06`。
- 合并 `origin/kexu=d726a55`，包含 `origin/main=7f4a0a2` 的更新，合并提交为 `5663920`。保留第 2 步的构建、GameTest、bench mod 和公共工具；RCON 的负坐标修复一致。Step0 的 CSV 任务完成后恢复 `chunky pattern loop`，避免后续步骤沿用旧目标；CSV 输入仍保留供持久化任务读取。
- 在干净提交 `5663920` 重新执行 `sg docker -c 'scripts/test.sh step0'`：**87 passed，0 skipped，122.14 秒，脚本退出码 0**。这次验证的是合并后的 Step0；第 2 步完整实验另按其验收入口执行。
- 最终证据：`artifacts/step0-20261108T085516-3619624/`。A 世界 `a1794128120069607858`、B 世界 `b1794128159564291544`，哨兵 `(142,0)`。A/B 活动世界、容器与网络均已清理，完整日志无未豁免错误。
- 结果及状态文档随实现交付到 `kexu` 和 `main`；后续文档提交不改变已验收的代码。读取器版本、hash schema 和快照接口可供第 1 步使用。
