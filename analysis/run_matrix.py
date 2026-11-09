"""第 10 步：按配置文件跑实验矩阵（docs/step10-plan.md 第 2 节）。

一个单元 = 一种实验 + 一组参数值 + 第几次。单元编号由规范 JSON 的哈希算，和键的顺序无关。
这里有配置、展开、打乱顺序和估时（10.1），断点续跑（10.2）；真驱动（10.6）在后面。

每个单元一个目录 matrix-<名字>/<单元编号>/attempt-NNN/：先写 running 的 manifest，跑完用第 1 步的
bench/archive.py 封存成 passed / failed / invalid / interrupted。重跑时已 passed 且校验通过的单元跳过。

用法：python -m analysis.run_matrix --config analysis/matrix/smoke.json --plan
"""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import itertools
import json
import random
from datetime import datetime, timezone
from pathlib import Path

from bench.archive import seal, verify
from bench.models import atomic_json

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
TOP = {'name', 'seed', 'repeats', 'experiments', 'server_cpuset', 'donor_cpuset', 'rerun_on_interference', 'frametime_import', 'note'}
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


MAX_ATTEMPTS = 3


def _passed(cdir: Path, cid: str) -> bool:
    """这个单元有没有一次封存完好的 passed。"""
    for a in sorted(cdir.glob('attempt-*')):
        try:
            verify(a, cid)
            return True
        except (ValueError, FileNotFoundError, KeyError, json.JSONDecodeError):
            continue
    return False


def _failures(cdir: Path) -> int:
    n = 0
    for m in cdir.glob('attempt-*/manifest.json'):
        try:
            n += json.loads(m.read_text())['status'] in ('failed', 'invalid')
        except (json.JSONDecodeError, KeyError):
            n += 1
    return n


def _record_config(campaign: Path, config: dict):
    path = campaign / 'config.json'
    saved = json.loads(path.read_text()) if path.exists() else dict(configs=[])
    if not saved['configs'] or saved['configs'][-1] != config:
        saved['configs'].append(config)
        atomic_json(path, saved)


def _attempt(cell, cid, cdir, driver, lock, rerun_on_interference) -> str:
    cdir.mkdir(parents=True, exist_ok=True)
    adir = cdir / f'attempt-{len(list(cdir.glob("attempt-*"))) + 1:03}'
    adir.mkdir()
    manifest = dict(status='running', protocol_id=cid, experiment=cell['experiment'], params=cell['params'],
                    repeat=cell['repeat'], started_utc=datetime.now(timezone.utc).isoformat())
    atomic_json(adir / 'manifest.json', manifest)
    try:
        with lock():
            output = driver(cell, adir)
    except KeyboardInterrupt as exc:
        atomic_json(adir / 'manifest.json', dict(manifest, status='interrupted', error=str(exc)))
        raise
    except Exception as exc:  # 驱动失败：记下来，按次数重试
        atomic_json(adir / 'manifest.json', dict(manifest, status='failed', error=f'{type(exc).__name__}: {exc}'))
        return 'failed'
    interference = list(output.get('interference') or [])
    atomic_json(adir / 'result.json', dict(cell_id=cid, experiment=cell['experiment'], params=cell['params'],
                                           repeat=cell['repeat'], output=output))
    status = 'invalid' if interference and rerun_on_interference else 'passed'
    seal(adir, dict(manifest, status=status, concurrent=bool(interference), interference=interference,
                    finished_utc=datetime.now(timezone.utc).isoformat(),
                    **({'error': '测量期间有其他容器事件'} if status == 'invalid' else {})))
    return status


def run(config: dict, drivers: dict, root, lock=contextlib.nullcontext, max_attempts: int = MAX_ATTEMPTS) -> dict:
    """按配置跑矩阵，能断点续跑。drivers：实验名 → driver(cell, 输出目录) -> dict（可带 interference 列表）。
    lock：每个真正执行的单元拿一次（单元之间放锁）。"""
    cells = order(expand(config), config['seed'])
    missing = {c['experiment'] for c in cells} - set(drivers)
    if missing:
        raise ValueError(f'没有驱动：{sorted(missing)}')
    campaign = Path(root) / f'matrix-{config["name"]}'
    campaign.mkdir(parents=True, exist_ok=True)
    _record_config(campaign, config)
    passed, failed, concurrent = 0, [], []
    for cell in cells:
        cid = cell_id(cell)
        cdir = campaign / cid
        while not _passed(cdir, cid) and _failures(cdir) < max_attempts:
            _attempt(cell, cid, cdir, drivers[cell['experiment']], lock, config.get('rerun_on_interference', False))
        if _passed(cdir, cid):
            passed += 1
            if any(json.loads(m.read_text()).get('concurrent') and json.loads(m.read_text())['status'] == 'passed'
                   for m in cdir.glob('attempt-*/manifest.json')):
                concurrent.append(cid)
        else:
            failed.append(cid)
    return dict(campaign=str(campaign), cells=len(cells), passed=passed, failed=failed, concurrent=concurrent)


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
