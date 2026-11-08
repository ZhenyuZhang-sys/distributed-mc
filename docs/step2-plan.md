# 第 2 步详细计划：仓库骨架和测量 mod

负责人：fish（分支 `fish`）。kexu 同时做第 1 步（分支 `kexu`）。

这一步做完，要能做到三件事：

- 一条命令在 Docker 里构建出所有 mod；
- 把我们的 mod 装进一台独立的测试服务器；
- 用 `/bench stats` 准确数出"新生成"和"从磁盘读"的 chunk 数，用 `/bench stages` 给出各生成阶段的 CPU 时间。

## 顺序

1. 先和 kexu 分好第 0 步剩下的活，合进 `main`（见"开工前"）
2. 2.1 构建环境
3. 2.2 多模块骨架
4. 2.3 测试服务器
5. 2.4 计数
6. 2.5 阶段计时
7. 2.6 GameTest
8. 2.7 收尾和合并

2.4 和 2.6 互不依赖，可以交换顺序。

## 开工前：机器资源和分工

这台机器只有 4 核、7GB 内存。平时玩的服务器占约 1.9GB，现在只剩约 1GB 可用，已经在用 swap。所以：

- **同一时间只跑一个重活**：Gradle 构建、测试服务器、benchmark，三个里只能跑一个。所有重活的脚本都先拿同一把锁（`flock /tmp/mc-donate-heavy.lock`），两个人的任务会自动排队，不会同时抢内存。
- **测试服务器和平时玩的服务器分开**：用单独的 compose 项目 `mc-test`，端口 25566，数据目录 `docker/test-data/`，用完就 `down`。
- **跑测试服务器前，先停掉平时玩的那台**，内存不够两台同时跑。kexu 跑 benchmark 也要停，否则测出来的数被干扰。脚本开头检查一下：玩的那台还在跑，就提示后退出，不要自动帮人关。
- **Gradle 不留后台进程**：`org.gradle.daemon=false`，`org.gradle.jvmargs=-Xmx1g`。

两个人用同一个系统账号，各用一个 git worktree，互不干扰：

| 目录 | 分支 | 用途 |
| --- | --- | --- |
| `/home/fish/mc` | `main` | 平时玩的服务器（`docker/.env`、`docker/data/` 只在这里）；合并在这里做 |
| `/home/fish/mc-fish` | `fish` | fish 开发 |
| `/home/fish/mc-kexu` | `kexu` | kexu 开发 |

`docker/.env` 和 `docker/data/` 不进 git，所以两个开发目录里没有。测试服务器用 worktree 自己的 `docker/test-data/`；要用到 `.env` 里的配置时，从 `/home/fish/mc/docker/.env` 读，不要把密码复制进仓库。

第 0 步还剩几样没做，第 1 步和第 2 步都要用到，先拆给两个人，各自做完就尽快合进 `main`：

| 谁 | 做什么 | 为什么是他 |
| --- | --- | --- |
| fish | `scripts/rcon.sh`、`scripts/new_world.sh`、`scripts/test.sh` 骨架、锁、测试服务器的 compose 文件 | 2.3 马上要用 |
| kexu | `analysis/mca/` 存档读取器和它的自测 | 第 1 步算不确定率要用 |

## 2.1 构建环境

- `scripts/gradle.sh <任务>`：用 JDK 21 的 Gradle 镜像跑 `./gradlew <任务>`，仓库挂进容器。`~/.gradle` 缓存放在一个命名 volume 里，第二次构建不用重新下载。
- 加 Gradle wrapper。Loom 和 Gradle 的版本，用 Fabric 官方模板生成器给 1.21.1 生成的那一对，固定写下来。
  - 实际定下的：Loom 1.19.4（插件 `net.fabricmc.fabric-loom-remap`）+ Gradle 9.8.2，和 Fabric 官方模板 1.21 分支一致。wrapper jar 和 Gradle 发行包都核对了官方 sha256。
  - Loom 1.19 要求用 **Java 25** 跑 Gradle，所以 `scripts/gradle.sh` 用 `eclipse-temurin:25-jdk`。mod 用 `release = 21` 编译，产物是 Java 21 的 class（版本 65），服务器照常用 Java 21。
  - Gradle 缓存放在 `~/.cache/mc-donate-gradle`（两个 worktree 共用），不用命名 volume，省掉文件权限问题。
