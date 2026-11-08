"""Minecraft Java 1.21.1 主世界的方块状态读取。"""

from __future__ import annotations

from dataclasses import dataclass, field
from functools import cached_property
from types import MappingProxyType
from typing import Mapping

from .errors import CorruptChunkError, IncompleteChunkError, UnsupportedFormatError
from .hashing import hashes

DATA_VERSION = 3955


@dataclass(frozen=True)
class BlockState:
    name: str
    properties: tuple[tuple[str, str], ...] = ()


AIR = BlockState("minecraft:air")


@dataclass(frozen=True)
class Chunk:
    cx: int
    cz: int
    status: str
    data_version: int
    sections: Mapping = field(repr=False)
    y_min: int = -64
    y_max_exclusive: int = 320
    air: BlockState = AIR
    biomes: Mapping = field(default_factory=dict, repr=False)

    def _require_full(self):
        if self.status != "minecraft:full":
            raise IncompleteChunkError(f"chunk ({self.cx},{self.cz}) 状态 {self.status}，不能计算完整方块结果")

    def block_state(self, x: int, y: int, z: int) -> BlockState:
        self._require_full()
        if not (0 <= x < 16 and 0 <= z < 16 and self.y_min <= y < self.y_max_exclusive):
            raise IndexError((x, y, z))
        section = self.sections.get(y // 16)
        if section is None:
            return AIR
        palette, indices = section
        index = 0 if indices is None else indices[(y % 16) * 256 + z * 16 + x]
        return palette[index]

    def biome(self, x: int, y: int, z: int) -> str:
        self._require_full()
        if not (0 <= x < 16 and 0 <= z < 16 and self.y_min <= y < self.y_max_exclusive):
            raise IndexError((x,y,z))
        try:
            palette, indices = self.biomes[y // 16]
        except KeyError as exc:
            raise CorruptChunkError(f"chunk ({self.cx},{self.cz}) section {y//16} 缺少 biome") from exc
        index = 0 if indices is None else indices[((y % 16)//4)*16 + (z//4)*4 + x//4]
        return palette[index]

    @cached_property
    def _hashes(self):
        self._require_full()
        return hashes(self)

    def block_hash(self) -> str:
        return self._hashes[0]

    def column_hashes(self) -> tuple[str, ...]:
        return self._hashes[1]


def decode_chunk(tag, cx: int, cz: int) -> Chunk:
    try:
        version = int(tag["DataVersion"])
        if version != DATA_VERSION:
            raise UnsupportedFormatError(f"chunk ({cx},{cz}): DataVersion {version}，只支持 {DATA_VERSION} (1.21.1)")
        if (int(tag["xPos"]), int(tag["zPos"])) != (cx, cz):
            raise ValueError("NBT 坐标与 region 索引不符")
        if int(tag["yPos"]) != -4:
            raise UnsupportedFormatError(f"chunk ({cx},{cz}): yPos={tag['yPos']}，只支持默认主世界高度")
        status = str(tag["Status"])
        if ":" not in status:
            status = "minecraft:" + status
        known = {"empty", "structure_starts", "structure_references", "biomes", "noise", "surface", "carvers", "features", "initialize_light", "light", "spawn", "full"}
        if status.removeprefix("minecraft:") not in known or not status.startswith("minecraft:"):
            raise ValueError(f"未知生成状态 {status}")
        sections = {}
        if status != "minecraft:full":
            return Chunk(cx, cz, status, version, MappingProxyType(sections))
        seen = set()
        biomes = {}
        for section in tag["sections"]:
            sy = int(section["Y"])
            if sy in seen:
                raise ValueError(f"section Y={sy} 重复")
            seen.add(sy)
            if "biomes" in section and -4 <= sy < 20:
                store = section["biomes"]
                palette = tuple(str(x) for x in store["palette"])
                if not palette or len(palette) > 64: raise ValueError("biome palette 非法")
                indices = None
                if len(palette) > 1:
                    bits = (len(palette)-1).bit_length()
                    per_word = 64//bits
                    words = store["data"]
                    if len(words) != (64+per_word-1)//per_word: raise ValueError("biome data 长度不符")
                    indices = tuple((int(words[i//per_word]) & ((1<<64)-1)) >> ((i%per_word)*bits) & ((1<<bits)-1) for i in range(64))
                    if max(indices) >= len(palette): raise ValueError("biome palette 下标越界")
                biomes[sy] = palette, indices
            states = section.get("block_states")
            if states is None:
                # 当前格式允许仅含光照的 section；缺失方块存储是空气。
                if not any(k in section for k in ("BlockLight", "SkyLight", "biomes")):
                    raise ValueError(f"section {sy} 缺少 block_states")
                continue
            if not -4 <= sy < 20:
                raise UnsupportedFormatError(f"chunk ({cx},{cz}): 主世界高度之外的方块 section {sy}")
            palette = tuple(BlockState(str(p["Name"]), tuple(sorted((str(k), str(v)) for k, v in p.get("Properties", {}).items()))) for p in states["palette"])
            if not palette or len(palette) > 4096 or any(not p.name for p in palette):
                raise ValueError(f"section {sy} palette 非法")
            words = states.get("data")
            if len(palette) == 1:
                if words is not None and len(words):
                    raise ValueError(f"section {sy} 单状态 palette 存在多余 data")
                sections[sy] = palette, None
                continue
            bits = max(4, (len(palette) - 1).bit_length())
            per_word = 64 // bits
            if words is None or len(words) != (4096 + per_word - 1) // per_word:
                raise ValueError(f"section {sy} data 长度不符")
            mask = (1 << bits) - 1
            values = []
            for signed in words:
                word = int(signed) & ((1 << 64) - 1)
                for shift in range(per_word):
                    if len(values) == 4096:
                        break
                    value = (word >> (shift * bits)) & mask
                    if value >= len(palette):
                        raise ValueError(f"section {sy} palette 下标越界 {value}")
                    values.append(value)
            sections[sy] = palette, tuple(values)
        return Chunk(cx, cz, status, version, MappingProxyType(sections), biomes=MappingProxyType(biomes))
    except UnsupportedFormatError:
        raise
    except (KeyError, TypeError, ValueError, IndexError, OverflowError) as exc:
        raise CorruptChunkError(f"chunk ({cx},{cz}): {exc}") from exc
