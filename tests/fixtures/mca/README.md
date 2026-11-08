# 第 0 步参考样本

`java-1.21.1/` 来自本仓库固定版本的 Java 1.21.1 服务端，DataVersion 3955、seed 424242。独立参考世界中先用 RCON 在 `(0,200,0)` 放置 `minecraft:diamond_block`，再用 `execute if block` 确认，正常停服后提取 `(0,0)` 的原始压缩记录。只重建 region 索引，未用待测读取器重新编码方块。`expected.json` 保存命令确定的预期值，`level.dat` 保留真实元数据。

`spawn_profile.json` 来自同一独立参考世界，没有假玩家或 Chunky：出生点 `(0,115,0)`，`spawnChunkRadius=2`。初始 flush 观察到 49 个索引，边界 `[-3,-3,3,3]`；正常停服后，包括出生区生成依赖的半成品共 841 个索引，边界 `[-14,-14,14,14]`。人工标记位于已生成的出生 chunk 内，不加载远处地形。允许范围固定为该停服边界，不添加主观余量，也不按被测世界放宽。参考证据为 `artifacts/calibration-20261108T081716-3480980/{initial,stopped}`。

同 seed 的生成细节可能使出生点高度不同（已观察到 Y=112 和 115）。验收使用本轮 `level.dat` 中的实际出生坐标计算相对边界，不要求 SpawnY 与参考世界相同；版本、seed 和出生区 gamerule 必须匹配。

复现校准（会修改这些参考文件，应检查差异；不是每轮测试的前置命令）：

```bash
scripts/calibrate_step0.sh
```

该入口复用 `.env.test`、N100 限制、独立测试 Compose 项目和共享重活锁。正常完成后关闭容器并删除临时活动世界，原始快照和日志留在忽略提交的 `artifacts/calibration-*`。EULA 须由运行者同意。

不依赖服务器的手工样本由 `tests/mca_fixtures.py` 构造。5-bit 跨 long 边界、负 long 等预期值在测试里显式给出，不使用生产解码器反推答案。二进制 fixture 只保留一个 chunk；不包含账号、RCON 密码或完整世界。