- `gradle.properties` 固定这些版本：

  ```properties
  minecraft_version=1.21.1
  loader_version=0.16.14
  fabric_api_version=0.116.6+1.21.1
  parchment_version=2024.11.17
  ```

- 映射：`loom.layered { officialMojangMappings(); parchment("org.parchmentmc.data:parchment-1.21.1:2024.11.17@zip") }`，并加上 Parchment 的 maven 仓库。2024.11.17 是 Parchment 给 1.21.1 的最新版本。

验收：

- [x] `scripts/gradle.sh build` 在空项目上通过
- [x] `scripts/gradle.sh genSources` 能生成带 Mojang 名字的原版源码。后面写 Mixin 时，所有类名和方法名都以这份源码为准

## 2.2 多模块骨架

- `settings.gradle` 包含 `common`、`coordinator`、`worker`、`client`、`bench`，五个都是 Loom 项目。
- `common` 是一个库 mod（`mcdonate-common`），其他模块依赖它，并用 `include(project(":common"))` 把它打包进自己的 jar。
- 每个模块有自己的 `fabric.mod.json`，mod id 统一为 `mcdonate-<模块名>`。`client` 的运行环境设成 `client`，其余设成 `*`。
- `coordinator`、`worker`、`client` 暂时只有一个入口类，启动时打一行日志 `mcdonate-<模块名> loaded`。
- 根项目加一个 `collectMods` 任务：把需要装到服务器上的 jar（remap 之后的）复制到 `build/mods/`。
- `common` 里放一个 JUnit 测试，先证明单元测试这条路是通的。

验收：

- [x] `scripts/gradle.sh build` 生成 5 个 jar，`collectMods` 之后 `build/mods/` 里有 coordinator 和 bench
- [x] `scripts/gradle.sh test` 跑了那个 JUnit 测试，并报告通过

## 2.3 测试服务器装上我们的 mod

- `docker/compose.test.yaml`：
    - 镜像和各版本号与 `compose.yaml` 完全一致；
    - 端口 25566，数据目录 `./test-data:/data`；
    - 把 `../build/mods` 只读挂到容器的 `/mods`，镜像启动时会把它同步进服务器的 mods 目录。
- 测试脚本通过环境变量选择打哪台服务器（compose 文件、端口），默认只打测试服务器，永远不碰 `world`。
- 测试服务器每次启动都用全新世界：`new_world.sh` 在 `test-data/` 下操作。

验收：

- [x] 测试服务器启动后，日志里有 `mcdonate-bench loaded` 和 `mcdonate-coordinator loaded`
- [x] 第 0 步的测试改成打测试服务器后，全部通过
- [x] 测试结束后 `docker compose -p mc-test down`，内存被释放

## 2.4 bench mod：计数

只用一个注入点：每个 chunk 变成完整状态时都要经过 `ChunkStatusTasks.full`。

- 进来时是 `ImposterProtoChunk`，说明它是从磁盘读出来的完整 chunk，计入 `full_loaded`。
- 是普通的 `ProtoChunk`，说明它是在这台服务器上生成完的，计入 `full_generated`。

另外在 `ChunkStatusTasks.generateNoise` 上计 `noise`。外圈的半成品 chunk 也会经过这一步，以后算"外圈重复计算"要用它。

