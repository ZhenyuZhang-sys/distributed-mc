# 7.6a 等 donor：原型设计笔记

只写设计，还没写代码。依据：1.21.1 源码（Mojang 名字）里的 `ChunkMap`、`IOWorker`、`ServerChunkCache`。

## 1. 要解决什么

原版要生成某个 chunk 时，如果它所在的 tile 正租给 donor、而且很快回来，就等一下，让原版直接读 donor 的结果，不自己从头生成。难点是第 6 步的导入规则：区域里有"在内存里"的 chunk 就整个丢弃；而原版开始要一个 chunk 时，它的 `ChunkHolder` 已经在内存里了。

## 2. 插在哪：`ChunkMap.scheduleChunkLoad`

1.21.1 源码：

```java
private CompletableFuture<ChunkAccess> scheduleChunkLoad(ChunkPos chunkPos) {
    return this.readChunk(chunkPos).thenApply(...).thenApplyAsync(optional -> {
        if (optional.isPresent()) { ... ChunkSerializer.read(...) ... }   // 存档里有：用存档的
        else { ... new ProtoChunk(...) ... }                                 // 没有：从头生成
    }, this.mainThreadExecutor);
}
```

chunk 第一次被需要时都走这里（`applyStep` 的 `EMPTY` 那一步）：先异步读存档，读到就用，读不到才从头生成。**在它前面插等待**，等完再走原来的逻辑，就会读到 donor 写进去的数据。

Mixin（coordinator，用 MixinExtras 的 `@WrapMethod`，第 2 步的 bench 已经这样用过）：

```java
@Mixin(ChunkMap.class)
abstract class ChunkMapWaitDonorMixin {
    @Shadow @Final ServerLevel level;

    @WrapMethod(method = "scheduleChunkLoad")
    private CompletableFuture<ChunkAccess> mcdonate$waitDonor(ChunkPos pos,
            Operation<CompletableFuture<ChunkAccess>> original) {
        if (level.dimension() != Level.OVERWORLD || !WaitDonor.enabled()) {
            return original.call(pos);
        }
        CompletableFuture<Void> gate = WaitDonor.gate(pos);   // 不该等就返回 null
        if (gate == null) {
            return original.call(pos);
        }
        return gate.completeOnTimeout(null, WaitDonor.MAX_WAIT_MS, TimeUnit.MILLISECONDS)
                   .thenComposeAsync(v -> {
                       WaitDonor.leave(pos);              // 离开"正在等"的集合
                       return original.call(pos);         // 原来的读取（读存档 → 有就用）
                   }, Util.backgroundExecutor());
    }
}
```

- 等待是一个异步 future，**不卡任何线程**：`scheduleChunkLoad` 本来就返回 future，我们只是把原来的 future 往后接。
- `original.call(pos)` 第一步是 `readChunk` → `ChunkStorage.read` → `IOWorker.loadAsync`，是线程安全的，可以在任何线程上调用；后面它自己会用 `thenApplyAsync(..., mainThreadExecutor)` 回到主线程，和原来一样。

### 什么时候等（`WaitDonor.gate`）

- 这个 chunk 在某个 LEASED 任务的区域里（tile 加一圈），而且按 7.3 的实测耗时，预计 `MAX_WAIT_MS`（2 秒）内回来。
- 进入等待时把这个 chunk 加进"正在等"的集合，并登记一个 gate（同一个任务的区域共用一个 gate）。
- 不满足就返回 null，原版照常。donor 全掉线时没有 LEASED 任务，永远不等，退化为原版。

## 3. 导入规则怎么放宽

- 导入时（`TileImporter.importStrict` → `chunkInfo`），"正在等"集合里的 chunk **不算在内存里**：按存档里的情况分类（空、归属同一个世界、服务器的）。
- 理由：这个 chunk 的 holder 虽然在，但原版还没从磁盘读它、也还没开始生成（卡在我们的 gate 上），holder 里没有 chunk 对象。这时写存档不会被覆盖：holder 没有 chunk，卸载时不会存盘。
- 区域里其他**真的**在内存里的 chunk（已经读进来或在生成）照旧导致整个丢弃。所以只有"整个区域都在等或都不在内存里"时才能写进去——原版要一个 tile 时通常会一起要它的一整片邻居，它们会同时卡在 gate 上，这种情况是常见的。

