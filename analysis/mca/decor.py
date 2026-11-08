"""两层比较：把原版自己就不确定的"装饰类"差异和真正的错误分开。

原版生成的不确定性几乎全在 features 阶段（相邻 chunk 放地物的先后不同）：树、藤蔓、草、矿团、
sculk、海带等等。noise 和 surface 阶段是确定的。所以一个位置上两边方块不同时：

- 有一边是 DECOR（地物放的方块）→ 装饰类差异（decor），只做统计；
- 两边是 TERRAIN_PAIRS 里的一对地形方块（每一对都对得上一种地物：海底沙/沙砾盘、地下泥土/沙砾矿团、
  泉水、树干底下的泥土……）→ 地形互换（terrain），单独计数，也只做统计；
- 其他（空气对石头、没列出的地形互换，比如石头对泥土以外的组合）→ 严格差异（strict），必须是 0。
  导入丢块、错位、截断、写错 chunk 会落在这里。

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
magma_block
rail oak_planks oak_fence dark_oak_planks dark_oak_fence wall_torch torch
""".split())
# 最后两行（11-08 fish 本地对照补的）：magma_block 是海底岩浆块地物；rail 以下是废弃矿井等地下结构的方块。
# 结构件放不放、放在哪，要看邻居 chunk 当时的状态（比如有没有水），原版自己就不确定。

# 地形方块之间、原版自己会不一样的具体组合（kexu 第 1 步实测，见 tests/step3/data/vanilla-diff-pairs.json）。
# 位置是 kexu 的逐块明细里看的（ocean、mountain；jungle 明细当时还没写完，只有汇总）。
TERRAIN_PAIRS = frozenset(frozenset(("minecraft:" + a, "minecraft:" + b)) for a, b in (
    ("gravel", "sand"),        # 海底沙/沙砾盘（ocean Y 52～60）
    ("dirt", "sand"),          # 海底沙盘（ocean Y 27～50）
    ("dirt", "gravel"),        # 海底盘（ocean Y 21～34）、地下泥土/沙砾矿团（mountain Y 3～147）
    ("dirt", "stone"),         # 地下泥土矿团（mountain Y 33～39）
    ("deepslate", "gravel"),   # 地下沙砾矿团（mountain Y −51）
    ("gravel", "stone"),       # 地下沙砾矿团（fish 本地对照 Y 2～5；矿团碰到洞穴空气时可能不放，看邻居）
    ("deepslate", "water"),    # 地下泉水（ocean Y −19～−17，jungle）
    ("stone", "water"),        # 泉水（jungle）
    ("sand", "water"),         # jungle 2 处，泉水或沙盘
    ("gravel", "water"),       # jungle 1 处
    ("lava", "stone"),         # 岩浆泉（mountain 1 处）
    ("dirt", "water"),         # 湖、泉水（fish 本地对照 Y 5～51）
    ("grass_block", "water"),  # 地表小湖（fish 本地对照 Y 79）
    # 湖：LakeFeature 挖出上空气、下水的坑（fish 本地对照，没导入部分 Y 48～49）。放进地形互换而不是整类放过：
    # 受"每处 ≤ 66 方块"约束，导入真丢了一片海会是一大片空气对水，照样判严格。
    ("air", "water"),
))
# 两边分别是这两种时算装饰类：
#   air/cave_air：古城等结构用 cave_air 填空，本来就都是空气；
#   air/dirt：大型丛林树的根把树下的空气换成泥土（kexu 第 1 步 jungle，97 个位置）。
EXCUSED_PAIRS = frozenset(frozenset(("minecraft:" + a, "minecraft:" + b)) for a, b in (
    ("air", "cave_air"),
    ("air", "dirt"),
    # 废弃矿井的走廊用 cave_air 挖开石头（fish 本地对照，Y −50～27）。
    # 噪声洞穴是 air，雕刻器是确定的，所以 cave_air 对石头只会来自结构；
    # 导入丢块表现为 air 对石头，仍是严格差异。
    ("cave_air", "stone"),
    ("cave_air", "deepslate"),
    ("cave_air", "tuff"),
    # 放树时把树干正下方那一格换成泥土（jungle 19180 处，mountain Y 99～124；雪坡上是雪块，Y 110～133）。
    # 属于装饰阶段，和树一起算装饰类；原来放在地形互换里，每棵不同的树都多算一"处"（调度 11-08 定）。
    ("dirt", "grass_block"),
    ("dirt", "snow_block"),
))

