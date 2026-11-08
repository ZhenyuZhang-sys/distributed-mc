"""导入边界附近的差异密度（只记录，不判对错）。

按 chunk 离最近的导入 tile 有几圈（切比雪夫距离，tile 里面是 0）分组，算每组每个 chunk 平均有多少个
装饰类、树类、地形互换差异的方块。导入边界有问题的话，第 1 圈会明显高于更外面。
11-08 第 5 步一轮看到第 1 圈的树类差异密度约是外面的 5 倍，调度让以后每轮都输出这个比值。
"""

from __future__ import annotations

from analysis.mca import read_chunk
from analysis.mca.decor import Diff, compare_chunks


def ring_of(x: int, z: int, tiles, size: int = 4) -> int:
    best = None
    for tx, tz in tiles:
        dx = max(0, tx * size - x, x - (tx * size + size - 1))
        dz = max(0, tz * size - z, z - (tz * size + size - 1))
        d = max(dx, dz)
        best = d if best is None else min(best, d)
    return best


def ring_density(a_root, b_root, tiles, x0: int, z0: int, size: int, max_ring: int = 3) -> dict:
    """比较 [x0, x0+size) × [z0, z0+size) 这块区域，按圈返回 {圈: {chunks, decor, tree, terrain, 每 chunk 密度}}。
    超过 max_ring 的归进 max_ring（"更外圈"）。

    区域最外一行不算：它外面的邻居从来不跑地物（没有树、矿长进来），两边的差异天然偏少，
    会把"外面"的基准拉低（11-08 那次 1.42 倍就是这么来的）。"""
    acc: dict[int, list] = {}
    for x in range(x0, x0 + size):
        for z in range(z0, z0 + size):
            if x in (x0, x0 + size - 1) or z in (z0, z0 + size - 1):
                continue
            ca, cb = read_chunk(a_root, x, z), read_chunk(b_root, x, z)
            if ca is None or cb is None or ca.status != "minecraft:full" or cb.status != "minecraft:full":
                continue
            r = min(ring_of(x, z, tiles), max_ring)
            e = acc.setdefault(r, [0, Diff(examples=[])])
            e[0] += 1
            e[1].add(compare_chunks(ca, cb, max_examples=0))
    out = {}
    for r, (n, d) in sorted(acc.items()):
        out[r] = {"chunks": n, "decor": d.decor, "tree": d.tree, "terrain": d.terrain, "strict": d.strict,
                  "decor_per_chunk": d.decor / n, "tree_per_chunk": d.tree / n, "terrain_per_chunk": d.terrain / n}
    return out


def ratios(density: dict, ring: int = 1, base: int = 2) -> dict:
    """第 ring 圈的密度 ÷ 第 base 圈的密度。

    基准只用紧挨着的第 2 圈：更外面的地形往往不一样（11-08 第 5 步那块区域，离 tile 3 圈以上是另一种地形，
    差异密度只有第 2 圈的 1/3～1/10），合进来会把基准拉低。"""
    density = {int(k): v for k, v in density.items()}
    if ring not in density or base not in density:
        return {}
    out = {}
    for k in ("decor", "tree", "terrain"):
        b = density[base][f"{k}_per_chunk"]
        out[k] = density[ring][f"{k}_per_chunk"] / b if b else None
    return out
