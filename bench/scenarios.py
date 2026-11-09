"""真实场景的飞行路线（调度 11-09，用户：在接近真实玩法的场景下测生成速度、找瓶颈）。

每个场景按 seed 生成一条或两条路线（文字格式给 /bench fly route：yaw,速度,秒数;...，见 Route.java），总时长就是这一轮：
- straight：沿 +x 直飞（第 4 步的基线场景）。
- turning：鞘翅，每 20～40 秒转 30～90 度（左右随机）。
- out_and_back：鞘翅去 60 秒、掉头回 60 秒：去程生成、回程读盘。
- mixed：走路和骑马交替（每段 10～30 秒，方向小幅变化），偶尔停下 3～8 秒。
- two_players：两个玩家同时飞，一个往东，一个往南偏东 60 度以外的方向。
- firework：烟花鞘翅（用户 11-09 "最狠的场景"），从缓存区中心沿 +x 直线飞出去。
- firework_turning：同上，出了缓存区以后每 20～40 秒转 30～90 度。
同一个 seed 每次一样：同组的原版和有 donor 的两轮飞同一条路线。
烟花速度：连续放烟花平飞约 33.5 格/秒（Minecraft Wiki 的 Elytra 页，烟花推进的最大水平速度约 33.5 m/s）。
"""
from __future__ import annotations

import random

ELYTRA, HORSE, WALK = 30.0, 14.0, 4.3
FIREWORK = 33.5
EAST, SOUTH = -90.0, 0.0
SCENARIOS = ('straight', 'turning', 'out_and_back', 'mixed', 'two_players', 'firework', 'firework_turning')
MAX_SEGMENTS = 64


def _fmt(v: float) -> str:
    return str(int(v)) if float(v).is_integer() else f'{v:.3f}'.rstrip('0').rstrip('.')


def _spec(segments) -> str:
    return ';'.join(f'{_fmt(y)},{_fmt(s)},{_fmt(t)}' for y, s, t in segments)


def _fill(rng, seconds, step, first_yaw):
    """按 step(rng, 上一段的 yaw) 生成段，直到凑满 seconds（最后一段截短）。"""
    out, t, yaw = [], 0.0, first_yaw
    while t < seconds - 1e-9 and len(out) < MAX_SEGMENTS:
        yaw, speed, dur = step(rng, yaw, len(out))
        dur = min(dur, seconds - t)
        out.append((yaw, speed, round(dur, 3)))
        t += dur
    if t < seconds - 1e-9:   # 段数到上限：最后一段补满
        y, s, d = out[-1]
        out[-1] = (y, s, round(d + seconds - t, 3))
    return out


def routes(name: str, seed: int, seconds: float = 120, speed: float | None = None, cache_radius_blocks: float = 256) -> list[str]:
    """speed：烟花两个场景可以换速度（找能扛住的最高速度）；cache_radius_blocks：缓存区半径（格），转弯从出了缓存区以后才开始。"""
    if name not in SCENARIOS:
        raise ValueError(f'不认识的场景：{name}，只能是 {SCENARIOS}')
    rng = random.Random(f'{name}:{seed}')
    if name in ('firework', 'firework_turning'):
        v = float(speed or FIREWORK)
        if name == 'firework':
            return [_spec([(EAST, v, seconds)])]
        first = min(seconds, cache_radius_blocks / v + rng.uniform(20, 40))   # 出了缓存区再飞 20～40 秒才第一次转

        def turn(r, yaw, i):
            if i == 0:
                return EAST, v, round(first, 3)
            return round(yaw + r.choice((-1, 1)) * r.uniform(30, 90), 1), v, round(r.uniform(20, 40), 3)
        return [_spec(_fill(rng, seconds, turn, EAST))]
    if name == 'straight':
        return [_spec([(EAST, ELYTRA, seconds)])]
    if name == 'out_and_back':
        half = seconds / 2
        return [_spec([(EAST, ELYTRA, half), (EAST + 180, ELYTRA, half)])]
    if name == 'two_players':
        other = rng.choice([SOUTH, SOUTH + 30, 180.0, 150.0])   # 和东边差 60 度以上
        return [_spec([(EAST, ELYTRA, seconds)]), _spec([(other, ELYTRA, seconds)])]
    if name == 'turning':
        def turn(r, yaw, i):
            new = yaw if i == 0 else yaw + r.choice((-1, 1)) * r.uniform(30, 90)
            return round(new, 1), ELYTRA, round(r.uniform(20, 40), 3)
        return [_spec(_fill(rng, seconds, turn, EAST))]

    def mixed(r, yaw, i):
        if i > 0 and r.random() < 0.25:
            return yaw, 0.0, round(r.uniform(3, 8), 3)          # 停下来看看
        return round(yaw + r.uniform(-60, 60), 1) if i else yaw, r.choice((WALK, HORSE)), round(r.uniform(10, 30), 3)
    segs = _fill(rng, seconds, mixed, EAST)
    # 保证走路、骑马、停下都至少出现一次（短的轮次随机可能凑不齐）
    kinds = {s for _, s, _ in segs}
    fixes = [k for k in (WALK, HORSE, 0.0) if k not in kinds]
    for k, idx in zip(fixes, range(len(segs) - 1, -1, -1)):
        y, _, d = segs[idx]
        segs[idx] = (y, k, d)
    return [_spec(segs)]
