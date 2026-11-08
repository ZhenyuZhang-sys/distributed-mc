"""独立构造很小的 Anvil 样本，不调用生产读取器。"""

import gzip
import io
import struct
import zlib
from pathlib import Path

import nbtlib as n


def world(path: Path) -> Path:
    path.mkdir(parents=True, exist_ok=True)
    (path / "region").mkdir(exist_ok=True)
    n.File({"Data": n.Compound({
        "DataVersion": n.Int(3955), "SpawnX": n.Int(-17),
        "SpawnY": n.Int(80), "SpawnZ": n.Int(33),
        "WorldGenSettings": n.Compound({"seed": n.Long(424242)}),
    })}).save(path / "level.dat", gzipped=True)
    return path


def section(y=-4, names=("minecraft:stone",), words=None, properties=None):
    palette = []
    for name in names:
        entry = n.Compound({"Name": n.String(name)})
        if properties:
            entry["Properties"] = n.Compound({k: n.String(v) for k, v in properties.items()})
        palette.append(entry)
    states = n.Compound({"palette": n.List[n.Compound](palette)})
    if words is not None:
        states["data"] = n.LongArray(words)
    return n.Compound({"Y": n.Byte(y), "block_states": states})


def chunk(cx=0, cz=0, sections=None, status="minecraft:full", version=3955):
    return n.File({
        "DataVersion": n.Int(version), "xPos": n.Int(cx), "zPos": n.Int(cz), "yPos": n.Int(-4),
        "Status": n.String(status),
        "sections": n.List[n.Compound]([section()] if sections is None else sections),
    })


def write_chunk(root, cx=0, cz=0, *, tag=None, compression=2):
    payload = io.BytesIO()
    (tag if tag is not None else chunk(cx, cz)).write(payload)
    raw = payload.getvalue()
    compressed = {1: gzip.compress, 2: zlib.compress, 3: lambda b: b}[compression](raw)
    record = struct.pack(">I", len(compressed) + 1) + bytes([compression]) + compressed
    count = (len(record) + 4095) // 4096
    header = bytearray(8192)
    slot = cx % 32 + 32 * (cz % 32)
    struct.pack_into(">I", header, slot * 4, (2 << 8) | count)
    path = root / "region" / f"r.{cx // 32}.{cz // 32}.mca"
    path.write_bytes(header + record.ljust(count * 4096, b"\0"))
    return path
