# 第 9 步详细计划：客户端 mod 和帧率限速（草稿，待审）

负责人：fish（分支 `fish`）。目标：让普通玩家也能当 donor。worker 不跑在游戏进程里，由客户端 mod 另起一个 Java 进程，代码和 Docker 里的 worker 是同一份。

## 0. 环境：这台机器能测什么

- **没有真正的显卡**：只有服务器主板自带的 ASPEED 显示芯片（`lspci`：ASPEED Graphics Family）。装了 Mesa（`libgl1-mesa-dri` 25.2），可以用 **llvmpipe 软件渲染**（纯 CPU 跑 OpenGL），配合 **Xvfb 虚拟显示**无头跑客户端。Xvfb 没装，用 Docker 镜像（Java 21 + Xvfb + Mesa）跑，不改宿主机。
- **能测的**：客户端 mod 的所有机制——同意界面的开关、下载、起子进程、退出时停、`kill -9` 后 worker 自己退出、token、限速有没有生效（帧时间变长时 worker 是否暂停）。
- **测不了的**：**Z（开 worker 后 p99 帧时间增加多少）**。llvmpipe 用 CPU 画图，帧时间由 CPU 决定，和真实显卡完全不同；worker 也抢同一批 CPU，测出来的数没有意义。
- **降级**：Z 和"限速有用"的定量对比放到真实玩家的电脑上测（需要人：一台有显卡的 Windows 或 Linux 电脑，按第 6 节的脚本挂机 10 分钟）。写进 log.md 的"已跳过"等人决定。无头环境里只做定性检查：限速打开时，人为把帧时间拉长（见 9.6），worker 会暂停。
- **客户端怎么跑**：测的是**正式发布的 jar**，不用 Loom 的开发环境。用无头启动器 HeadlessMC（版本固定，写进脚本）在 Docker 里启动真正的 1.21.1 客户端 + Fabric Loader + 正式的客户端 mod jar，离线账号。启动参数 `--quickPlayMultiplayer host:port` 让它启动后直接进服务器，不用点界面。离线账号进不了正版验证的服务器，所以**客户端测试用的服务器要离线模式**（只给这个测试用的 compose 叠加文件设 `ONLINE_MODE=FALSE`，绑 127.0.0.1）。
- **资源**：客户端在 llvmpipe 下要吃好几个核。客户端容器绑节点 3 的 57-63（不用超线程兄弟核），和 donor 同一个节点、不碰服务器的节点 2。属于重活，按锁规则拿共享锁，kexu 的 benchmark 跑时等它。

## 1. 同意和下载

### 1.1 同意界面

- 默认关闭。模组菜单里一个开关"帮服务器生成地形"，第一次打开时弹出说明：
    - 会用多少 CPU（默认 2 个线程，帧率低时自动暂停）和内存（默认 2GB）；
    - 会从 Mojang 官方地址下载 Minecraft 服务器（约 50MB），放在 `.minecraft/mc-donate/worker/`；
    - **Minecraft EULA**：显示链接（https://aka.ms/MinecraftEULA），要求玩家本人勾选"我已阅读并同意"才能继续。
- 同意记录写进 `.minecraft/config/mc-donate.json`（`{"consent": true, "eulaAcceptedAt": "..."}`）。没同意之前，mod 什么都不做：不下载、不起进程、不连 coordinator。
- **测试怎么同意**（不替人点同意，也不改正式 jar 的同意流程）：
    - 正式 jar 里**没有任何"自动同意"的代码**，也不认任何跳过同意的系统属性。同意界面在玩家点"同意"时调用 `Consent.accept()`；界面打开时会查一个 Fabric 入口点 `mcdonate:consent_driver`，正式环境里没有任何 mod 提供它。
    - 测试另外用一个**只给测试的小 mod** `mcdonate-client-testhooks`（独立的 Gradle 模块，jar 名 `mcdonate-client-testhooks-*.jar`，`fabric.mod.json` 的名字是 "mc-donate client TEST HOOKS — 只用于测试"）。它提供 `mcdonate:consent_driver`：界面一打开就替测试按下"同意"按钮（走和玩家点击完全一样的代码路径）。
    - 测试时装**正式的客户端 mod jar + 测试小 mod**，所以测的就是正式 jar 的同意流程，只有"点同意"这一下被替代。
    - `collectMods` 和发布产物里没有测试小 mod；测试小 mod 不会被打进正式 jar。