# 名字相同、只差属性也算地形互换的方块（草方块上盖了雪就是 snowy=true）
TERRAIN_PROPS = frozenset({"minecraft:grass_block"})

# 原版互比里地形互换的最小非零比例（ocean：637 处 ÷ 4 对 × 256 chunk × 98304 方块 ≈ 6.3e-6）。
# 统计层的下限；比原版最少的地方还少，不算问题。
VANILLA_TERRAIN_FLOOR = 637 / (4 * 256 * 98304)


def classify(a, b) -> str:
    """a、b 是同一位置上两边不同的 BlockState，返回 decor / terrain / strict。"""
    if a.name in DECOR or b.name in DECOR:
        return "decor"
    if a.name == b.name:
        return "terrain" if a.name in TERRAIN_PROPS else "strict"
    pair = frozenset((a.name, b.name))
    if pair in EXCUSED_PAIRS:
        return "decor"
    if pair in TERRAIN_PAIRS:
        return "terrain"
    return "strict"


_TREE_EXTRA = frozenset("minecraft:" + n for n in "vine cocoa bee_nest mangrove_roots muddy_mangrove_roots".split())
_TREE_PAIRS = frozenset(frozenset(("minecraft:" + a, "minecraft:" + b)) for a, b in (
    ("dirt", "grass_block"), ("dirt", "snow_block"), ("air", "dirt")))


def is_tree(a, b) -> bool:
    """这处装饰类差异和树有关（只用于记录，见 analysis/seam.py）。"""
    for n in (a.name, b.name):
        if n.endswith("_log") or n.endswith("_leaves") or n in _TREE_EXTRA:
            return True
    return frozenset((a.name, b.name)) in _TREE_PAIRS


def is_decor_diff(a, b) -> bool:
    """不算严格差异（装饰类或列出的地形互换）。"""
    return classify(a, b) != "strict"


@dataclass
class Diff:
    blocks: int = 0      # 比了多少个方块
    strict: int = 0      # 严格差异（必须是 0）
    decor: int = 0       # 装饰类差异
    terrain: int = 0     # 列出的地形互换
    terrain_positions: list = None  # 地形互换的方块坐标（世界坐标 x, y, z），按"处"统计用
    tree: int = 0        # 装饰类里和树有关的（原木、树叶、藤蔓、可可、树干底下那格），只记录
    examples: list = None  # 几个严格差异的例子，排查用

    def add(self, other: "Diff") -> "Diff":
        self.blocks += other.blocks
        self.strict += other.strict
        self.decor += other.decor
        self.terrain += other.terrain
        self.tree += other.tree
        self.terrain_positions = (self.terrain_positions or []) + (other.terrain_positions or [])
        self.examples = (self.examples or []) + (other.examples or [])
        self.examples = self.examples[:10]
        return self

    @property
    def decor_rate(self) -> float:
        return self.decor / self.blocks if self.blocks else 0.0

    @property
    def terrain_rate(self) -> float:
        return self.terrain / self.blocks if self.blocks else 0.0


