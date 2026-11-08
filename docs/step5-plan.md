# 第 5 步详细计划：通信协议和单个 worker 联网

把第 3 步的手动命令（`/donor export`、`/donor import`）换成网络消息。任务还是手动放（`/donor enqueue`），不做自动找需求（那是第 6 步）。

分工（调度 distributed-mc-77 定的）：

| 谁 | 分支 | 做什么 |
| --- | --- | --- |
| kexu | `kexu` | 新建 `common` 里的 `mcdonate.common.proto` 包：帧、消息、编解码和它们的 JUnit。**不碰 `TileFile`** |
| fish | `fish` | `TileFile` 的 `toTag`/`fromTag`；coordinator（监听、注册表、任务队列、租约、写入）；worker（拿 seed、连接、领任务、生成、发回）；端到端测试 |

**第 1 节是两边的接口约定**，改动要两边都同意（经调度转达）。

**第 5 步不持久化任务队列**：coordinator 重启后队列清空，要重新 `/donor enqueue`。已经写进存档的 tile 不受影响。持久化留到第 6 步以后再看要不要。

## 1. 接口约定（common，包 `mcdonate.common.proto`）

### 1.1 帧

- TCP 上每条消息一帧：`4 字节大端长度 N` + `N 字节消息体`。
- 消息体是 **gzip 压缩的 NBT**（`NbtIo.writeCompressed` 的格式），根是 `CompoundTag`。
- 上限 `Frame.MAX_BYTES = 16 MiB`（压缩后）。一个 result 是一个 tile 的 36 个 chunk，第 3 步实测导出文件约 1～3 MiB，留足余量。超过上限：发送方抛异常；接收方断开连接。
- 解压后的 NBT 也有上限（`NbtAccounter`，64 MiB），防止压缩炸弹。

```java
public final class Frame {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    /** 消息编码成一帧（含长度前缀）。超过上限抛 ProtocolException。 */
    public static byte[] encode(Message m);
}

/** 增量拆帧：收到多少字节就喂多少，凑齐一帧吐一条消息。不是线程安全的，一个连接一个。 */
public final class FrameDecoder {
    /** 返回这次凑齐的消息（0 条或多条）。长度超限、消息解不开抛 ProtocolException，调用方断开连接。 */
    public List<Message> feed(byte[] bytes, int offset, int length);
}
```

### 1.2 消息

`Message` 是 sealed interface，每种一个 record。NBT 里用 `type` 字段（小写字符串）区分，未知类型解码时抛 `ProtocolException`。

| 类型 | 方向 | 字段 | 说明 |
| --- | --- | --- | --- |
| `hello` | worker → coordinator | `protocol: int`、`name: String` | 连上后第一条。`protocol` 现在是 1 |
| `welcome` | coordinator → worker | `protocol: int`、`workerId: int`、`seed: long`、`dataVersion: int` | 回 hello。版本不一致时 `workerId = -1`，发完就断开 |
| `task` | coordinator → worker | `taskId: long`、`tx: int`、`tz: int`、`leaseMs: long` | 生成一个 tile |
| `result` | worker → coordinator | `taskId: long`、`ok: boolean`、`error: String`（ok 时为空串）、`tile: CompoundTag`（ok 时是 `TileFile.toTag()`，否则空） | 生成结果 |
| `cancel` | coordinator → worker | `taskId: long` | 这个任务别人已经做完了，没开始就别做，做完了也别发 |
| `heartbeat` | 双向 | `sentAtMs: long`、`taskId: long`（worker 发：正在做的任务，没有就是 -1；coordinator 发：-1） | 每 5 秒一条；15 秒收不到对方任何消息就断开。worker 的心跳给 `taskId` 续租 |

和上一版的区别（**需要 kexu 确认**）：