- worker 的 `eula.txt` 由 mod 在玩家同意后写入（`eula=true`），因为玩家本人已经在界面上同意过 EULA。测试里是测试小 mod 代替点击，这条写进测试说明。

### 1.2 下载

- 从 Mojang 的版本清单（`https://piston-meta.mojang.com/mc/game/version_manifest_v2.json`）找到 1.21.1，读它的 `downloads.server` 的 URL 和 sha1，下载后校验 sha1，不对就删掉报错。不自己分发服务器 jar。
- Fabric Loader 的服务器启动器从 Fabric 官方 meta（`https://meta.fabricmc.net`）下载，同样校验。
- worker mod、common、Fabric API（Apache 2.0，可以随 mod 分发）打包在客户端 mod 的 jar 里，第一次开启时解出来。
- 下载放后台线程，不卡游戏；进度显示在模组菜单里。断网时报错，下次开启再试。
- 测试：服务器 jar 缓存在测试机的 `~/.cache/mc-donate-client/`，挂进客户端容器，只在第一次真的下载（验证一次真实下载 + sha1 校验即可，之后用缓存，避免每轮都从 Mojang 拉）。

## 2. worker 子进程

- 用游戏自己的 Java（`System.getProperty("java.home")/bin/java`）起 worker（DEV_PLAN：调低进程优先级、限制内存）：
    - 内存 `-Xmx2G`（可配置）；
    - 降低优先级：Linux/macOS 用 `nice -n 10`，Windows 用 `ProcessBuilder` 起 `cmd /c start /low /b`；
    - 系统属性：coordinator 地址、token（见第 3 节）、`mcdonate.parentPid=<游戏进程 PID>`。
- **游戏退出或被 `kill -9`，worker 自己退出**，两道保险：
    1. worker 每秒检查 `ProcessHandle.of(parentPid).isAlive()`，父进程没了就退出；
    2. worker 的标准输入接在游戏进程上，读到 EOF（父进程没了，管道关闭）就退出。
- 玩家退出世界：mod 发"停"给 worker（见 2.1），等 10 秒，还在就强制结束。

### 2.1 本地控制通道（游戏 ↔ worker）

- 用子进程的标准输入、输出，一行一条文本命令，不开端口：`PAUSE`、`RESUME`、`STATUS`、`STOP`。worker 回 `OK` 或状态 JSON。
- **线程数**：worker 是一个专用服务器，生成线程池大小在启动时定（核数 − 1），运行中改不了。所以限速只用"暂停/恢复"，线程数只在启动时设（`-Dmcdonate.genThreads=2` 配合限制 worker 进程能看到的核数）。DEV_PLAN 写的"改线程数"改成"启动时定线程数 + 运行中暂停恢复"，写进报告。
- 暂停的做法：worker 不再领新任务；正在生成的这个 tile 做完（几秒）再停。不打断生成中途（中途停会留下一半的世界状态）。

## 3. token 和 coordinator（fish 的模块）

### 3.1 流程

1. 玩家进服时，服务器（coordinator）通过 **Fabric 自定义包**发给客户端：coordinator 的公开地址、一次性 token、seed。没装 mod 的客户端收不到也不受影响（原版会忽略不认识的包频道；Fabric 只给注册了这个频道的客户端发）。
2. 客户端 mod 起 worker 时把 token 交给它；worker 的 `hello` 带上 token。
3. coordinator 校验 token：存在、没用过、没过期（发出后 60 秒内）、而且这个玩家还在线。通过就标成已用；不通过回 `welcome(workerId = -2)` 然后断开（`-1` 已经用来表示协议版本不对）。

### 3.2 coordinator 要改的

- `TokenRegistry`（纯逻辑，单元测试）：`issue(playerUuid) → token`（128 位随机，Base64）；`consume(token, now) → Optional<playerUuid>`：用过、过期、不认识都返回空；同一个玩家重新进服时作废他的旧 token。
- 玩家进服时（Fabric `ServerPlayConnectionEvents.JOIN`）发包；退出时作废没用过的 token，并断开这个玩家的 worker。
- Docker 里的测试 worker 没有玩家：coordinator 加一个"本地 worker 不要 token"的开关（系统属性 `mcdonate.allowTokenless=true`，只在测试 compose 里设）。正式默认关。

### 3.2b 再要一次邀请（kexu 11-09 审阅后加）