def compare_chunks(a, b, max_examples: int = 5) -> Diff:
    """逐块比较两个 chunk（同一位置，都是 full 或都允许半成品）。"""
    from .chunk import AIR

    d = Diff(examples=[], terrain_positions=[])
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
            kind = classify(x, y)
            if kind == "decor":
                d.decor += 1
                if is_tree(x, y):
                    d.tree += 1
            elif kind == "terrain":
                d.terrain += 1
                d.terrain_positions.append((a.cx * 16 + i % 16, sy * 16 + i // 256, a.cz * 16 + (i // 16) % 16))
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


# 地形互换一处最多多少个方块：原版单个矿团大小的 2 倍。
# 出处：1.21.1 net.minecraft.data.worldgen.features.OreFeatures，ore_dirt、ore_gravel 都是
# new OreConfiguration(..., 33)（size = 33）。调度 11-08 定：超过就算严格差异，
# 免得"处数下限 1"让导入写错的一整块混过去。
ORE_BLOB_SIZE = 33
TERRAIN_CLUSTER_MAX = 2 * ORE_BLOB_SIZE


def terrain_clusters(positions) -> list[list[tuple[int, int, int]]]:
    """把地形互换的方块按 6 邻接连成"处"。"""
    left = set(positions)
    out = []
    while left:
        start = left.pop()
        group, todo = [start], [start]
        while todo:
            x, y, z = todo.pop()
            for n in ((x + 1, y, z), (x - 1, y, z), (x, y + 1, z), (x, y - 1, z), (x, y, z + 1), (x, y, z - 1)):
                if n in left:
                    left.remove(n)
                    group.append(n)
                    todo.append(n)
        out.append(group)
    return out


def relative_check(inside: Diff, outside: Diff, factor: float = 1.5) -> list[str]:
    """统计层。返回不满足的说明，空列表表示通过。

    - 装饰类：导入部分的方块差异率不高于同一轮没导入部分（本地原版互比）的 factor 倍（下限 VANILLA_DECOR_FLOOR）。
    - 地形互换：很少、成团出现（一个矿团几十个方块），按方块比例比全看一个矿团落在哪，所以按"处"数，
      用单侧二项检验看导入部分是否偏多（见 TERRAIN_ALPHA）。横跨两边的一处算导入部分的。
      每一处不能超过 TERRAIN_CLUSTER_MAX 个方块，超过算严格差异。
    """
    bad = []
    limit = max(outside.decor_rate * factor, VANILLA_DECOR_FLOOR)
    if inside.decor_rate > limit:
        bad.append(f"装饰类：导入部分 {inside.decor_rate:.5%} > 上限 {limit:.5%}（没导入部分 {outside.decor_rate:.5%}）")
    inside_pos = set(inside.terrain_positions or [])
    clusters = terrain_clusters(inside_pos | set(outside.terrain_positions or []))
    big = [len(c) for c in clusters if len(c) > TERRAIN_CLUSTER_MAX]
    if big:
        bad.append(f"严格：地形互换有 {len(big)} 处超过 {TERRAIN_CLUSTER_MAX} 个方块（{big}），比原版单个矿团的 2 倍还大")
    n_in = sum(1 for c in clusters if any(p in inside_pos for p in c))
    n_out = len(clusters) - n_in
    total = inside.blocks + outside.blocks
    pval = binomial_tail(n_in, n_in + n_out, inside.blocks / total) if total else 1.0
    if pval < TERRAIN_ALPHA:
        bad.append(f"地形互换：导入部分 {n_in} 处、没导入部分 {n_out} 处，按面积导入部分偏多（单侧二项检验 p={pval:.4f} < {TERRAIN_ALPHA}）")
    return bad


# 地形互换处数的判法（调度 11-08 定，看到第 5 步那轮数据后改的）：处数只有 0～3，按比例 × 1.5 判不稳。
# 假设导入不影响地形互换，每一处落在导入部分的概率 = 导入部分方块数 ÷ 总方块数；
# 导入部分的处数达到观察值的概率 < TERRAIN_ALPHA 才算偏多。
TERRAIN_ALPHA = 0.01


def binomial_tail(k: int, n: int, p: float) -> float:
    """P(X ≥ k)，X ~ Binomial(n, p)。"""
    from math import comb

    return sum(comb(n, i) * p ** i * (1 - p) ** (n - i) for i in range(k, n + 1))