- 比 DEV_PLAN 多了 `welcome`：hello 是 worker 说"我是谁"，welcome 是 coordinator 回"你的编号、用哪个 seed"。
- 新增 `cancel`：重发的任务被别人先做完时，把它从手上撤掉（见 2.4）。
- `heartbeat` 加了 `taskId`：用来续租（见 2.3）。

```java
public sealed interface Message permits Hello, Welcome, Task, Result, Cancel, Heartbeat {}
public record Hello(int protocol, String name) implements Message {}
public record Welcome(int protocol, int workerId, long seed, int dataVersion) implements Message {}
public record Task(long taskId, int tx, int tz, long leaseMs) implements Message {}
public record Result(long taskId, boolean ok, String error, CompoundTag tile) implements Message {}
public record Cancel(long taskId) implements Message {}
public record Heartbeat(long sentAtMs, long taskId) implements Message {}

public final class MessageCodec {
    public static CompoundTag toTag(Message m);
    public static Message fromTag(CompoundTag tag);   // 字段缺失、类型不对抛 ProtocolException
}
public final class ProtocolException extends RuntimeException { ... }
public final class Protocol { public static final int VERSION = 1; }
```

### 1.3 `TileFile` 加两个方法（fish，5.1，单独提交）

```java
public CompoundTag toTag();                                                  // write() 写进文件的那个根
public static TileFile fromTag(CompoundTag tag, int dataVersion, long seed); // read() 的校验逻辑，不读文件
```

`write`/`read` 改成调这两个，行为不变，第 3 步的 JUnit 照样通过。

### 1.4 common 的验收（kexu，JUnit）

- [ ] 每种消息编码再解码，内容不变（`result` 带一个真实大小的 tile）
- [ ] 一帧被拆成任意小块（包括 1 字节一块）喂进 `FrameDecoder` 也能拼回；两帧粘在一起一次喂进去能拆成两条
- [ ] 长度前缀超过上限：抛 `ProtocolException`，不分配那么大的缓冲区
- [ ] 消息体不是 gzip、NBT 坏了、`type` 未知、字段缺失：抛 `ProtocolException`
- [ ] 压缩炸弹（压缩后小、解压后超 64 MiB）：抛 `ProtocolException`

## 2. coordinator（fish）

### 2.1 线程

- 一个接收线程收新连接；每个连接一个读线程，读到的消息放进这个连接的处理队列；发送走每个连接自己的写队列和写线程。心跳由一个定时线程发。
- **网络线程从不碰游戏状态**。要动存档的（导入 result）用 `server.execute(...)` 交给主线程。主线程卡顿不影响心跳和收发。
- 端口 `25580`（系统属性 `mcdonate.port`），只在 compose 网络里，不映射到宿主机。

### 2.2 任务队列（纯逻辑，单独一个类，不依赖网络和游戏，JUnit 全覆盖）

每个任务的状态：`QUEUED` → `LEASED(workerId, 到期时间)` → `DONE` 或 `FAILED`。

- `/donor enqueue <tx> <tz>`：同一个 tile 已在队列里或做完了就不重复放。
- 空闲 worker（没有租着任务的）拿队首，租约 30 秒。
- 租约到期没收到 result 也没续租：任务回到队首，记一条日志。
- 时间用注入的时钟，单元测试不用真的等。

### 2.3 租约够不够

第 3 步实测：worker 绑 4 核（8-11，频率固定），一个 tile 连外圈 36 个 chunk 导出耗时 **1.8～3.1 秒**（第一个最慢，JVM 没预热）。加上传输（1～3 MiB）和导入，正常远低于 30 秒。

但真实的 donor 可能核少、被限速（第 9 步按帧率限速），netem 再加 100 ms 延迟和丢包，慢 5～10 倍就逼近 30 秒。所以：

- **worker 每 5 秒的心跳带上正在做的 `taskId`，coordinator 收到就把这个任务的租约延长到"现在 + 30 秒"。** 只要 worker 活着、连接没断，任务就不会被收回。
- 连接断了（15 秒没心跳）：这个 worker 手上的任务立刻回到队首，不用等租约到期。
- 租约兜底的是"连着但卡死不发心跳"的情况。

