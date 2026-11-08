"""读 common 里共用的装饰类清单（docs/step8-plan.md 第 7 节第 6 条）。

Python（测试、分析）和 Java（coordinator）读同一份 `common/src/main/resources/mcdonate/decor.json`。
classify 的判定顺序和 fish 的 analysis/mca/decor.py 一样，返回 decor / terrain / strict。
"""
from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

PATH = Path(__file__).resolve().parents[1] / 'common/src/main/resources/mcdonate/decor.json'


class Rules:
    def __init__(self, data: dict):
        if data.get('schema') != 1:
            raise ValueError('decor.json schema 不是 1')
        self.decor = frozenset(data['decor'])
        self.terrain_props = frozenset(data['terrain_props'])
        self.terrain_pairs = frozenset(frozenset(p) for p in data['terrain_pairs'])
        self.excused_pairs = frozenset(frozenset(p) for p in data['excused_pairs'])
        if any(len(p) != 2 for p in self.terrain_pairs | self.excused_pairs):
            raise ValueError('每对必须是两个不同的方块')
        self.vanilla_decor_rate = dict(data['vanilla_decor_rate'])
        self.vanilla_terrain_floor = data['vanilla_terrain_floor']
        self.terrain_cluster_max = int(data['terrain_cluster_max'])
        self.terrain_alpha = float(data['terrain_alpha'])
        if self.terrain_cluster_max < 1 or not 0 < self.terrain_alpha < 1:
            raise ValueError('地形互换判法参数不合法')

    def classify_names(self, a: str, b: str, same_name_props_differ: bool = False) -> str:
        """a、b 是同一位置上两边不同的方块名；名字相同时表示只有属性不同。"""
        if a in self.decor or b in self.decor:
            return 'decor'
        if a == b:
            return 'terrain' if a in self.terrain_props else 'strict'
        pair = frozenset((a, b))
        if pair in self.excused_pairs:
            return 'decor'
        if pair in self.terrain_pairs:
            return 'terrain'
        return 'strict'

    def classify(self, a, b) -> str:
        """a、b 是同一位置上两边不同的 BlockState（有 .name）。"""
        return self.classify_names(a.name, b.name)


@lru_cache(maxsize=None)
def load(path: Path = PATH) -> Rules:
    return Rules(json.loads(Path(path).read_text()))
