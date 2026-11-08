"""第 10 步：按配置文件跑实验矩阵（docs/step10-plan.md 第 2 节）。

一个单元 = 一种实验 + 一组参数值 + 第几次。单元编号由规范 JSON 的哈希算，和键的顺序无关。
这里先有配置、展开、打乱顺序和估时（10.1）；断点续跑（10.2）和真驱动（10.6）在后面。

用法：python -m analysis.run_matrix --config analysis/matrix/smoke.json --plan
"""
from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import random
from pathlib import Path

SPEEDS = ('walk', 'horse', 'elytra')
CHEATS = ('none', 'ore', 'flatten', 'random', 'flat', 'collude')

# 每种实验：哪些键是维度（配置里给列表，逐个展开）、哪些是固定参数、哪些必须有。
SCHEMA = {
    'flight': dict(dims={'donors', 'speed', 'server_cpus', 'latency_ms', 'features', 'bots', 'tile_size'},
                   fixed={'duration_s', 'jitter_ms', 'repeats', 'estimate_minutes'}, required={'donors', 'speed', 'server_cpus', 'duration_s'}),
    'capacity': dict(dims={'donors', 'features', 'server_cpus'},
                     fixed={'repeats', 'estimate_minutes', 'max_bots'}, required={'donors', 'server_cpus'}),
    'verify': dict(dims={'cheat', 'check_rate', 'donors'},
                   fixed={'duration_s', 'repeats', 'estimate_minutes'}, required={'cheat', 'duration_s'}),
}
TOP = {'name', 'seed', 'repeats', 'experiments', 'server_cpuset', 'donor_cpuset', 'rerun_on_interference', 'frametime_import'}
# 每轮估计分钟数的默认值（第 6 节的估计）：起服、预热、停服归档的开销 + 实验本身。
OVERHEAD_MIN = {'flight': 4, 'verify': 5}
CAPACITY_MIN = 60


def _check_value(exp, key, v):
    ok = {
        'donors': lambda x: type(x) is int and x >= 0,
        'speed': lambda x: x in SPEEDS,
        'server_cpus': lambda x: type(x) is int and x >= 1,
        'latency_ms': lambda x: type(x) in (int, float) and x >= 0,
        'features': lambda x: type(x) is str and x != '',
        'bots': lambda x: type(x) is int and x >= 1,
        'tile_size': lambda x: x in (1, 2, 4, 8),
        'cheat': lambda x: x in CHEATS,
        'check_rate': lambda x: type(x) in (int, float) and 0 < x <= 1,
    }[key]
    if not ok(v):
        raise ValueError(f'{exp}.{key} 的值不合法：{v!r}')


def validate(config: dict) -> dict:
    """检查配置，返回原样的 dict；不合法就抛 ValueError。"""
    unknown = set(config) - TOP
    if unknown:
        raise ValueError(f'不认识的顶层键：{sorted(unknown)}')
    for key in ('name', 'seed', 'repeats', 'experiments'):
        if key not in config:
            raise ValueError(f'缺少 {key}')
    if type(config['repeats']) is not int or config['repeats'] < 1:
        raise ValueError('repeats 至少是 1')
    for exp, grids in config['experiments'].items():
        if exp not in SCHEMA:
            raise ValueError(f'不认识的实验：{exp}')
        if not isinstance(grids, list):
            raise ValueError(f'{exp} 要写成网格的列表')
        s = SCHEMA[exp]
        for g in grids:
            extra = set(g) - s['dims'] - s['fixed']
            if extra:
                raise ValueError(f'{exp} 不认识的键：{sorted(extra)}')
            missing = s['required'] - set(g)
            if missing:
                raise ValueError(f'{exp} 缺少：{sorted(missing)}')
            for key in s['dims'] & set(g):
                values = g[key]
                if not isinstance(values, list) or not values:
                    raise ValueError(f'{exp}.{key} 是维度，要写成非空列表')
                for v in values:
                    _check_value(exp, key, v)
            if 'repeats' in g and (type(g['repeats']) is not int or g['repeats'] < 1):
                raise ValueError(f'{exp}.repeats 至少是 1')
    return config


def load_config(path) -> dict:
    return validate(json.loads(Path(path).read_text()))


def cell_id(cell: dict) -> str:
    canon = json.dumps(dict(experiment=cell['experiment'], params=cell['params'], repeat=cell['repeat']),
                       sort_keys=True, separators=(',', ':'))
    return hashlib.sha256(canon.encode()).hexdigest()[:16]


def _normalize(exp: str, params: dict) -> dict:
    """0 个 donor 时开关、延迟、tile 大小都没有意义，统一成默认值，避免同一个原版配置被跑好几遍。"""
    p = dict(params)
    if exp in ('flight', 'capacity') and p.get('donors') == 0:
        if 'features' in p:
            p['features'] = 'v0'
        if 'latency_ms' in p:
            p['latency_ms'] = 0
        p.pop('tile_size', None)
        p.pop('jitter_ms', None)
    return p


def expand(config: dict) -> list[dict]:
    """展开成单元列表（按编号排序、去重）。"""
    cells = {}
    for exp, grids in config['experiments'].items():
        s = SCHEMA[exp]
        for g in grids:
            dims = sorted(s['dims'] & set(g))
            fixed = {k: v for k, v in g.items() if k in s['fixed'] and k not in ('repeats', 'estimate_minutes')}
            repeats = g.get('repeats', config['repeats'])
            for values in itertools.product(*(g[k] for k in dims)):
                params = _normalize(exp, {**fixed, **dict(zip(dims, values))})
                for r in range(1, repeats + 1):
                    cell = dict(experiment=exp, params=params, repeat=r, estimate_minutes=g.get('estimate_minutes'))
                    cells.setdefault(cell_id(cell), cell)
    return [cells[k] for k in sorted(cells)]


def order(cells: list[dict], seed: int) -> list[dict]:
    """按种子打乱跑的顺序（同第 1 步），避免时间漂移混进某一种配置。"""
    out = sorted(cells, key=cell_id)
    random.Random(seed).shuffle(out)
    return out


def estimate_minutes(cell: dict) -> float:
    if cell.get('estimate_minutes') is not None:
        return float(cell['estimate_minutes'])
    if cell['experiment'] == 'capacity':
        return CAPACITY_MIN
    return OVERHEAD_MIN[cell['experiment']] + cell['params']['duration_s'] / 60


def plan(config: dict) -> dict:
    cells = expand(config)
    by = {}
    for c in cells:
        e = by.setdefault(c['experiment'], dict(cells=0, minutes=0.0))
        e['cells'] += 1
        e['minutes'] += estimate_minutes(c)
    total = sum(e['minutes'] for e in by.values())
    return dict(name=config['name'], cells=len(cells), hours=round(total / 60, 2),
                by_experiment={k: dict(cells=v['cells'], hours=round(v['minutes'] / 60, 2)) for k, v in sorted(by.items())})


def main(argv=None):
    parser = argparse.ArgumentParser(description='第 10 步实验矩阵')
    parser.add_argument('--config', required=True)
    parser.add_argument('--plan', action='store_true', help='只展开并估时，不跑')
    args = parser.parse_args(argv)
    config = load_config(args.config)
    if args.plan:
        print(json.dumps(plan(config), ensure_ascii=False, indent=1))
        return
    raise SystemExit('跑矩阵（10.2 / 10.6）还没做；先用 --plan')


if __name__ == '__main__':
    main()