## 4. 先写后读的保证

1.21.1 的 `IOWorker`：

- 只有一个任务队列（`ProcessorMailbox`，前台优先级 FIFO）；`store` 和 `loadAsync` 都是往这个队列里提交任务。
- `loadAsync` 先查 `pendingWrites`（还没真正落盘的写），有就直接返回它的拷贝。

所以顺序是：

1. 导入在主线程上调用 `ChunkMap.write`（→ `IOWorker.store`），36 个 chunk 的写都排进队列；
2. 然后才完成 gate；
3. gate 完成后 `original.call(pos)` → `IOWorker.loadAsync`，排在那些写的后面，读到的就是 donor 的数据（哪怕还没落盘，也会从 `pendingWrites` 读到）。

## 5. 一个会卡主线程的坑：导入要提交到 chunk 缓存自己的队列

- 有些地方在主线程上**同步**等 chunk：`ServerChunkCache.getChunk(...)` 在主线程上会 `mainThreadProcessor.managedBlock(future::isDone)`。等的时候主线程只执行 **chunk 缓存自己的任务队列**（`ServerChunkCache.MainThreadExecutor`），不执行 `server.execute(...)` 提交到服务器队列的任务。
- 第 5 步的导入是 `server.execute(() -> importResult(...))`。如果主线程正同步等一个卡在 gate 上的 chunk，这个导入要等 managedBlock 结束才执行，gate 只能等到 2 秒超时——**服务器卡 2 秒**。
- 所以：有 gate 在等的任务，结果回来时把导入提交到 `level.getChunkSource()` 的 `mainThreadProcessor`（它是 `ChunkMap` 构造时传进来的那个 `mainThreadExecutor`，用 accessor 拿）。managedBlock 期间会执行它，导入完成 → gate 完成 → 同步等待结束。没有 gate 的任务照旧用 `server.execute`。

## 6. 怎么判断"没死锁、没卡顿"

原型测试（7.6a）：一个 tile 正租给 donor，把假玩家传送到 tile 中心。

- **在进步**：测试期间每秒发一次 rcon 命令（比如 `donor status`），每次都要在 1 秒内回来（rcon 由主线程处理，主线程卡住就回不来）。
- **没长时间卡顿**：从 spark 或 `/bench`（第 2 步）读这段时间的最长 tick 时间，不超过 `MAX_WAIT_MS` 的一半（1 秒）。卡到接近 2 秒说明第 5 节的坑没躲开。
- **真的读到了 donor 的数据**：tile 的 16 个 chunk 在服务器上没跑 noise、features（`/bench chunk`，第 3 步的计数）；存档里和 donor 导出逐列一致；`waitdonor_hits` > 0。
- **出问题时有证据**：任何一条不满足就在容器里 `jcmd <pid> Thread.print`，把线程栈存进证据目录。
- **超时退化**：donor 故意不回结果（`/donor sleep` 让 worker 主线程卡住），2 秒后原版照常生成，`waitdonor_timeouts` +1，存档正确（第 6 步的检查）。

## 7. 还没想清楚的（做原型时验证）

- `applyStep(EMPTY)` 是在哪个线程上调用 `scheduleChunkLoad` 的（世界生成的 mailbox 线程？），我们的 `thenComposeAsync` 换线程会不会打乱它的假设。原型里打日志确认。
- 原版在等 EMPTY 那一步时，会不会因为别的原因（比如票被撤掉）放弃这个 holder；放弃后 gate 完成再读一次存档是否无害（应该无害：只是多读一次）。
- 光照：导入的 chunk 停在 `initialize_light`，原版接着做光照时，邻居可能已经先算过。第 3 步的关卡已经覆盖了这种情况（导入后服务器接着生成），但 7.6 改变了先后，原型要做第 6 步的正确性检查。

## 8. 做不出来时

7.6a 的任何一条判断不满足、而且要改原版逻辑太多（比如要改 `ChunkGenerationTask`）时，停在 7.6a，开关不进消融，报告写明原因和原型的结果，作为已知限制。
