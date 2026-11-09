"""演示面板的一键 A/B（docs/step10-plan.md 7.1 第 3 条）。

A：全新世界，donor 关，飞一轮。B：再换一个全新世界（同 seed，同航线，地形一样），donor reset，donor 开、开关按参数（默认 deadline），
飞同样的速度和时长。两轮各自判有效（吸取 11-09 看门狗那次的教训），结果存进 artifacts/demo-ab-<时间>/，标"演示，粗测"。
"""
from __future__ import annotations

import json
import os
import threading
import time
from pathlib import Path

from bench.demo.control import ALL_WORKERS, FEATURES, SPEEDS, Busy, check_flight, server_cpuset

DEFAULT_DONORS = 3   # demo-v2（11-09）挑出来的：2 核、3 个 donor、deadline、提前量 8，卡住 37%→18%，两次成对降 19、18.5 个百分点


def donor_env(donors):
    """3 个 donor 时 worker3 提到 4 核（和 demo-v2 一样）；核用 MCD_DEMO_WORKER3_CPUSET，没设就用槽位 .env.local 的 WORKER3_CPUSET。"""
    if donors < 3:
        return None
    env = {'WORKER3_CPUS': os.environ.get('MCD_DEMO_WORKER3_CPUS', '4')}
    if os.environ.get('MCD_DEMO_WORKER3_CPUSET'):
        env['WORKER3_CPUSET'] = os.environ['MCD_DEMO_WORKER3_CPUSET']
    return env

NOTE = '演示，粗测：每组 1 次，这台机器上限核跑，可能有别的测试并行，不进第 10 步的矩阵和假设表。'
DEFAULT_START = (-32000.5, 32008.5)   # 第 4 步协议的起点：远离出生点，全新世界里一定没生成过
LIFECYCLE = {'create', 'start', 'restart', 'die', 'kill', 'stop', 'destroy', 'pause', 'unpause', 'oom'}


def _docker_events(since, until):
    from bench.layer2_campaign import docker_events
    return docker_events(since, until)


def _concurrent(events, project):
    """飞行期间别的 compose 项目（或不属于任何项目）的容器生命周期事件。本项目换世界、起停 worker 不算。"""
    out = []
    for e in events:
        if e.get('Type') != 'container' or e.get('Action') not in LIFECYCLE:
            continue
        attrs = e.get('Actor', {}).get('Attributes', {})
        if project and attrs.get('com.docker.compose.project') == project:
            continue
        out.append(f"{attrs.get('name', e.get('Actor', {}).get('ID', '?'))} {e['Action']}")
    return out


def _judge(out_dir, b, cpus, ids_before=None, ids_after=None):
    summary = json.loads((out_dir / 'summary.json').read_text())
    done = json.loads((out_dir / 'raw' / 'done.json').read_text())
    invalid = []
    if summary.get('available_processors') != cpus:
        invalid.append(f"服务器核数不对：要 {cpus} 个，容器里是 {summary.get('available_processors')} 个（换世界时核数没生效）")
    if summary.get('sync_loads'):
        invalid.append(f"玩家实体 tick 里同步加载了 {summary['sync_loads']} 次（假玩家跑到了生成前面），这一轮的卡住不可信")
    donor_events = done.get('provenance', {}).get('donor', 0)
    if b:
        if not donor_events and summary.get('full_resumed'):
            invalid.append(f"飞行期间有 {summary['full_resumed']} 个 chunk 是导入后服务器接着做完的（donor 在干活），"
                           '但 coordinator 没报 DONOR 来源事件：这个版本的 coordinator 还没有上报（fish 第 6 步进 main 后才有），分不清哪些是 donor 写的')
        elif not donor_events:
            invalid.append('飞行期间没有 donor 来源事件：donor 没在干活，或者中途掉线了')
        if ids_before != ids_after:
            invalid.append(f'飞行中 worker 重启过（coordinator 里的 id {ids_before} → {ids_after}），多半是被看门狗杀掉')
    return dict(valid=not invalid, invalid=invalid, donor_events=donor_events, summary=summary, dir=str(out_dir))


