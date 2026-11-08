"""不存在用 None 表示；格式错误不能吞掉。"""


class MCAError(ValueError):
    pass


class CorruptRegionError(MCAError):
    pass


class CorruptChunkError(MCAError):
    pass


class UnsupportedFormatError(MCAError):
    pass


class IncompleteChunkError(MCAError):
    pass
