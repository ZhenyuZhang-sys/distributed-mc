"""只读 Minecraft Java 1.21.1 主世界存档。"""

from .chunk import BlockState, Chunk
from .errors import CorruptChunkError, CorruptRegionError, IncompleteChunkError, MCAError, UnsupportedFormatError
from .hashing import HASH_SCHEMA
from .world import WorldMetadata, iter_chunk_positions, read_chunk, read_world_metadata

__all__ = [
    "BlockState", "Chunk", "WorldMetadata", "HASH_SCHEMA", "MCAError",
    "CorruptChunkError", "CorruptRegionError", "IncompleteChunkError",
    "UnsupportedFormatError", "read_chunk", "read_world_metadata", "iter_chunk_positions",
]