### 2.4 结果怎么处理

收到 `result`，先在网络线程上查任务状态，再决定：

| 任务状态 | ok = true | ok = false |
| --- | --- | --- |
| `LEASED` 给这个 worker | 交主线程导入；导入完标 `DONE` | 重新入队（队尾），次数 +1 |
| `LEASED` 给别的 worker（租约到期后重发了） | 交主线程导入；标 `DONE`；**给现在租着它的 worker 发 `cancel`** | 忽略，记日志（别人在做） |
| `QUEUED`（重发了但还没人领） | 导入；标 `DONE`；从队列里拿掉 | 忽略，记日志 |
| `DONE` | **丢弃，不导入**，记一条日志"重复结果" | 丢弃，记日志 |
| 不认识的 taskId | 丢弃，记日志 | 丢弃，记日志 |

- 导入用第 3 步的冲突规则（内存里有的不写、存档里更完整的不覆盖），所以就算两份都导入也不会写坏存档。但按上表，后到的那份根本不导入。
- `ok = false` 最多重试 3 次，第 3 次还失败就标 `FAILED`，记一条 WARN，不再重发。`/donor status` 里能看到。
- worker 收到 `cancel`：任务还没开始就丢掉；正在生成的做完后不发 result。

### 2.5 握手

- 收到 `hello`：`protocol` 不等于 1 → 回 `welcome(workerId = -1)` 后断开，记一条 WARN。
- 否则分配 workerId，回 `welcome(seed, dataVersion)`，加入注册表。
- 连上后第一条不是 `hello`：断开。

### 2.6 命令

- `/donor enqueue <tx> <tz>`：放任务。
- `/donor status`：一行 JSON：注册表（workerId、名字、手上的任务、最后一次收到消息的时间）和各状态的任务数。测试用。

## 3. worker（fish）

### 3.1 拿 seed：建世界前在 mod 里握手

worker 服务器启动时还不知道 seed，要先问 coordinator。

**做法**：worker mod 用 Mixin 拦 `DedicatedServerProperties` 构造函数里的 `WorldOptions.parseSeed(...)`（1.21.1 源码：`level-seed` 读出来后交给它），在这里阻塞握手（hello → welcome），把 seed 换成 coordinator 给的。

- 这时 Fabric 已经加载完，Minecraft 的类和 common 的编解码都能用，只需要一套协议。
- 选它而不是"另开纯文本端口查 seed"：一套协议、一个版本检查；纯文本方案要在 coordinator 再维护一个端口和格式，而且握手和后面的连接是两回事，seed 和 workerId 对不上的风险更大。
- coordinator 地址来自系统属性 `mcdonate.coordinator=host:port`。没设就不握手（第 3 步那种手动用法照旧）。
- 握手失败（连不上、版本不对）：按 1、2、4…最长 30 秒的间隔重试，5 分钟还不行就让服务器启动失败（日志写清原因）。
- 存档已经存在时，原版用 `level.dat` 里的 seed，不看配置。所以 worker 起来后再核对一次：存档 seed 和 welcome 给的不一样，就不领任务，记一条 ERROR。

### 3.2 线程

- 网络线程收发、发心跳；生成用 `server.execute(...)` 交给 worker 服务器的主线程。主线程生成再慢，心跳照常发，不会被当成断线。
- 一次只做一个任务。做完：发 result → 卸载这块区域（第 3 步的导出逻辑生成完会留在内存里，要放掉，不然越跑内存越大）。

### 3.3 断线

- 断线后按 1、2、4…最长 30 秒重连，重连后重新 hello（seed 不会变，核对一下）。
- 正在做的任务做完后丢掉结果（coordinator 已经把它收回了）。