- token 60 秒过期，邀请只在进服时发一次：玩家晚同意、worker 晚起，或者 coordinator 重启后 worker 要重连，都会过期、没法再要。
- 客户端发 `mcdonate:invite_request`（游戏内频道，不改 coordinator 的 TCP 协议），服务器作废这个玩家的旧 token、重发邀请。每个玩家 10 秒最多一次（`inviteRequestMinMs`），超了忽略并记 WARN。
- 已做（11-09）：payload、频率限制、服务器处理，单元测试。等客户端 mod 能跑（9.3）再做的端到端：过期后再要能拿到新 token、旧 token 失效、刷请求被限流、coordinator 重启后 worker 靠再要邀请重连。
- 还要接的：worker 握手被拒（-2）时经本地控制通道告诉客户端（`NEED_TOKEN`），客户端再要邀请，把新 token 发给 worker（`TOKEN <t>`）。另外 worker 建世界前取 seed 的那次握手（SeedHandshake）也会带 token：要么那次不用掉 token（只检查），要么改成从邀请里拿 seed、再由 Welcome 核对，接客户端时定。

### 3.3 协议改动（要转给 kexu 确认）

- `hello` 加字段 `token: String`（没有 token 时是空串）。
- `welcome.workerId` 的约定加一条：`-2` = token 不对（`-1` 仍然是协议版本不对）。
- 两处都向后兼容：旧的 hello 没有 `token` 字段时，按空串处理。`Protocol.VERSION` 保持 1。

## 4. 帧时间和限速

- 客户端每帧记帧时间（Fabric 的渲染事件），算最近 5 秒的 p99。
- 规则：p99 > 目标（默认 50 ms，即 20 帧/秒以下）持续 2 秒 → 发 `PAUSE`；p99 < 目标的 0.8 持续 5 秒 → `RESUME`。
- 帧时间写进文件 `.minecraft/mc-donate/frametimes.csv`（每秒一行：时间、p50、p99、worker 状态），脚本读文件判断（DEV_PLAN 的做法）。

## 5. 小步和验收（DEV_PLAN 第 9 步的每一条怎么自动判定）

### 9.1 原版客户端照常进服

- [ ] 用协议层的无头客户端 mineflayer 连测试服务器：
    - **版本固定**：`package.json` 里写死 `"mineflayer": "4.23.0"`（这一版的 minecraft-data 支持 1.21.1；开工时先确认，不支持就换到支持的最早一版并写死），`package-lock.json` 一起提交。
    - **在哪跑**：测试 compose 里加一个 `mineflayer` 服务（profile `client`），镜像 `node:20.18.1-bookworm-slim`（固定版本），脚本挂进去，`npm ci` 装依赖。
    - **退路**：mineflayer 不支持 1.21.1 时，用 Python 写一个最小协议客户端（握手 + 登录 + 收第一批区块），只判断"能进服、不被踢"。
    
  判定：能进、能收到区块、服务器日志有"joined the game"，没有因为自定义包报错。这比起一个真的原版客户端便宜得多，而且判定的是"服务器不要求 mod"。

### 9.0 测的是正式 jar

- [ ] 正式的客户端 mod jar 里没有测试小 mod 的类、没有 `mcdonate:consent_driver` 的实现（解开 jar 检查类列表和 `fabric.mod.json`）；`build/` 的发布产物里没有 `mcdonate-client-testhooks`
- [ ] 只装正式 jar（不装测试小 mod）进服挂 30 秒：同意界面在，没有被自动同意（同 9.2 的检查）

### 9.2 装了 mod 但没同意：不启动任何进程，不下载任何东西

- [ ] 无头客户端（正式 jar，不装测试小 mod）进服挂 30 秒：`.minecraft/mc-donate/` 不存在；客户端容器里没有 java 子进程；coordinator 的 `/donor status` 没有新 worker；客户端容器的网络记录里没有访问 piston-meta（容器只放行测试服务器和一个记录 DNS 请求的小代理）

### 9.3 同意后启动，退出世界后停止

- [ ] 正式 jar + 测试小 mod 进服：60 秒内 `/donor status` 出现这个玩家的 worker；退出世界（mod 的测试命令触发断开）后 15 秒内 worker 进程消失、注册表里没了

### 9.3b worker 的资源限制确实生效

