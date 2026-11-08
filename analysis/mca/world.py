"""世界元数据和 region 枚举；不会写入 level.dat。"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

import nbtlib

from .chunk import DATA_VERSION, decode_chunk
from .errors import MCAError, CorruptChunkError, UnsupportedFormatError
from .region import locations, read_record

REGION_NAME = re.compile(r"r\.(-?\d+)\.(-?\d+)\.mca")


def world_path(path) -> Path:
    root = Path(path)
    if not root.is_dir() or not (root / "level.dat").is_file() or not (root / "region").is_dir():
        raise FileNotFoundError(f"不是有效世界/快照根目录：{root}")
    return root


@dataclass(frozen=True)
class WorldMetadata:
    seed: int
    spawn: tuple[int, int, int]
    data_version: int
    level_name: str | None = None


def read_world_metadata(world_dir) -> WorldMetadata:
    root = world_path(world_dir)
    try:
        # 不使用 nbtlib.File 的上下文管理器，它退出时会保存。
        tag = nbtlib.load(root / "level.dat", gzipped=True)["Data"]
        version = int(tag["DataVersion"])
        if version != DATA_VERSION:
            raise UnsupportedFormatError(f"{root}: DataVersion {version} 不支持")
        return WorldMetadata(
            int(tag["WorldGenSettings"]["seed"]),
            tuple(int(tag[f"Spawn{axis}"]) for axis in "XYZ"), version,
            str(tag["LevelName"]) if "LevelName" in tag else None,
        )
    except OSError:
        raise
    except UnsupportedFormatError:
        raise
    except Exception as exc:
        raise CorruptChunkError(f"{root}/level.dat: {exc}") from exc


def read_chunk(world_dir, cx: int, cz: int, *, incomplete: bool = False):
    root = world_path(world_dir)
    path = root / "region" / f"r.{cx // 32}.{cz // 32}.mca"
    try:
        tag = read_record(path, cx % 32 + 32 * (cz % 32))
        return None if tag is None else decode_chunk(tag, cx, cz, incomplete=incomplete)
    except FileNotFoundError:
        return None
    except MCAError as exc:
        raise type(exc)(f"{path}, chunk ({cx},{cz}): {exc}") from exc


def iter_chunk_positions(world_dir):
    root = world_path(world_dir)
    result = []
    for path in (root / "region").glob("*.mca"):
        match = REGION_NAME.fullmatch(path.name)
        if match is None:
            raise CorruptChunkError(f"region 文件名非法：{path}")
        rx, rz = map(int, match.groups())
        for slot in locations(path):
            result.append((rx * 32 + slot % 32, rz * 32 + slot // 32))
    yield from sorted(result)
