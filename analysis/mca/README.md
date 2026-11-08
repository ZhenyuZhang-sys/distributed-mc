# 只读存档读取器

支持 Minecraft Java **1.21.1 / DataVersion 3955** 默认主世界，方块高度为 `[-64, 320)`。输入是包含 `level.dat` 和 `region/` 的世界或一致快照根目录。读取不会保存或改写文件；测试必须先完成 `save-off → save-all flush → 复制 → save-on`，或在正常停服后复制。

```python
from analysis.mca import read_chunk, iter_chunk_positions, read_world_metadata

metadata = read_world_metadata("snapshot")  # seed、spawn、data_version、level_name
positions = list(iter_chunk_positions("snapshot"))  # 所有有效索引坐标，含半成品
chunk = read_chunk("snapshot", -1, 0)
if chunk is not None and chunk.status == "minecraft:full":
    state = chunk.block_state(15, 64, 0)  # x/z 是 0..15，y 是绝对高度
    print(state.name, state.properties)  # Properties 按键排序的不可变 tuple
    print(chunk.block_hash())
    print(chunk.column_hashes()[0])      # 共 256 列，索引 z*16+x
```

依赖使用仓库根目录的 `requirements-test.txt`。离线回归：

```bash
.venv/bin/python -m pytest tests/step0/test_00_mca.py -q
```

## 格式及错误契约

- region 使用 4096 字节 sector、8192 字节头部；检查非法偏移、重叠分配、长度和实际 payload 截断。允许最后一个 sector 尚未补齐空白的有效文件。
- 压缩标识支持 gzip（1）、zlib（2）、无压缩（3）。LZ4、未知标识和外部 `.mcc` 明确报 `UnsupportedFormatError`。
- 方块使用 section Y、palette 和不跨 long 的位打包；至少 4 bit，NBT 有符号 long 转无符号后解码。单状态省略 data，缺省空气 section 正规化为 `minecraft:air`。
- 合法的缺失 region/空槽位返回 `None`；无效世界根目录报 `FileNotFoundError`。损坏索引报 `CorruptRegionError`，损坏压缩/NBT/方块数据报 `CorruptChunkError`，这些都不会变成“区块不存在”。底层文件权限/I/O 错误向上抛出。
- 非 full chunk 保留坐标、状态和版本；取方块或 hash 报 `IncompleteChunkError`。显式传 `incomplete=True`（`read_chunk`、`decode_chunk` 都支持）时也解码半成品的方块，用于比较同一阶段的半成品（第 3 步的导出/导入）；半成品的方块不是最终结果。未知版本或非默认高度报 `UnsupportedFormatError`。
- 首版不解码 biome，不比较实体、方块实体、光照、tick 或高度图；这些需另增接口。不能把方块 hash 相同解释成全部存档数据相同。

## Hash schema

`mca-block-states-v1` 使用 SHA-256。前缀依次为 schema 的 ASCII 字节、零字节、两个 big-endian int32 高度边界，以及 `chunk\0` 或 `column\0`。每个方块编码为紧凑、`ensure_ascii=True` 的 JSON `[name, sorted_properties]`，前接 JSON 字节长度的 big-endian uint32。属性是排序后的键值对数组。

整块顺序为 y、z、x，列内顺序为 y；输出是十六进制字符串。坐标、seed、palette 下标、NBT 顺序、时间和光照不进入 hash。相同高度内的同一方块布局得到相同结果；schema 改变时需要更新版本名，不能跨 schema 比较。

独立手工构造样本与服务器真实样本的来源见 [fixture 说明](../../tests/fixtures/mca/README.md)。