## 4. 测试环境

- compose：worker 服务加 `mcdonate.coordinator=minecraft:25580`（`JVM_OPTS`），**worker 的 `SEED` 故意设成别的值**，证明它用的是 coordinator 给的 seed。
- **netem 不给 worker 容器加 NET_ADMIN**：测试时临时起一个带 `NET_ADMIN` 的小容器，和 worker 共用网络（`docker run --rm --network container:<worker> --cap-add NET_ADMIN <带 tc 的镜像> tc qdisc ...`），加完/删完就退出。脚本 `scripts/netem.sh on [延迟] [丢包] | off`，pytest 里用 fixture 开，`finally` 里一定关。只影响 worker 的出站流量（worker → coordinator 方向）。镜像用固定版本，写进脚本。
- coordinator 重启 = 测试服务器重启（`server.restart()`）。

## 5. 小步和验收

### 5.1 `TileFile.toTag/fromTag`（fish，先做，单独提交）

- [ ] 第 3 步的 TileFile JUnit 全部通过；加一条：`fromTag(toTag())` 内容不变，校验和 `read` 一样

### 5.2 coordinator 的任务队列和租约（fish，纯逻辑，不依赖 proto）

JUnit：

- [ ] enqueue 去重；空闲 worker 拿队首
- [ ] 租约到期任务回到队首；心跳续租后不到期
- [ ] worker 断线：手上的任务立刻回队首
- [ ] 2.4 表里每一格：重复结果丢弃、过期结果先到则导入并给新租户发 cancel、`ok = false` 重试 3 次后 `FAILED`
- [ ] 不认识的 taskId 丢弃

### 5.3 网络（fish，等 kexu 的 proto 合进 main）

- [ ] coordinator 监听；worker 握手拿到 seed：worker 的 `SEED` 设成别的值，起来后 `seed` 命令返回 424242
- [ ] 版本不一致：coordinator 回 `workerId = -1` 并断开，worker 日志写清原因
- [ ] 主线程卡住（测试用命令让 worker 主线程 sleep 20 秒）：心跳照常，连接不断

### 5.4 worker 循环和端到端（fish）

- [ ] 放 10 个 tile，全部写进存档；每个 tile 用第 3 步的关卡比较方法和对照世界比，通过
- [ ] 任务进行中 `docker kill` 掉 worker：15 秒内任务回队首；重启 worker 后全部完成，没有 chunk 丢失
- [ ] `netem` 加 100 ms 延迟和 1% 丢包，"放 10 个 tile"仍然通过
- [ ] 重启 coordinator：worker 不崩溃，自动重连；**队列清空**，重新 enqueue 剩下的 tile 后全部完成；重启前写进存档的 tile 还在
- [ ] worker 做完一个 tile 后这块区域被卸载（`/bench` 或 `forceload` 查内存里的 chunk 数不持续增长）

## 6. 顺序和依赖

1. 调度审完本计划，kexu 确认第 1 节（特别是新增的 `cancel` 和 `heartbeat.taskId`）。
2. fish：5.1、5.2（不依赖 proto）。
3. kexu：第 1 步做完、合进 main 后，写 `mcdonate.common.proto` 和 1.4 的 JUnit，合进 main。kexu 的构建要等第 1 步测量跑完才拿重活锁。
4. fish：merge main，做 5.3、5.4。

## 7. 风险

- **proto 来得晚**：5.3 之后都等它。5.1、5.2 先做；必要时 fish 用一个临时的最小实现顶着写网络层，proto 到了再换，接口按第 1 节不变。
- **Mixin 拦 seed 的位置**：`DedicatedServerProperties` 在 `/reload` 或改配置时也会重建。握手结果缓存，只做一次。
- **worker 内存**：导出后不卸载，几十个 tile 后内存会涨。5.4 有一条专门测。
- **netem 镜像要联网拉**：第一次跑会下载，之后有缓存。
