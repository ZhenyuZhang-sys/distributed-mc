"""不依赖 palette 下标、NBT 顺序和进程 hash 随机种子。"""

import hashlib
import json
import struct
from functools import lru_cache

HASH_SCHEMA = "mca-block-states-v1"


@lru_cache(maxsize=8192)
def encode_state(state) -> bytes:
    raw = json.dumps([state.name, state.properties], ensure_ascii=True, separators=(",", ":")).encode("utf-8")
    return struct.pack(">I", len(raw)) + raw


def hashes(chunk):
    header = HASH_SCHEMA.encode("ascii") + b"\0" + struct.pack(">ii", chunk.y_min, chunk.y_max_exclusive)
    whole = hashlib.sha256(header + b"chunk\0")
    columns = [hashlib.sha256(header + b"column\0") for _ in range(256)]
    air = encode_state(chunk.air)
    for section_y in range(chunk.y_min // 16, chunk.y_max_exclusive // 16):
        section = chunk.sections.get(section_y)
        if section is None:
            whole.update(air * 4096)
            for column in columns:
                column.update(air * 16)
            continue
        palette, indices = section
        codes = [encode_state(state) for state in palette]
        if indices is None:
            whole.update(codes[0] * 4096)
            for column in columns:
                column.update(codes[0] * 16)
            continue
        for i, index in enumerate(indices):
            code = codes[index]
            whole.update(code)
            columns[i % 256].update(code)
    return whole.hexdigest(), tuple(column.hexdigest() for column in columns)