- 计数器用 `LongAdder`：noise 跑在后台线程上。
- 只统计主世界。
- `/bench stats` 输出一行 JSON，比如 `{"full_generated":0,"full_loaded":0,"noise":0}`；`/bench reset` 清零。
- 上面的类名和方法名是按 1.21.1 的代码结构写的，写 Mixin 前先在 `genSources` 里确认。

验收（pytest 端到端，在测试服务器上跑）：

- [x] 新世界，`/bench reset`，用 Chunky 在 (5000, 5000) 生成一块方形区域（远离出生点，避免出生点常驻区块干扰）：`full_generated` 正好等于 Chunky 报告的 chunk 数，`noise` 大于等于这个数
- [x] 区域要比假玩家的视野（21×21 chunk）大一圈。重启服务器，`/bench reset`，把假玩家传送到区域中心：`full_loaded` 增加，`full_generated` 为 0
- [x] 让假玩家往区域外走：`full_generated` 开始增加
- [ ] 用存档读取器核对：`full_generated` 等于存档里新增的、状态为 `minecraft:full` 的 chunk 数

## 2.5 bench mod：阶段计时

1.21.1 里，每个生成阶段都是 `ChunkStatusTasks` 里的一个静态方法：structure starts、biomes、noise、surface、carvers、features、light 等。其中 biomes 和 noise 返回的是 `CompletableFuture`，主要计算在后台线程上进行。

- **同步阶段**：在方法进出时用 `ThreadMXBean.getCurrentThreadCpuTime()` 取 CPU 时间，相减。
- **异步阶段（biomes、noise）**：在生成器内部真正干活的那段代码里计时，具体位置在 `NoiseBasedChunkGenerator` 里，用 `genSources` 确认。
- **每个阶段记录**：次数、CPU 总时间、墙钟总时间、最大值。
- **开关和命令**：`/bench timing on|off`，默认关闭；关闭时不做任何计时。`/bench stages` 输出 JSON。
- **交给 kexu**：给一个脚本把 `/bench stages` 的结果写成 `analysis/results/stages.csv`，用来补第 1 步的阶段占比。

验收：

- [x] Chunky 生成一块地之后，每个阶段的次数都大于 0，noise 阶段的次数等于 2.4 的 `noise` 计数
- [x] 各阶段 CPU 时间加起来，至少占这段时间容器总 CPU（从 cgroup 读）的 70%。剩下的是磁盘读写和主线程。达不到就先查漏了哪段，必要时调整这个目标（实测 80%：先预热 JVM，否则即时编译占 15% 以上会拉低到 69%）
- [x] 同一块区域，计时打开和关闭各跑一次，生成速度相差不到 5%

## 2.6 GameTest

- Fabric API 自带 GameTest 支持。在 `bench` 里注册 `fabric-gametest` 入口，用空结构模板写测试。
- 测试服务器加一个运行模式：带上 GameTest 的启动参数（开启 GameTest，并把报告写成 JUnit XML），服务器跑完测试会自己退出。参数名以 Fabric API 1.21.1 版本的文档为准。
- `scripts/test.sh` 读这份 XML 判断通过或失败。

验收：

- [ ] 一个空测试和一个真测试（放一个方块，断言它在）都通过
- [ ] 故意写一个会失败的测试：`scripts/test.sh` 返回非 0。这证明失败能被发现。验证完删掉

## 2.7 收尾和合并

- `scripts/test.sh step2` 依次跑（构建在起服务器之前，内存不够两个同时开）：
    - Gradle 构建和单元测试（`gradle.sh build collectMods`）
    - GameTest
    - 2.4 的端到端测试
    - 第 0 步的测试
- 2.5 的计时测试标成 `slow`。
- 更新 `DEV_PLAN.md`：第 1 步的阶段占比改为用 `/bench stages` 测。
- 先 rebase 到最新的 `main`，`scripts/test.sh step2` 全部通过后再合并。

## 留到后面再做

- coordinator、worker 的实际功能：第 3 步开始。
- 实时地图、飞行场景：第 4 步。
