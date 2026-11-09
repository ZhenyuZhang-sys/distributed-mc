"""第 10 步 10.6：flight 实验的真驱动（按用户 11-09 的"能演示"做，只支持这台机器上能跑的配置）。

一个单元：换一个全新世界（同 seed，服务器按 server_cpus 重建），按 donors 开关 donor（0 或 2 个），按 features 设第 7 步开关，
latency_ms > 0 时开模拟网络（fish 的 netem.sh：35ms / 20mbit / 1% 丢包，固定值），飞一轮（第 4 步 bench.layer2.fly），
按演示 A/B 的规则判有效。无效就抛异常，run_matrix 记 failed 并重试（最多 3 次）。
用的都是演示面板的部件（bench/demo），不另写一套控制。
"""
from __future__ import annotations

import csv
import json
import math
import time
from pathlib import Path

from bench import layer2_stats
from bench.demo import ab
from bench.demo.control import DONORS, FEATURES, SPEEDS, server_cpuset

# 写进 result.json 的指标。不能和参数同名（汇总会把它当成参数）：summary.json 里的 speed、seconds 这些不带。
METRICS = ('stall_s', 'stall_fraction', 'distance_ratio', 'void_mean', 'void_p95', 'latency_p50_ms', 'latency_p95_ms',
           'latency_p99_ms', 'censored_fraction', 'mspt_mean', 'mspt_max', 'cpu_cores_mean', 'full_generated',
           'full_resumed', 'full_loaded', 'sync_loads', 'sync_loads_world', 'available_processors', 'initial_load_s')


def _num(v):
    """封存的 JSON 不许有无穷大：删失照第 4 步写成 'inf'，matrix_summary.collect 读回来变成无穷大。"""
    return 'inf' if isinstance(v, float) and math.isinf(v) else v


def _p95(values):
    values = sorted(v for v in values if v is not None)
    return values[min(len(values) - 1, int(len(values) * 0.95))] if values else None


def _features_on(features):
    if features in (None, 'v0'):
        return ()
    if features == 'all':
        return FEATURES
    if features not in FEATURES:
        raise ValueError(f'不认识的开关组合：{features}')
    return (features,)


def make_flight_driver(runner, controller, flyer, events=None, start=ab.DEFAULT_START, seed=424242):
    events = events or ab._docker_events

    def drive(cell, out) -> dict:
        p = cell['params']
        donors = p['donors']
        if donors not in (0, len(DONORS)):
            raise ValueError(f'这台机器上只能跑 0 或 {len(DONORS)} 个 donor：{donors}')
        if p.get('bots', 1) != 1:
            raise ValueError('演示矩阵只支持 1 个假玩家')
        out = Path(out)
        cpus = p['server_cpus']
        cpuset = server_cpuset(cpus, runner.slot_cpuset())
        net = p.get('latency_ms', 0) > 0
        t0 = time.time()
        name = f"mx{run_cell_id(cell)}"
        runner.script('new_world.sh', name, str(seed), env={'MC_CPUS': str(cpus), 'MC_CPUSET': cpuset}, timeout=900)
        ids_before = []
        if donors == 0:
            controller.donor_off()
            for f in FEATURES:
                controller.feature(f, False)
        else:
            runner.rcon('donor reset')
            controller.donor_on()
            on = _features_on(p.get('features'))
            for f in FEATURES:
                controller.feature(f, f in on)
            if runner.netem_supported():
                controller.netem(net)
            elif net:
                raise RuntimeError('要模拟网络，但 scripts/netem.sh 还不支持 NETEM_SERVICE 和限速')
            ids_before = controller.worker_ids()
        flyer.fly_sync(f'mx-{name}', start, SPEEDS[p['speed']], p['duration_s'], out / 'flight')
        ids_after = controller.worker_ids() if donors else []
        judged = ab._judge(out / 'flight', donors > 0, cpus, ids_before, ids_after)
        if not judged['valid']:
            raise RuntimeError('这一轮无效：' + '；'.join(judged['invalid']))
        s = judged['summary']
        result = {k: _num(s[k]) for k in METRICS if k in s}
        raw = out / 'flight' / 'raw'
        seconds = layer2_stats.load_jsonl(raw / 'seconds.jsonl') if (raw / 'seconds.jsonl').exists() else []
        csv_path = out / 'flight' / 'layer2.csv'
        if csv_path.exists():
            with csv_path.open() as f:
                result['mspt_p95'] = _p95([float(r['mspt_mean']) for r in csv.DictReader(f) if r.get('mspt_mean')])
        else:
            result['mspt_p95'] = _p95([r.get('mspt_mean') for r in seconds])
        prov = layer2_stats.load_jsonl(raw / 'provenance.jsonl') if (raw / 'provenance.jsonl').exists() else []
        leads = layer2_stats.donor_leads(prov, seconds)
        result.update(donor_events=judged['donor_events'], netem=net, valid=True, server_cpuset=cpuset,
                      ahead_total=sum(x >= 0 for x in leads) / len(leads) if leads else None,
                      interference=ab._concurrent(events(t0, time.time()), getattr(runner, 'project', None)))
        return result

    return drive


def run_cell_id(cell) -> str:
    """世界名用的短编号：小写十六进制，符合 lib.sh 的世界名规则。"""
    from analysis.run_matrix import cell_id
    return cell_id(cell)[:16]
