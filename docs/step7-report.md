# 第 7 步报告（进行中；并行跑，粗测）

计划见 docs/step7-plan.md。11-09 起按用户决定"只求大概、并行跑"：数字只看方向，正式数字到部署机器上测。

## 7.6a 等 donor 原型

- 端到端 3 条全过（tests/step7/test_04_waitdonor.py，`1a7bc56` 起）：
    1. 接近时等 donor：闸门等到结果，tile 的 36 个 chunk 全部写入，tile 上服务器没跑 noise、features，rcon 最慢 < 1 秒；
    2. 主线程同步要一个正租着的 chunk：不等（放弃闸门），主线程只卡原版生成这个 chunk 的时间；
    3. donor 卡住：最多等 2 秒超时，原版照常生成，主线程不卡。
- **变异检查（11-09）**：计划 7.6a 要求"把导入故意改回 `server.execute`，同步等 chunk 那条测试要失败"。
  用 `MCD_MUTATE_WAITIMPORT=server scripts/test.sh step7 -k waitdonor` 跑（测试夹具发 `/donor waitimport server`，命令日志里确认生效）：
  **3 条全过，变异没被抓到。** 原因不是测试漏了，而是这条要求的前提已经不成立：原型第二版起，主线程同步要 chunk 时
  （`getChunk` 的 `managedBlock`）会放弃所有闸门、不建新的（`ServerChunkCacheSyncMixin`），主线程不会再等在闸门上，
  所以导入排在哪个队列都不会让主线程卡住。"导入走 chunk 缓存自己的队列"是第一版（没有放弃闸门时）为了不卡 2 秒加的，
  现在是多余但无害的保险，保留。计划里那条测试要求改成"同步要 chunk 时不等、不卡"（第 2 条已经覆盖）。
- **换成防现在的风险的变异检查（11-09，调度要求）**：加测试开关 `/donor waitsync wait`（主线程同步要 chunk 时照样建闸门、照样等），
  第 2 条测试加"同步要 chunk 期间不建闸门"的检查。`MCD_MUTATE_WAITSYNC=wait scripts/test.sh step7 -k waitdonor`：
  **第 2 条失败**（期间建了 9 个闸门，同步要 chunk 用了 1188 ms，等到 donor 的结果才返回）；不加变异 3 条全过（同一条 786 ms，
  是原版自己生成这个 chunk 的时间，2 核测试服务器上）。说明这条测试确实防住了"主线程卡在闸门上等 donor"。

## 快速对比（7.1～7.6，进行中）

结果在 f3 槽位跑完后补。注意：f3 的 donor 核（112-119）是 f1 donor 核（48-55）的超线程兄弟，两边 donor 同时忙时会互相拖慢。
