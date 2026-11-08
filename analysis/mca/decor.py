"""两层比较：把原版自己就不确定的"装饰类"差异和真正的错误分开。

原版生成的不确定性几乎全在 features 阶段（相邻 chunk 放地物的先后不同）：树、藤蔓、草、矿团、
sculk、海带等等。noise 和 surface 阶段是确定的。所以一个位置上两边方块不同时：

- 有一边是 DECOR（地物放的方块）→ 装饰类差异，只做统计（不高于原版互比的上界）；
- 两边都是 TERRAIN_SWAP（地物会互相替换的地形方块，比如泉水把石头换成水、沙土盘把泥土换成沙砾）→ 也算装饰类；
- 其他（最典型的是空气对石头）→ 严格差异，必须是 0。导入丢块、错位、写错 chunk 会落在这里。

清单是拿两个原版世界互比（kexu 第 1 步三个区域 + 本地测试区域）补全的：原版自己在严格层上必须是 0。
方块属性（树叶的 distance、雪的层数……）不同，按名字相同处理的只限 DECOR；其余属性不同也是严格差异。
"""

from __future__ import annotations

from dataclasses import dataclass

# 地物放的方块
DECOR = frozenset("minecraft:" + n for n in """
oak_leaves jungle_leaves birch_leaves spruce_leaves dark_oak_leaves acacia_leaves mangrove_leaves cherry_leaves azalea_leaves flowering_azalea_leaves
oak_log jungle_log birch_log spruce_log dark_oak_log acacia_log mangrove_log cherry_log mangrove_roots muddy_mangrove_roots
vine cocoa bee_nest melon pumpkin bamboo sugar_cane cactus
short_grass tall_grass fern large_fern dandelion poppy azure_bluet oxeye_daisy cornflower lily_of_the_valley allium blue_orchid
orange_tulip red_tulip white_tulip pink_tulip sunflower lilac rose_bush peony dead_bush sweet_berry_bush pink_petals
brown_mushroom red_mushroom brown_mushroom_block red_mushroom_block mushroom_stem
azalea flowering_azalea moss_block moss_carpet rooted_dirt hanging_roots spore_blossom big_dripleaf big_dripleaf_stem small_dripleaf
cave_vines cave_vines_plant glow_lichen pointed_dripstone dripstone_block
kelp kelp_plant seagrass tall_seagrass sea_pickle
tube_coral_block brain_coral_block bubble_coral_block fire_coral_block horn_coral_block
tube_coral brain_coral bubble_coral fire_coral horn_coral
tube_coral_fan brain_coral_fan bubble_coral_fan fire_coral_fan horn_coral_fan
tube_coral_wall_fan brain_coral_wall_fan bubble_coral_wall_fan fire_coral_wall_fan horn_coral_wall_fan
sculk sculk_vein sculk_catalyst sculk_sensor sculk_shrieker
coal_ore iron_ore copper_ore gold_ore redstone_ore lapis_ore diamond_ore emerald_ore
deepslate_coal_ore deepslate_iron_ore deepslate_copper_ore deepslate_gold_ore deepslate_redstone_ore deepslate_lapis_ore
deepslate_diamond_ore deepslate_emerald_ore raw_iron_block raw_copper_block
andesite diorite granite tuff calcite smooth_basalt amethyst_block budding_amethyst amethyst_cluster
small_amethyst_bud medium_amethyst_bud large_amethyst_bud
infested_stone infested_deepslate clay
snow ice packed_ice blue_ice
cobweb spawner chest
deepslate_bricks cracked_deepslate_bricks deepslate_tiles cracked_deepslate_tiles polished_deepslate cobbled_deepslate
deepslate_brick_stairs deepslate_brick_slab deepslate_brick_wall deepslate_tile_stairs deepslate_tile_slab deepslate_tile_wall
cobblestone mossy_cobblestone
""".split())

# 地物会互相替换的地形方块：两边都在这里时算装饰类差异
TERRAIN_SWAP = frozenset("minecraft:" + n for n in """
stone deepslate dirt coarse_dirt grass_block podzol gravel sand sandstone red_sand mud
snow_block powder_snow water lava soul_sand magma_block obsidian
""".split())


# 两边分别是这两种时也算装饰类：
#   air/cave_air：古城等结构用 cave_air 填空，本来就都是空气；
#   air/dirt：大型丛林树的根把树下的空气换成泥土（kexu 第 1 步 jungle，97 个位置）。
EXCUSED_PAIRS = frozenset(frozenset(("minecraft:" + a, "minecraft:" + b)) for a, b in (
    ("air", "cave_air"),
    ("air", "dirt"),
))


def is_decor_diff(a, b) -> bool:
    """a、b 是同一位置上两边不同的 BlockState。"""
    if a.name in DECOR or b.name in DECOR:
        return True
    if a.name == b.name:
        # 名字相同、属性不同：地形方块的属性也是地物改的（草方块上盖了雪就是 snowy=true）
        return a.name in TERRAIN_SWAP
    if frozenset((a.name, b.name)) in EXCUSED_PAIRS:
        return True
    return a.name in TERRAIN_SWAP and b.name in TERRAIN_SWAP


@dataclass
class Diff:
    blocks: int = 0      # 比了多少个方块
    strict: int = 0      # 严格差异（必须是 0）
    decor: int = 0       # 装饰类差异
    examples: list = None  # 几个严格差异的例子，排查用

    def add(self, other: "Diff") -> "Diff":
        self.blocks += other.blocks
        self.strict += other.strict
        self.decor += other.decor
        self.examples = (self.examples or []) + (other.examples or [])
        self.examples = self.examples[:10]
        return self

    @property
    def decor_rate(self) -> float:
        return self.decor / self.blocks if self.blocks else 0.0


def compare_chunks(a, b, max_examples: int = 5) -> Diff:
    """逐块比较两个 chunk（同一位置，都是 full 或都允许半成品）。"""
    from .chunk import AIR

    d = Diff(examples=[])
    for sy in range(a.y_min // 16, a.y_max_exclusive // 16):
        sa, sb = a.sections.get(sy), b.sections.get(sy)
        d.blocks += 4096
        if sa == sb:
            continue
        pa, ia = sa if sa is not None else ((AIR,), None)
        pb, ib = sb if sb is not None else ((AIR,), None)
        for i in range(4096):
            x = pa[0 if ia is None else ia[i]]
            y = pb[0 if ib is None else ib[i]]
            if x == y:
                continue
            if is_decor_diff(x, y):
                d.decor += 1
            else:
                d.strict += 1
                if len(d.examples) < max_examples:
                    d.examples.append({"chunk": (a.cx, a.cz), "x": i % 16, "z": (i // 16) % 16,
                                       "y": sy * 16 + i // 256, "a": x.name, "b": y.name})
    return d


# kexu 第 1 步：原版互比的方块差异率（全部方块计，每个区域 4 对 × 256 chunk）。
# ocean 0.023%、mountain 0.22%、jungle 1.1%。
VANILLA_DECOR_RATE = {"ocean": 0.00023, "mountain": 0.0022, "jungle": 0.011}
# 本地对照太小（比如一片海里装饰类差异接近 0）时，倍数比较会被随机波动卡住。
# 下限取原版测到的最小值（ocean），不是放宽：比原版最安静的地方还安静的差异不算问题。
VANILLA_DECOR_FLOOR = VANILLA_DECOR_RATE["ocean"]
# 本地测不到原版对照时，用最保守的上界（jungle）。
VANILLA_DECOR_MAX = VANILLA_DECOR_RATE["jungle"]
