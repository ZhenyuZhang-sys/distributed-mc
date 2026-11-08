"""把 fish 的 analysis/mca/decor.py 导出成 common 里共用的 decor.json（Java 和 Python 读同一份）。

decor.py 是清单的来源；改了它就重新跑：`.venv/bin/python -m analysis.export_decor`。
tests/step8/test_00_decor_rules.py 会检查 JSON 和这里导出的一致。
"""
from __future__ import annotations

import json

from analysis.decor_rules import PATH
from analysis.mca import decor as fish


def _pairs(pairs):
    return sorted(sorted(p) for p in pairs)


def build() -> dict:
    return {
        'schema': 1,
        'source': 'fish 的 analysis/mca/decor.py 导出（analysis/export_decor.py）；判定顺序见 docs/step8-plan.md 第 7 节',
        'decor': sorted(fish.DECOR),
        'terrain_props': sorted(fish.TERRAIN_PROPS),
        'terrain_pairs': _pairs(fish.TERRAIN_PAIRS),
        'excused_pairs': _pairs(fish.EXCUSED_PAIRS),
        'vanilla_decor_rate': dict(sorted(fish.VANILLA_DECOR_RATE.items())),
        'vanilla_terrain_floor': fish.VANILLA_TERRAIN_FLOOR,
        # 地形互换的判法：6 邻接连成一处，每处 ≤ terrain_cluster_max 个方块（超过整处算严格）；
        # 导入部分的处数用单侧二项检验，p < terrain_alpha 算偏多。
        'terrain_cluster_max': fish.TERRAIN_CLUSTER_MAX,
        'terrain_alpha': fish.TERRAIN_ALPHA,
    }


def main():
    PATH.write_text(json.dumps(build(), ensure_ascii=False, indent=1, sort_keys=True) + '\n')
    print(f'写好 {PATH}')


if __name__ == '__main__':
    main()
