"""第 8 步 8.0：第 8 步计划里三条推测的原版验证。判据在算数据之前定好（11-08）。

数据由 `python -m analysis.vanilla_checks --campaign baseline-20261108T154535` 从第 1 步粗测的快照生成，
提交在 tests/step8/data/vanilla-checks.json，所以别的机器上也能复核。
"""
import json
from pathlib import Path

DATA = json.loads((Path(__file__).parent / 'data/vanilla-checks.json').read_text())


def test_covers_all_main_pairs():
    # 每区域 4 对（1 核重复、4 核重复、1 对 4 核两对），2 核补充组不算。
    for area in ('ocean', 'mountain', 'jungle'):
        assert sum(p['area_id'] == area for p in DATA['pairs']) == 4


def test_biomes_identical():
    # 计划 4.1 第 2 条：生物群系在 features 之前，原版两次应当完全一样。
    assert all(p['biome_sections'] > 0 for p in DATA['pairs'])
    assert sum(p['biome_diff_sections'] for p in DATA['pairs']) == 0


def test_surface_height_not_strict():
    # 计划 4.1 第 3 条原判据（事先定的）：地表高度完全一样。**不成立**：jungle 每对 51～69 列不同，
    # 来自大丛林树的根把空气换成泥土；ocean 是海草，mountain 是地表那格石头被煤矿替换。
    # 看过数据后另试过"水和岩浆也透明"，更差（海底沙盘、砾石盘会改地面），不采用。
    # 结论：地表高度移到统计层（计划 4.2），这里记下原版的量级，作为统计层阈值的起点。
    assert all(p['height_columns'] == 256 * 256 for p in DATA['pairs'])
    jungle = [p['height_diff_columns'] for p in DATA['pairs'] if p['area_id'] == 'jungle']
    assert min(jungle) > 0  # 原判据确实不成立，不是没算
    for p in DATA['pairs']:
        assert p['height_diff_columns'] / p['height_columns'] < 0.002, p
        assert p['height_max_abs'] <= 5, p


def test_valued_ore_direction_symmetric():
    # 计划 4.2：单侧符号检验的前提。双侧二项检验 p >= 0.01，每个区域和合起来都要满足。
    for area, d in DATA['direction'].items():
        assert d['p_two_sided'] >= 0.01, (area, d)
