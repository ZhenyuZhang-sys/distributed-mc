"""只读 Anvil 索引和压缩记录。"""

from __future__ import annotations

import gzip
import io
import struct
import zlib
from pathlib import Path

import nbtlib

from .errors import CorruptChunkError, CorruptRegionError, UnsupportedFormatError

SECTOR = 4096


def locations(path: Path) -> dict[int, tuple[int, int]]:
    size = path.stat().st_size
    if size == 0:
        # 原版读还没有的 region 时会留下 0 字节的文件，RegionFile 读到 EOF 就当没有 chunk（只有读到一部分头部才警告）
        return {}
    with path.open("rb") as stream:
        header = stream.read(2 * SECTOR)
    if len(header) != 2 * SECTOR:
        raise CorruptRegionError(f"{path}: region 头部/sector 截断")
    used = {0, 1}
    out = {}
    for slot, entry in enumerate(struct.unpack(">1024I", header[:SECTOR])):
        if entry == 0:
            continue
        offset, count = entry >> 8, entry & 255
        # Mojang 在关闭 region 前可能尚未填充最后一个 sector 的尾部。
        if offset < 2 or count == 0 or (offset + count - 1) * SECTOR >= size:
            raise CorruptRegionError(f"{path}: slot {slot} 非法 sector {offset}+{count}")
        sectors = set(range(offset, offset + count))
        if used.intersection(sectors):
            raise CorruptRegionError(f"{path}: slot {slot} sector 重叠")
        used.update(sectors)
        out[slot] = offset, count
    return out


def read_record(path: Path, slot: int):
    entries = locations(path)
    if slot not in entries:
        return None
    offset, count = entries[slot]
    with path.open("rb") as stream:
        stream.seek(offset * SECTOR)
        length_bytes = stream.read(4)
        if len(length_bytes) != 4:
            raise CorruptRegionError(f"{path}: slot {slot} 记录头部截断")
        length = struct.unpack(">I", length_bytes)[0]
        if not 1 <= length <= count * SECTOR - 4 or offset * SECTOR + 4 + length > path.stat().st_size:
            raise CorruptRegionError(f"{path}: slot {slot} payload 长度非法 {length}")
        record = stream.read(length)
    if len(record) != length:
        raise CorruptRegionError(f"{path}: slot {slot} payload 截断")
    compression = record[0]
    if compression & 128:
        raise UnsupportedFormatError(f"{path}: slot {slot} 暂不支持外部 .mcc 存储")
    decompress = {1: gzip.decompress, 2: zlib.decompress, 3: lambda data: data}.get(compression)
    if decompress is None:
        raise UnsupportedFormatError(f"{path}: slot {slot} 未支持压缩标识 {compression}")
    try:
        raw = decompress(record[1:])
        stream = io.BytesIO(raw)
        result = nbtlib.File.parse(stream, byteorder="big")
        if stream.tell() != len(raw):
            raise ValueError("NBT 结尾有额外数据")
        return result
    except Exception as exc:
        raise CorruptChunkError(f"{path}: slot {slot} 解压/NBT 失败：{exc}") from exc
