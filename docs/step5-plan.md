# 第 5 步详细计划：通信协议和单个 worker 联网

分工（调度 distributed-mc-77 定的）：

| 谁 | 分支 | 做什么 |
| --- | --- | --- |
| kexu | `kexu` | `common` 里的协议：帧、消息、编解码，和它们的 JUnit |
| fish | `fish` | coordinator（监听、注册表、任务队列、租约、写入）和 worker（连接、领任务、生成、发回），端到端测试 |

**本文第 1 节是两边的接口约定**，改动要两边都同意（经调度转达）。其余各节各写各的。

## 1. 接口约定（common，包 `mcdonate.common.proto`）

### 1.1 帧

- TCP 上每条消息一帧：`4 字节大端长度 N` + `N 字节消息体`。
- 消息体是 **gzip 压缩的 NBT**（`NbtIo.writeCompressed` 的格式），根是 `CompoundTag`。
- 上限 `Frame.MAX_BYTES = 16 MiB`（消息体压缩后的长度）。一个 RESULT 是一个 tile 的 36 个 chunk，压缩后约 1～3 MiB，留足余量。超过上限：发送方抛异常；接收方断开连接。
- 解压后的 NBT 也有上限（`NbtAccounter`，64 MiB），防止压缩炸弹。

```java
public final class Frame {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    /** 消息编码成一帧（含长度前缀）。超过上限抛 FrameTooLarge。 */
    public static byte[] encode(Message m);
}

/** 增量拆帧：网络上收到多少字节就喂多少，凑齐一帧吐一条消息。不是线程安全的，一个连接一个。 */
public final class FrameDecoder {
    /** 喂入字节，返回这次凑齐的消息（可能 0 条或多条）。长度超限、消息解不开抛 ProtocolException，调用方断开连接。 */
    public List<Message> feed(byte[] bytes, int offset, int length);
}
```

### 1.2 消息

`Message` 是 sealed interface，每种消息一个 record。NBT 里用 `type` 字段区分（小写字符串），未知类型解码时抛 `ProtocolException`。

| 类型 | 方向 | 字段 | 说明 |
| --- | --- | --- | --- |
| `hello` | worker → coordinator | `protocol: int`、`name: String` | 连上后第一条。`protocol` 现在是 1 |
| `welcome` | coordinator → worker | `protocol: int`、`workerId: int`、`seed: long`、`dataVersion: int` | 回 hello。worker 用 seed 建世界 |
| `task` | coordinator → worker | `taskId: long`、`tx: int`、`tz: int`、`leaseMs: long` | 生成一个 tile |
| `result` | worker → coordinator | `taskId: long`、`ok: boolean`、`error: String`（ok 时为空串）、`tile: CompoundTag`（ok 时是 `TileFile` 的 NBT，否则空） | 生成结果 |
| `heartbeat` | 双向 | `sentAtMs: long` | 每 5 秒一条；15 秒收不到对方的任何消息就断开 |

说明：

- 比 DEV_PLAN 多了一个 `welcome`：hello 是 worker 说"我是谁"，welcome 是 coordinator 回"你的编号、用哪个 seed"。合成一个双向的 hello 字段会混。
- `result.tile` 直接复用第 3 步的 `TileFile`（`mcdonate.common.TileFile`）。需要 kexu 在 `TileFile` 上加两个方法，或者由 fish 加（谁先需要谁加，不改现有方法）：

```java
public CompoundTag toTag();                                              // write() 写进文件的那个根
public static TileFile fromTag(CompoundTag tag, int dataVersion, long seed); // read() 的校验逻辑，不读文件
```

```java
public sealed interface Message permits Hello, Welcome, Task, Result, Heartbeat {}
public record Hello(int protocol, String name) implements Message {}
public record Welcome(int protocol, int workerId, long seed, int dataVersion) implements Message {}
public record Task(long taskId, int tx, int tz, long leaseMs) implements Message {}
public record Result(long taskId, boolean ok, String error, CompoundTag tile) implements Message {}
public record Heartbeat(long sentAtMs) implements Message {}

public final class MessageCodec {
    public static CompoundTag toTag(Message m);
    public static Message fromTag(CompoundTag tag);   // 字段缺失、类型不对抛 ProtocolException
}

public final class ProtocolException extends RuntimeException { ... }
```

### 1.3 common 的验收（kexu，JUnit）

- [ ] 每种消息编码再解码，内容不变（`result` 带一个真实大小的 tile）
- [ ] 一帧被拆成任意小块（包括 1 字节一块）喂进 `FrameDecoder` 也能拼回；两帧粘在一起一次喂进去能拆成两条
- [ ] 长度前缀超过上限：抛 `ProtocolException`，不分配那么大的缓冲区
- [ ] 消息体不是 gzip、NBT 坏了、`type` 未知、字段缺失：抛 `ProtocolException`
- [ ] 压缩炸弹（压缩后小、解压后超 64 MiB）：抛 `ProtocolException`

## 2. coordinator（fish）

- 监听端口 `25580`（可配置，系统属性 `mcdonate.port`），只在 compose 网络里开，不对外。
- 注册表：连接 → workerId、名字、当前任务、最后一次收到消息的时间。
- 任务队列：`/donor enqueue <tx> <tz>` 手动放任务；空闲 worker 拿队首。
- 租约 30 秒：到期没收到 result，任务回到队首，记一条日志。
- 收到 result：在主线程上走第 3 步的导入逻辑（`TileImporter`，改成从内存的 `TileFile` 导入，不经过文件）。过期后才到的 result 照常按冲突规则导入：内存里有的不写、存档里更完整的不覆盖，所以不会写坏存档。
- `/donor status`：一行 JSON，注册表和队列，测试用。
- 网络线程不碰游戏状态；所有写入都交给主线程。

## 3. worker（fish）

- 启动时不知道 seed：先连 coordinator 拿 welcome，再用这个 seed 建世界。做法：容器启动脚本先跑一个只握手的小程序拿 seed，写进 `server.properties` 的 `level-seed`，再启动服务器。
- 服务器起来后，worker mod 连 coordinator，循环：领 task → 第 3 步的导出逻辑（返回 `TileFile`，不写文件）→ 发 result → 卸载这块区域（不留在内存里）。
- 断线自动重连：间隔 1、2、4… 最长 30 秒。重连后重新 hello。

## 4. 端到端验收（fish）

- [ ] 放 10 个 tile，全部写进存档，和对照世界比通过（第 3 步的关卡比较方法）
- [ ] 任务进行中 `docker kill` 掉 worker：租约到期后任务重发；重启 worker 后全部完成，没有 chunk 丢失
- [ ] 用 `tc netem` 加 100ms 延迟和 1% 丢包，上面的测试仍然通过
- [ ] 重启 coordinator：worker 不崩溃，能自动重连
- [ ] 单元：租约到期，任务回到队列；过期后才到的 result 按冲突规则处理（coordinator 的 JUnit）

## 5. 顺序

1. 两边确认第 1 节的接口（调度转达）。
2. kexu 写 common 的协议和 JUnit；同时 fish 写 coordinator 的队列、租约（纯逻辑，不依赖网络，先单元测试）。
3. common 合进 main 后，fish 接上网络，写 worker 循环和端到端测试。