def run_ab(runner, controller, flyer, out_root, speed='elytra', seconds=120, features=('deadline',), seed=424242,
           start=DEFAULT_START, events=None, clock=time.time, server_cpus=2, donors=DEFAULT_DONORS):
    check_flight(speed, seconds)
    if donors not in range(1, len(ALL_WORKERS) + 1):
        raise ValueError(f'donor 个数只能是 1～{len(ALL_WORKERS)}')
    cpuset = server_cpuset(server_cpus, runner.slot_cpuset())
    world_env = {'MC_CPUS': str(server_cpus), 'MC_CPUSET': cpuset}   # 换世界时服务器容器按这个核数重建
    if any(f not in FEATURES for f in features):
        raise ValueError(f'开关名只能是 {list(FEATURES)}')
    events = events or _docker_events
    ts = time.strftime('%Y%m%dT%H%M%S', time.gmtime(clock()))
    name = f'demo-ab-{ts}'
    root = Path(out_root) / name
    root.mkdir(parents=True)
    v = SPEEDS[speed]
    t0 = clock()
    steps = []

    def step(text):
        steps.append(dict(t=round(clock() - t0, 1), step=text))

    # A：没有 donor
    step('A：换全新世界')
    runner.script('new_world.sh', f'demoa{ts.lower()}', str(seed), env=world_env, timeout=900)
    step('A：donor 关')
    controller.donor_off()
    for f in FEATURES:
        controller.feature(f, False)
    step('A：飞')
    flyer.fly_sync(f'demo-a-{ts}', start, v, seconds, root / 'A')
    a = _judge(root / 'A', False, server_cpus)
    # B：有 donor，开关按参数
    step('B：换全新世界')
    runner.script('new_world.sh', f'demob{ts.lower()}', str(seed), env=world_env, timeout=900)
    runner.rcon('donor reset')
    step('B：donor 开')
    controller.donor_on(donors, env=donor_env(donors))
    for f in FEATURES:
        controller.feature(f, f in features)
    ids_before = controller.worker_ids()
    step('B：飞')
    flyer.fly_sync(f'demo-b-{ts}', start, v, seconds, root / 'B')
    ids_after = controller.worker_ids()
    b = _judge(root / 'B', True, server_cpus, ids_before, ids_after)
    step('完成')
    conc = _concurrent(events(t0, clock()), getattr(runner, 'project', None))
    a['run'], b['run'] = f'demo-a-{ts}', f'demo-b-{ts}'
    result = dict(name=name, note=NOTE, speed=speed, speed_blocks_per_s=v, seconds=seconds, seed=seed, start=list(start),
                  server_cpus=server_cpus, server_cpuset=cpuset, donors=donors,
                  features=list(features), netem=bool(getattr(controller.poller, 'netem', False)), A=a, B=b,
                  concurrent=conc, steps=steps, dir=str(root))
    (root / 'ab.json').write_text(json.dumps(result, ensure_ascii=False, indent=1) + '\n')
    (root / 'README.md').write_text(
        f'# 演示 A/B：{name}\n\n{NOTE}\n\n'
        f'- A：donor 关；B：{donors} 个 donor，开关 {", ".join(features) or "无"}；服务器 {server_cpus} 核（{cpuset}）；{speed} {v} 格/秒，{seconds} 秒，seed {seed}，两轮各一个全新世界，同一条航线。\n'
        f'- 模拟网络：{"开（35ms / 20mbit / 1% 丢包）" if result["netem"] else "关"}。\n'
        f'- 有效性：A {"有效" if a["valid"] else "无效：" + "；".join(a["invalid"])}；B {"有效" if b["valid"] else "无效：" + "；".join(b["invalid"])}。\n'
        f'- 并发：{"；".join(conc) if conc else "无"}。\n\n两轮的汇总和有效性在 ab.json，归档在 A/、B/。\n')
    return result


class Job:
    """后台跑一次 A/B，同一时间只有一个。controller 给了就在整个过程里占着它（别的控制按钮得到 409）。"""

    def __init__(self, fn, controller=None):
        self.fn, self.controller = fn, controller
        self.thread = None
        self.state, self.result, self.error, self.params = 'idle', None, None, None

    def start(self, speed, seconds, server_cpus=2, donors=DEFAULT_DONORS):
        check_flight(speed, seconds)
        from bench.demo.control import SERVER_CPUS, BadRequest
        if server_cpus not in SERVER_CPUS or type(server_cpus) is not int:
            raise BadRequest(f'服务器核数只能是 {list(SERVER_CPUS)}')
        if type(donors) is not int or donors not in (2, 3):
            raise BadRequest('A/B 的 donor 个数只能是 2 或 3')
        if self.thread and self.thread.is_alive():
            raise Busy('A/B 正在跑')
        self.state, self.result, self.error, self.params = 'running', None, None, dict(speed=speed, seconds=seconds, server_cpus=server_cpus,
                                                                                      donors=donors)

        def go():
            try:
                if self.controller:
                    with self.controller.busy('A/B 对比'):
                        self.result = self.fn(speed=speed, seconds=seconds, server_cpus=server_cpus, donors=donors)
                else:
                    self.result = self.fn(speed=speed, seconds=seconds, server_cpus=server_cpus, donors=donors)
                self.state = 'done'
            except Exception as e:
                self.error, self.state = repr(e), 'failed'

        self.thread = threading.Thread(target=go, daemon=True)
        self.thread.start()

    def status(self):
        return dict(state=self.state, params=self.params, result=self.result, error=self.error)