- [ ] 从 `/proc/<worker pid>/stat` 读 nice 值（第 19 个字段），等于 10（比游戏进程高 10）
- [ ] 从 `/proc/<worker pid>/cmdline` 读到 `-Xmx2G`（或测试里设的值）
- Windows 上的低优先级在无头环境测不了，在 9.7 的真实电脑上顺带确认（任务管理器里 worker 的优先级是"低"）

### 9.4 `kill -9` 游戏进程

- [ ] 对客户端 java 进程 `kill -9`：5 秒内 worker 进程消失，容器里没有残留 java 进程

### 9.5 token

- [ ] 单元测试（`TokenRegistry`）：只能用一次；过期（60 秒）拒绝；不认识拒绝；玩家重新进服后旧 token 作废
- [ ] 端到端：用 Python 写的最小协议客户端（gzip NBT，用 nbtlib）拿一个已经被 worker 用过的 token 去 hello → `welcome(-2)` 并断开；等 61 秒再用一个没用过的 → 同样被拒

### 9.6 限速（无头环境只做定性）

- [ ] 测试命令让客户端人为把每帧拉长到 80 ms（Mixin 在渲染循环里 sleep）：2 秒内 worker 收到 `PAUSE`、不再领任务（`/donor status` 里它手上没有新任务）；取消后 5 秒内 `RESUME`
- [ ] `frametimes.csv` 每秒一行，格式对

### 9.7 Z 和"限速有用"（需要真实显卡，**已跳过，等人决定**）

- 同一段固定场景（固定视角挂机 10 分钟），开 worker 前后 p99 帧时间的增加量定出 Z；再关掉限速对比一次。
- 需要人提供一台有显卡的电脑，跑我写好的脚本（启动客户端、挂机、收 `frametimes.csv`）。脚本和说明在 9.7 做好，数据等人跑。

### 9.8 加分项：拆分信任（不做，留给第 8 步之后再看）

donor 只算地形阶段、矿和结构由服务器自己算，会改变导出的状态和第 6 步的接缝规则，牵涉太大，第 9 步不做。

## 6. 依赖和顺序

- **第 9 步要等第 6 步完成**（worker 联网、自动找需求都要用）。
- **能和第 7 步交错做的**：9.1（mineflayer 进服）、1.1/1.2（同意和下载）、第 2 节（子进程和控制通道）、3.2 的 `TokenRegistry` 纯逻辑、9.5 的单元测试。这些都不碰第 7 步改的调度逻辑。
- **要等第 7 步之后的**：9.6 的限速和 7.3 的"按实测算力"有关（暂停的 donor 吞吐变成 0，第 7 步的算力估计要能处理），放在 7.3 之后。
- **协议改动**（3.3）要 kexu 确认后才能动 `mcdonate.common.proto`。

## 7. 已知限制（写进报告）

- **离线模式的游戏连接不加密**（kexu 11-09 审阅）：邀请走游戏连接，服务器 `online-mode=false` 时局域网里能抓到 token。正式部署开 `online-mode=true`，离线模式只用于可信网络。写在 docs/config.md 的"部署时的安全注意"。
- **token 走明文 TCP**：coordinator 和 worker 之间没有加密，抓到包的人可以抢在玩家的 worker 之前用掉 token（一次性，被抢用后玩家的 worker 会被拒）。原型阶段接受。以后的工作：coordinator 和 worker 之间加 TLS。
- **Z 没在这台机器上测**：见第 0 节和 9.7。

## 8. 风险

- **HeadlessMC 跑不起来**：退回 Loom 的开发环境跑客户端，但那时测的不是正式 jar，要在报告里写明，并保留 9.0 的检查（正式 jar 里没有测试代码）。
- **llvmpipe 太慢**：1.21.1 在 llvmpipe 下可能只有几帧，进服都很慢。降级：把渲染距离设成 2、窗口 320×240；还不行就只用 mineflayer 测服务器端，客户端 mod 的机制（子进程、控制通道、token）在不渲染的情况下测（mod 的这些部分不依赖画面）。
- **Windows 的低优先级**：无头环境只有 Linux，Windows 的 `start /low` 只能靠人工在 9.7 的真实电脑上顺带确认。
- **下载依赖外网**：Mojang 和 Fabric 的地址偶尔慢，测试用缓存，只有一条测试真实下载。
- **线程数不能运行中改**：见 2.1，DEV_PLAN 的说法要改，写进报告。
